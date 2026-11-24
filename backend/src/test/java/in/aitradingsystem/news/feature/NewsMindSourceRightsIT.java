package in.aitradingsystem.news.feature;

import static org.assertj.core.api.Assertions.assertThat;

import in.aitradingsystem.news.NewsService;
import in.aitradingsystem.news.policy.NewsSourcePolicyTestSupport;
import in.aitradingsystem.support.AbstractPostgresIT;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Rights are a prerequisite for an advisory Mintick Mind news context.
 * Synthetic source permissions do not authorize any real publisher.
 */
class NewsMindSourceRightsIT extends AbstractPostgresIT {
    @Autowired JdbcClient jdbc;
    @Autowired NewsService news;
    @Autowired NewsFeatureService features;

    @Test
    void deniedRevokedAndDisplayBlockedSourcesCannotSupplyMindEvidence() {
        String sym = "R" + UUID.randomUUID().toString().replace("-", "").substring(0, 9).toUpperCase();
        String source = "fictional-mind-" + UUID.randomUUID();
        Instant published = Instant.now().minusSeconds(300);
        Instant analyzed = published.plusSeconds(60);
        Instant decision = Instant.now();
        String url = "https://news.example.test/fictional-mind/" + UUID.randomUUID();

        news.ingest(new NewsService.IngestRequest(List.of(
            new NewsService.ArticleInput(source, null, "FICTIONAL headline for " + sym,
                url, "FICTIONAL licensed-only-for-fixture description", published))));
        UUID articleId = jdbc.sql("SELECT id FROM news_articles WHERE url=:url")
            .param("url", url).query(UUID.class).single();
        jdbc.sql("""
                INSERT INTO news_analyses
                    (id, article_id, provider, model, status, sentiment, direction_score,
                     confidence, materiality, horizon, contradiction, ambiguity,
                     impacted_symbols, evidence, latency_ms, created_at)
                VALUES (:id, :article, 'TEST', 'fictional-model', 'ANALYZED', 'POSITIVE',
                        0.75, 0.85, 0.90, 'INTRADAY', false, false,
                        :symbols, 'FICTIONAL proof', 1, :analyzed)
                """)
            .param("id", UUID.randomUUID())
            .param("article", articleId)
            .param("symbols", "[\"" + sym + "\"]")
            .param("analyzed", OffsetDateTime.ofInstant(analyzed, ZoneOffset.UTC))
            .update();

        NewsContext denied = features.contextFor(4242L, sym + "-EQ", decision);
        assertThat(denied.status()).isEqualTo(NewsContext.STATUS_NO_COVERAGE);
        assertThat(denied.netDirection()).isNull();
        assertThat(denied.evidence()).isEmpty();

        NewsSourcePolicyTestSupport.approveFixture(jdbc, source);
        NewsContext allowed = features.contextFor(4242L, sym + "-EQ", decision);
        assertThat(allowed.status()).isEqualTo(NewsContext.STATUS_INCLUDED_EVIDENCE);
        assertThat(allowed.evidence()).singleElement()
            .extracting(NewsContext.Evidence::source).isEqualTo(source);

        jdbc.sql("""
                UPDATE news_source_policy
                   SET permitted_uses = ARRAY['INGEST','RETAIN','NLP_TRANSFER']::text[]
                 WHERE source_id = :source
                """).param("source", source).update();
        NewsContext displayBlocked = features.contextFor(4242L, sym + "-EQ", decision);
        assertThat(displayBlocked.status()).isEqualTo(NewsContext.STATUS_NO_COVERAGE);
        assertThat(displayBlocked.evidence()).isEmpty();
        assertThat(displayBlocked.netDirection()).isNull();

        jdbc.sql("""
                UPDATE news_source_policy
                   SET license_state = 'REVOKED',
                       permitted_uses = ARRAY['INGEST','RETAIN','DISPLAY','NLP_TRANSFER']::text[]
                 WHERE source_id = :source
                """).param("source", source).update();
        NewsContext revoked = features.contextFor(4242L, sym + "-EQ", decision);
        assertThat(revoked.status()).isEqualTo(NewsContext.STATUS_NO_COVERAGE);
        assertThat(revoked.evidence()).isEmpty();
        assertThat(revoked.netDirection()).isNull();
    }
}
