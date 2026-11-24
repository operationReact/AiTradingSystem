package in.aitradingsystem.news.feature;

import static org.assertj.core.api.Assertions.assertThat;

import in.aitradingsystem.news.NewsService;
import in.aitradingsystem.news.policy.NewsSourcePolicyTestSupport;
import in.aitradingsystem.support.AbstractPostgresIT;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Real-Postgres checks that the analyzer category reaches the context as an event class, that a
 * material company event opens a blackout, and that the F&amp;O underlying resolution attaches without
 * changing any news fact. Article and analysis rows are labelled fixtures, not real news.
 */
class NewsFeatureEnrichmentIT extends AbstractPostgresIT {
    @Autowired JdbcClient jdbc;
    @Autowired NewsService news;
    @Autowired NewsFeatureService features;

    private static String symbol() {
        return "E" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
    }

    private String article(Instant publishedAt) {
        NewsSourcePolicyTestSupport.approveFixture(jdbc, "fixture-source");
        String url = "https://news.example.test/enrichment/" + UUID.randomUUID();
        news.ingest(new NewsService.IngestRequest(List.of(new NewsService.ArticleInput(
            "fixture-source", null, "Fixture headline", url, "Fixture excerpt", publishedAt))));
        return jdbc.sql("SELECT id::text FROM news_articles WHERE url = :url")
            .param("url", url).query(String.class).single();
    }

    private void analysis(String articleId, Instant createdAt, String category, String materiality,
                          String symbols) {
        jdbc.sql("""
                INSERT INTO news_analyses (id, article_id, provider, model, status, sentiment,
                    direction_score, confidence, materiality, horizon, contradiction, ambiguity,
                    impacted_symbols, evidence, category, latency_ms, created_at)
                VALUES (:id, CAST(:article AS uuid), 'FIXTURE', 'fixture-model', 'ANALYZED', 'POSITIVE',
                    0.600, 0.800, CAST(:mat AS numeric), 'INTRADAY', false, false, :symbols,
                    'Fixture paraphrase', :category, 1, :createdAt)
                """)
            .param("id", UUID.randomUUID())
            .param("article", articleId)
            .param("mat", materiality)
            .param("symbols", symbols)
            .param("category", category)
            .param("createdAt", OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC))
            .update();
    }

    @Test
    void materialEarningsEventOpensBlackoutAndCarriesEventClassAndFreshness() {
        String sym = symbol();
        Instant published = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).minusSeconds(600);
        String a = article(published);
        analysis(a, published.plusSeconds(30), "EARNINGS", "0.80", "[\"" + sym + "\"]");

        NewsContext ctx = features.contextFor(9001L, sym + "-EQ", Instant.now());
        assertThat(ctx.status()).isEqualTo(NewsContext.STATUS_INCLUDED_EVIDENCE);
        assertThat(ctx.blackout()).isTrue();
        NewsContext.Evidence e = ctx.evidence().getFirst();
        assertThat(e.eventClass()).isEqualTo("EARNINGS_RESULT");
        assertThat(e.blackoutUntil()).isEqualTo(published.plusSeconds(86_400));
        // Ten minutes into a 24-hour validity window is well inside the first third: FRESH.
        assertThat(e.freshness()).isEqualTo("FRESH");
    }

    @Test
    void lowMaterialityOrMacroNewsDoesNotOpenABlackout() {
        String sym = symbol();
        Instant published = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).minusSeconds(600);
        String a = article(published);
        analysis(a, published.plusSeconds(30), "MACRO", "0.90", "[\"" + sym + "\"]");
        NewsContext macro = features.contextFor(9002L, sym + "-EQ", Instant.now());
        assertThat(macro.blackout()).isFalse();
        assertThat(macro.evidence().getFirst().eventClass()).isEqualTo("MACRO_BACKDROP");

        String sym2 = symbol();
        String b = article(published);
        analysis(b, published.plusSeconds(30), "EARNINGS", "0.40", "[\"" + sym2 + "\"]");
        assertThat(features.contextFor(9003L, sym2 + "-EQ", Instant.now()).blackout()).isFalse();
    }

    @Test
    void fnoResolutionAttachesWithoutChangingAnyNewsFact() {
        String sym = symbol();
        Instant published = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).minusSeconds(600);
        analysis(article(published), published.plusSeconds(30), "EARNINGS", "0.80", "[\"" + sym + "\"]");
        NewsContext base = features.contextFor(9004L, sym + "-EQ", Instant.now());

        NewsContext listed = base.withFno(FnoUnderlyingPolicy.resolve(sym, Set.of(sym), true, "fixture-catalogue"));
        assertThat(listed.fnoStatus()).isEqualTo("LISTED_FNO");
        assertThat(listed.fnoUnderlying()).isEqualTo(sym);
        assertThat(listed.netDirection()).isEqualByComparingTo(base.netDirection());
        assertThat(listed.includedCount()).isEqualTo(base.includedCount());
        assertThat(listed.evidence()).isEqualTo(base.evidence());

        NewsContext unknown = base.withFno(FnoUnderlyingPolicy.resolve(sym, Set.of(), false, "fixture-catalogue"));
        assertThat(unknown.fnoStatus()).isEqualTo("UNKNOWN_CATALOG");
        assertThat(unknown.fnoUnderlying()).isNull();
    }
}
