package in.aitradingsystem.news.feature;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.aitradingsystem.news.NewsService;
import in.aitradingsystem.scanner.ResearchScanRepository;
import in.aitradingsystem.support.AbstractPostgresIT;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * Real-Postgres proof of the as-of news join: no lookahead, latest-analysis-wins, verified
 * instrument mapping, expiry, write-once candidate context, and the customer decision-history read.
 */
class NewsMindContextIT extends AbstractPostgresIT {
    private static final Instant PUBLISHED = Instant.parse("2026-11-20T09:00:00Z");
    private static final Instant ANALYZED = PUBLISHED.plusSeconds(60);

    @Autowired JdbcClient jdbc;
    @Autowired NewsService news;
    @Autowired NewsFeatureService features;
    @Autowired ResearchScanRepository scans;
    @Autowired MockMvc mvc;

    /** Unique base symbol per test so shared database rows cannot interfere. */
    private static String symbol() {
        return "T" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
    }

    private String article(String sym, Instant publishedAt) {
        String url = "https://news.example.test/markets/" + UUID.randomUUID();
        news.ingest(new NewsService.IngestRequest(List.of(new NewsService.ArticleInput(
            "TEST-SOURCE", null, "Headline for " + sym, url, "Excerpt " + sym, publishedAt))));
        return jdbc.sql("SELECT id::text FROM news_articles WHERE url = :url")
            .param("url", url).query(String.class).single();
    }

    private void analysis(String articleId, Instant createdAt, String status, String symbols,
                          String sentiment, String direction, String confidence, String materiality,
                          String horizon, boolean contradiction) {
        jdbc.sql("""
                INSERT INTO news_analyses (id, article_id, provider, model, status, sentiment,
                    direction_score, confidence, materiality, horizon, contradiction, ambiguity,
                    impacted_symbols, evidence, latency_ms, created_at)
                VALUES (:id, CAST(:article AS uuid), 'TEST', 'test-model', :status, :sentiment,
                    CAST(:direction AS numeric), CAST(:confidence AS numeric), CAST(:materiality AS numeric),
                    :horizon, :contradiction, false, :symbols, 'Test evidence paraphrase', 1, :createdAt)
                """)
            .param("id", UUID.randomUUID())
            .param("article", articleId)
            .param("status", status)
            .param("sentiment", sentiment)
            .param("direction", direction)
            .param("confidence", confidence)
            .param("materiality", materiality)
            .param("horizon", horizon)
            .param("contradiction", contradiction)
            .param("symbols", symbols)
            .param("createdAt", OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC))
            .update();
    }

    private NewsContext context(String sym, Instant asOf) {
        return features.contextFor(4242L, sym + "-EQ", asOf);
    }

    @Test
    void verifiedAnalysisIsIncludedOnlyAfterItBecameKnowable() {
        String sym = symbol();
        String a = article(sym, PUBLISHED);
        analysis(a, ANALYZED, "ANALYZED", "[\"" + sym + "\"]", "POSITIVE", "0.600", "0.800", "0.700",
            "INTRADAY", false);

        NewsContext after = context(sym, ANALYZED.plusSeconds(60));
        assertThat(after.status()).isEqualTo(NewsContext.STATUS_INCLUDED_EVIDENCE);
        assertThat(after.direction()).isEqualTo("POSITIVE");
        assertThat(after.netDirection()).isEqualByComparingTo("0.600");
        assertThat(after.evidence()).hasSize(1);
        assertThat(after.evidence().getFirst().included()).isTrue();
        assertThat(after.evidence().getFirst().analysisAvailableAt()).isEqualTo(ANALYZED);

        // Before the analysis existed, the article must not be visible at all: no lookahead.
        NewsContext before = context(sym, ANALYZED.minusSeconds(1));
        assertThat(before.status()).isEqualTo(NewsContext.STATUS_NO_COVERAGE);
        assertThat(before.evidence()).isEmpty();
    }

    @Test
    void laterReanalysisCannotRewriteEarlierAsOfView() {
        String sym = symbol();
        String a = article(sym, PUBLISHED);
        analysis(a, ANALYZED, "ANALYZED", "[\"" + sym + "\"]", "POSITIVE", "0.600", "0.800", "0.700",
            "INTRADAY", false);
        analysis(a, ANALYZED.plusSeconds(600), "FAILED", null, null, null, null, null, "INTRADAY", false);

        // At 2 minutes the later FAILED row does not exist yet: the ANALYZED verdict is what was known.
        assertThat(context(sym, ANALYZED.plusSeconds(120)).status())
            .isEqualTo(NewsContext.STATUS_INCLUDED_EVIDENCE);
        // At 15 minutes the newest analysis is FAILED, so the item is excluded.
        NewsContext later = context(sym, ANALYZED.plusSeconds(900));
        assertThat(later.status()).isEqualTo(NewsContext.STATUS_NO_INCLUDED_EVIDENCE);
        assertThat(later.evidence().getFirst().status()).isEqualTo("EXCLUDED_ANALYSIS_FAILED");
    }

    @Test
    void symbolNotEqualToScannedInstrumentIsNeverAttached() {
        String sym = symbol();
        String a = article("OTHER" + sym, PUBLISHED);
        analysis(a, ANALYZED, "ANALYZED", "[\"OTHER" + sym + "\"]", "NEGATIVE", "-0.900", "0.900", "0.900",
            "INTRADAY", false);
        assertThat(context(sym, ANALYZED.plusSeconds(120)).status()).isEqualTo(NewsContext.STATUS_NO_COVERAGE);
    }

    @Test
    void expiredAndUncertainEvidenceNeverAddsDirection() {
        String sym = symbol();
        String a = article(sym, PUBLISHED);
        analysis(a, ANALYZED, "ANALYZED", "[\"" + sym + "\"]", "POSITIVE", "0.900", "0.900", "0.900",
            "INTRADAY", false);
        NewsContext expired = context(sym, PUBLISHED.plusSeconds(86_400L * 2));
        assertThat(expired.status()).isEqualTo(NewsContext.STATUS_NO_INCLUDED_EVIDENCE);
        assertThat(expired.netDirection()).isNull();
        assertThat(expired.evidence().getFirst().status()).isEqualTo("EXCLUDED_EXPIRED");

        String uncertainSym = symbol();
        String u = article(uncertainSym, PUBLISHED);
        analysis(u, ANALYZED, "ANALYZED", "[\"" + uncertainSym + "\"]", "POSITIVE", "0.900", "0.900", "0.900",
            "INTRADAY", true);
        NewsContext uncertain = context(uncertainSym, ANALYZED.plusSeconds(120));
        assertThat(uncertain.status()).isEqualTo(NewsContext.STATUS_NO_INCLUDED_EVIDENCE);
        assertThat(uncertain.uncertainty()).isEqualTo("HIGH");
        assertThat(uncertain.direction()).isEqualTo("UNKNOWN");
    }

    @Test
    void scanCandidateStoresContextOnceAndDecisionHistoryShowsItOrNotRecorded() throws Exception {
        String sym = symbol();
        String a = article(sym, PUBLISHED);
        analysis(a, ANALYZED, "ANALYZED", "[\"" + sym + "\"]", "POSITIVE", "0.600", "0.800", "0.700",
            "INTRADAY", false);
        UUID run = UUID.randomUUID();
        Instant signal = Instant.parse("2026-11-20T09:30:00Z");
        Instant completed = Instant.now();
        jdbc.sql("""
                INSERT INTO research_scan_run (id, interval, status, requested_limit, scanned_count,
                    candidate_count, strategy_set, started_at, completed_at)
                VALUES (:id, 'FIVE_MINUTE', 'SUCCEEDED', 10, 1, 2, 'TEST', :completed, :completed)
                """)
            .param("id", run).param("completed", OffsetDateTime.ofInstant(completed, ZoneOffset.UTC)).update();
        for (String strategy : List.of("MA_RECLAIM_V1", "VOLUME_BREAKOUT_V1")) {
            jdbc.sql("""
                    INSERT INTO research_scan_candidate (scan_run_id, instrument_id, trading_symbol, exchange,
                        interval, strategy, score, entry_price, stop_price, target_price, signal_candle_time,
                        reasons, data_cutoff_at)
                    VALUES (:run, 4242, :sym, 'NSE', 'FIVE_MINUTE', :strategy, 0.5, 100, 95, 110, :signal,
                        'test', :signal)
                    """)
                .param("run", run).param("sym", sym + "-EQ").param("strategy", strategy)
                .param("signal", OffsetDateTime.ofInstant(signal, ZoneOffset.UTC)).update();
        }

        NewsContext ctx = context(sym, ANALYZED.plusSeconds(120));
        String json = features.toJson(ctx);
        scans.recordNewsContext(run, 4242L, "MA_RECLAIM_V1", signal, json);
        // Write-once: a second attempt with different content does not overwrite the first.
        scans.recordNewsContext(run, 4242L, "MA_RECLAIM_V1", signal, features.toJson(
            NewsContext.unavailable(4242L, sym, Instant.now())));

        String body = mvc.perform(MockMvcRequestBuilders.get("/api/customer/mind/v1/decision-history")
                .with(SecurityMockMvcRequestPostProcessors.jwt()
                    .jwt(j -> j.issuer("https://test.example").subject("news-it-" + sym))
                    .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
            .andReturn().getResponse().getContentAsString();
        JsonNode decisions = new ObjectMapper().readTree(body).get("decisions");
        JsonNode withNews = null;
        JsonNode withoutNews = null;
        for (JsonNode d : decisions) {
            if (!(sym + "-EQ").equals(d.get("tradingSymbol").asText())) continue;
            if ("MA_RECLAIM_V1".equals(d.get("strategy").asText())) withNews = d;
            if ("VOLUME_BREAKOUT_V1".equals(d.get("strategy").asText())) withoutNews = d;
        }
        assertThat(withNews).isNotNull();
        assertThat(withNews.get("news").get("status").asText()).isEqualTo(NewsContext.STATUS_INCLUDED_EVIDENCE);
        assertThat(withNews.get("news").get("contract").asText()).isEqualTo("news.context.v1");
        assertThat(withNews.get("news").get("netDirection").decimalValue()).isEqualByComparingTo("0.600");
        assertThat(withNews.get("news").get("evidence").get(0).get("source").asText()).isEqualTo("TEST-SOURCE");
        assertThat(withNews.get("paperOrderSubmitted").asBoolean()).isFalse();
        assertThat(withNews.get("liveMoney").asBoolean()).isFalse();
        // Not recorded is explicit null, never an invented "no news" verdict.
        assertThat(withoutNews).isNotNull();
        assertThat(withoutNews.get("news").isNull()).isTrue();
    }
}
