package in.aitradingsystem.news.feature;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.aitradingsystem.config.NewsProperties;
import in.aitradingsystem.news.NewsAnalysisScheduler;
import in.aitradingsystem.news.NewsAnalysisService;
import in.aitradingsystem.news.NewsArticleBridge;
import in.aitradingsystem.news.NewsService;
import in.aitradingsystem.news.NewsSourceHealthRepository;
import in.aitradingsystem.news.analysis.NewsAnalysisParser;
import in.aitradingsystem.news.analysis.NewsAnalysisProvider;
import in.aitradingsystem.scanner.ResearchScanRepository;
import in.aitradingsystem.support.AbstractPostgresIT;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Wiring test for the full chain: RSS-ingested row → approved bridge → analyzer → as-of features →
 * persisted scan context → customer decision history.
 *
 * <p>Every article, source row and provider response here is a labelled FIXTURE written to prove the
 * wiring and the source attribution at each stage. None of it is real news, and passing this test is
 * not evidence about any market, source or model.
 */
class NewsEndToEndWiringIT extends AbstractPostgresIT {
    @Autowired JdbcClient jdbc;
    @Autowired NewsService news;
    @Autowired NewsSourceHealthRepository health;
    @Autowired ResearchScanRepository scans;
    @Autowired NewsFeatureService features;
    @Autowired in.aitradingsystem.news.policy.NewsSourcePolicyService policy;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    private static String hash64() {
        return (UUID.randomUUID().toString() + UUID.randomUUID().toString()).replace("-", "");
    }

    @Test
    void approvedRssRowTravelsToDecisionHistoryWithSourceAttributionAtEveryStage() throws Exception {
        String sym = "W" + UUID.randomUUID().toString().replace("-", "").substring(0, 9).toUpperCase();
        Instant published = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).minusSeconds(60);
        String url = "https://www.moneycontrol.com/news/wiring-fixture-" + UUID.randomUUID() + ".html";

        // 1. RSS-ingested row (fixture), as NewsRepository would write it.
        jdbc.sql("""
                INSERT INTO news_item (source, publisher, source_url, published_at, title,
                                       normalized_content, content_hash)
                VALUES ('moneycontrol', 'Moneycontrol', :url, :publishedAt, :title, 'fixture body', :hash)
                """)
            .param("url", url)
            .param("publishedAt", OffsetDateTime.ofInstant(published, ZoneOffset.UTC))
            .param("title", "FIXTURE wiring headline for " + sym)
            .param("hash", hash64())
            .update();

        // 2. Bridge: approved source only, into the analyzer store.
        in.aitradingsystem.news.policy.NewsSourcePolicyTestSupport.approveFixture(jdbc, "moneycontrol");
        NewsArticleBridge bridge = new NewsArticleBridge(jdbc, news, health, policy, List.of("moneycontrol"), Clock.systemUTC());
        bridge.runOnce();
        var article = jdbc.sql("SELECT id, source FROM news_articles WHERE url = :url")
            .param("url", url).query((rs, n) -> new Object[] {rs.getObject("id", UUID.class), rs.getString("source")})
            .optional();
        assertThat(article).isPresent();
        UUID articleId = (UUID) article.get()[0];
        assertThat(article.get()[1]).isEqualTo("moneycontrol");

        // 3. Analyzer: scheduled run with a FIXTURE provider that returns a valid structured response.
        String response = """
            {"sentiment":"POSITIVE","directionScore":0.6,"category":"EARNINGS","confidence":0.8,
             "materiality":0.8,"horizon":"INTRADAY","novelty":0.5,"contradiction":false,"ambiguity":false,
             "impactedSymbols":["%s"],"evidence":"FIXTURE paraphrase","risks":[]}
            """.formatted(sym);
        NewsAnalysisProvider provider = new NewsAnalysisProvider() {
            @Override public String name() { return "FAKE"; }
            @Override public String model() { return "fixture-model"; }
            @Override public boolean configured() { return true; }
            @Override public Completion complete(String systemPrompt, String userPrompt) {
                return new Completion(response, "req-fixture");
            }
        };
        NewsAnalysisService analysis = new NewsAnalysisService(jdbc, mapper,
            new NewsProperties("FAKE", "m", "m", null, null, 5000, 30000, 2),
            List.of(provider), new NewsAnalysisParser(mapper), news);
        // The fixture article comes from the approved "moneycontrol" source, so it is the only approved id here.
        new NewsAnalysisScheduler(jdbc, analysis, Clock.systemUTC(), 20, List.of("moneycontrol"), policy).runOnce();
        String analysisStatus = jdbc.sql("""
                SELECT status FROM news_analyses WHERE article_id = :id ORDER BY created_at DESC LIMIT 1
                """).param("id", articleId).query(String.class).single();
        assertThat(analysisStatus).isEqualTo("ANALYZED");

        // 4. As-of features for the scanned instrument, verified against the impacted symbol.
        long instrumentId = 7_700_000L + Math.abs(sym.hashCode() % 100_000);
        NewsContext ctx = features.contextFor(instrumentId, sym + "-EQ", Instant.now());
        assertThat(ctx.status()).isEqualTo(NewsContext.STATUS_INCLUDED_EVIDENCE);
        assertThat(ctx.blackout()).isTrue();
        NewsContext.Evidence e = ctx.evidence().getFirst();
        assertThat(e.source()).isEqualTo("moneycontrol");
        assertThat(e.url()).isEqualTo(url);
        assertThat(e.publishedAt()).isEqualTo(published);
        assertThat(e.analysisAvailableAt()).isAfterOrEqualTo(published);
        assertThat(e.eventClass()).isEqualTo("EARNINGS_RESULT");

        // 5. Persist on a scanned candidate (write-once), then read through the customer endpoint.
        UUID run = UUID.randomUUID();
        Instant signal = published;
        jdbc.sql("""
                INSERT INTO research_scan_run (id, interval, status, requested_limit, scanned_count,
                    candidate_count, strategy_set, started_at, completed_at)
                VALUES (:id, 'FIVE_MINUTE', 'SUCCEEDED', 10, 1, 1, 'TEST', now(), now())
                """).param("id", run).update();
        jdbc.sql("""
                INSERT INTO research_scan_candidate (scan_run_id, instrument_id, trading_symbol, exchange,
                    interval, strategy, score, entry_price, stop_price, target_price, signal_candle_time,
                    reasons, data_cutoff_at)
                VALUES (:run, :inst, :sym, 'NSE', 'FIVE_MINUTE', 'MA_RECLAIM_V1', 0.5, 100, 95, 110,
                    :signal, 'fixture', :signal)
                """)
            .param("run", run).param("inst", instrumentId).param("sym", sym + "-EQ")
            .param("signal", OffsetDateTime.ofInstant(signal, ZoneOffset.UTC)).update();
        scans.recordNewsContext(run, instrumentId, "MA_RECLAIM_V1", signal, features.toJson(ctx));

        String body = mvc.perform(MockMvcRequestBuilders.get("/api/customer/mind/v1/decision-history")
                .with(SecurityMockMvcRequestPostProcessors.jwt()
                    .jwt(j -> j.issuer("https://test.example").subject("e2e-" + sym))
                    .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
            .andReturn().getResponse().getContentAsString();
        JsonNode decision = null;
        for (JsonNode d : mapper.readTree(body).get("decisions")) {
            if ((sym + "-EQ").equals(d.get("tradingSymbol").asText())) decision = d;
        }
        assertThat(decision).isNotNull();
        JsonNode news = decision.get("news");
        assertThat(news.get("status").asText()).isEqualTo("INCLUDED_EVIDENCE");
        assertThat(news.get("blackout").asBoolean()).isTrue();
        assertThat(news.get("evidence").get(0).get("source").asText()).isEqualTo("moneycontrol");
        assertThat(news.get("evidence").get(0).get("eventClass").asText()).isEqualTo("EARNINGS_RESULT");
        assertThat(decision.get("paperOrderSubmitted").asBoolean()).isFalse();
        assertThat(decision.get("liveMoney").asBoolean()).isFalse();
        assertThat(decision.get("executionEnabled").asBoolean()).isFalse();
    }
}
