package in.aitradingsystem.news.feature;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.aitradingsystem.news.NewsService;
import in.aitradingsystem.news.policy.NewsSourcePolicyTestSupport;
import in.aitradingsystem.support.AbstractPostgresIT;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Real-Postgres proof that historical signal annotation never uses future news. */
class NewsBacktestAnnotatorIT extends AbstractPostgresIT {
    private static final Instant PUBLISHED = Instant.parse("2026-11-18T09:00:00Z");
    private static final Instant ANALYZED = PUBLISHED.plusSeconds(60);

    @Autowired JdbcClient jdbc;
    @Autowired NewsService news;
    @Autowired NewsBacktestAnnotator annotator;

    @BeforeEach
    void approveOnlyFictionalHistoricalSource() {
        NewsSourcePolicyTestSupport.approveFixture(jdbc, "TEST-SOURCE");
        jdbc.sql("""
                UPDATE news_source_policy
                   SET effective_from = :known, updated_at = :known
                 WHERE source_id = 'TEST-SOURCE'
                """)
            .param("known", OffsetDateTime.ofInstant(
                Instant.parse("2020-01-01T00:00:00Z"), ZoneOffset.UTC)).update();
    }

    @AfterEach
    void resetFictionalRights() {
        NewsSourcePolicyTestSupport.resetToPending(jdbc, "TEST-SOURCE");
    }

    private static String symbol() {
        return "B" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
    }

    private String article(Instant publishedAt) {
        String url = "https://news.example.test/backtest/" + UUID.randomUUID();
        news.ingest(new NewsService.IngestRequest(List.of(new NewsService.ArticleInput(
            "TEST-SOURCE", null, "Backtest headline", url, "Backtest excerpt", publishedAt))));
        String id = jdbc.sql("SELECT id::text FROM news_articles WHERE url = :url")
            .param("url", url).query(String.class).single();
        // Synthetic fixture mimics a source actually ingested before historical
        // research; real backfilled publisher dates alone must never pass.
        jdbc.sql("""
                UPDATE news_articles SET ingested_at = :known
                 WHERE id = CAST(:id AS uuid)
                """)
            .param("known", OffsetDateTime.ofInstant(publishedAt, ZoneOffset.UTC))
            .param("id", id).update();
        return id;
    }

    private void analysis(String articleId, Instant createdAt, String status, String symbols,
                          String direction) {
        boolean analyzed = "ANALYZED".equals(status);
        jdbc.sql("""
                INSERT INTO news_analyses (id, article_id, provider, model, status, sentiment,
                    direction_score, confidence, materiality, horizon, contradiction, ambiguity,
                    impacted_symbols, evidence, latency_ms, created_at)
                VALUES (:id, CAST(:article AS uuid), 'TEST', 'test-model', :status, :sentiment,
                    CAST(:direction AS numeric), CAST(:conf AS numeric), CAST(:mat AS numeric), 'INTRADAY', false, false, :symbols,
                    'Backtest evidence', 1, :createdAt)
                """)
            .param("id", UUID.randomUUID())
            .param("article", articleId)
            .param("status", status)
            .param("sentiment", analyzed ? "POSITIVE" : null)
            .param("direction", analyzed ? direction : null)
            .param("conf", analyzed ? "0.800" : null)
            .param("mat", analyzed ? "0.700" : null)
            .param("symbols", analyzed ? symbols : null)
            .param("createdAt", OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC))
            .update();
    }

    @Test
    void historicalSignalsUseOnlyNewsKnownAtEachSignalTime() {
        String sym = symbol();
        String a = article(PUBLISHED);
        analysis(a, ANALYZED, "ANALYZED", "[\"" + sym + "\"]", "0.600");
        // A later re-analysis that flips to FAILED must not leak into earlier signal annotations.
        analysis(a, ANALYZED.plusSeconds(600), "FAILED", null, null);

        List<NewsBacktestAnnotator.SignalAnnotation> series = annotator.annotate(7L, sym + "-EQ", List.of(
            PUBLISHED.plusSeconds(30),   // analysis not yet created
            PUBLISHED.plusSeconds(120),  // only the ANALYZED verdict is knowable
            PUBLISHED.plusSeconds(900))); // latest analysis is FAILED

        assertThat(series.get(0).context().status()).isEqualTo(NewsContext.STATUS_NO_COVERAGE);
        assertThat(series.get(1).context().status()).isEqualTo(NewsContext.STATUS_INCLUDED_EVIDENCE);
        assertThat(series.get(1).context().evidence().getFirst().analysisAvailableAt()).isEqualTo(ANALYZED);
        assertThat(series.get(2).context().status()).isEqualTo(NewsContext.STATUS_NO_INCLUDED_EVIDENCE);
        assertThat(series).allSatisfy(s -> assertThat(s.leakageViolations()).isEmpty());

        NewsBacktestAnnotator.ShadowSummary summary = annotator.summarize(series);
        assertThat(summary.contract()).isEqualTo(NewsBacktestAnnotator.CONTRACT);
        assertThat(summary.signals()).isEqualTo(3);
        assertThat(summary.withIncludedNews()).isEqualTo(1);
        assertThat(summary.withoutIncludedNews()).isEqualTo(2);
        assertThat(summary.positiveEvidence()).isEqualTo(1);
        assertThat(summary.leakageViolationCount()).isZero();
        assertThat(summary.exclusionReasons()).containsEntry("EXCLUDED_ANALYSIS_FAILED", 1);
        assertThat(summary.limitations()).anyMatch(l -> l.contains("No incremental value is claimed"));
    }

    @Test
    void expiredNewsIsNotCarriedIntoLaterSignals() {
        String sym = symbol();
        String a = article(PUBLISHED);
        analysis(a, ANALYZED, "ANALYZED", "[\"" + sym + "\"]", "0.900");

        List<NewsBacktestAnnotator.SignalAnnotation> series = annotator.annotate(8L, sym + "-EQ",
            List.of(PUBLISHED.plusSeconds(120), PUBLISHED.plusSeconds(86_400L * 2)));

        assertThat(series.get(0).context().status()).isEqualTo(NewsContext.STATUS_INCLUDED_EVIDENCE);
        assertThat(series.get(1).context().status()).isEqualTo(NewsContext.STATUS_NO_INCLUDED_EVIDENCE);
        assertThat(series.get(1).context().evidence().getFirst().status()).isEqualTo("EXCLUDED_EXPIRED");
        assertThat(series.get(1).context().netDirection()).isNull();
    }

    @Test
    void rejectsUnboundedOrMissingSignalSeries() {
        assertThatThrownBy(() -> annotator.annotate(1L, "X-EQ", null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> annotator.annotate(1L, "X-EQ",
                java.util.Collections.nCopies(NewsBacktestAnnotator.MAX_SIGNALS + 1, Instant.EPOCH)))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
