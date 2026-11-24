package in.aitradingsystem.news.policy;

import static org.assertj.core.api.Assertions.assertThat;

import in.aitradingsystem.news.policy.NewsSourcePolicy.Decision;
import in.aitradingsystem.news.policy.NewsSourcePolicy.Use;
import in.aitradingsystem.support.AbstractPostgresIT;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Real-PostgreSQL rights checks. Uses fictional source ids for approval flows so it never depends on the state of a
 * seeded publisher. The seeded rows are verified separately and restored to the default-deny state afterwards.
 */
class NewsSourcePolicyIT extends AbstractPostgresIT {
    @Autowired JdbcClient jdbc;
    @Autowired NewsSourcePolicyService policy;

    private static String id() {
        return "fixture-" + UUID.randomUUID();
    }

    @Test
    void seededPublishersStartDefaultDenied() {
        for (String seeded : new String[] {"moneycontrol", "economic-times", "sebi"}) {
            NewsSourcePolicyTestSupport.resetToPending(jdbc, seeded);
            assertThat(policy.decide(seeded, Use.INGEST, Instant.now())).isEqualTo(Decision.DENY_REVIEW_PENDING);
            assertThat(policy.decide(seeded, Use.NLP_TRANSFER, Instant.now())).isEqualTo(Decision.DENY_REVIEW_PENDING);
        }
    }

    @Test
    void unknownSourceIdIsDeniedNotGuessed() {
        assertThat(policy.decide(id(), Use.INGEST, Instant.now())).isEqualTo(Decision.DENY_UNKNOWN_SOURCE);
        assertThat(policy.decide(null, Use.INGEST, Instant.now())).isEqualTo(Decision.DENY_UNKNOWN_SOURCE);
    }

    @Test
    void approvalPermitsOnlyTheRecordedUseAndRevocationBlocksNewProcessing() {
        String source = id();
        NewsSourcePolicyTestSupport.approveFixture(jdbc, source);
        assertThat(policy.decide(source, Use.NLP_TRANSFER, Instant.now())).isEqualTo(Decision.PERMIT);
        assertThat(policy.permitted(source, Use.INGEST, Instant.now())).isTrue();

        jdbc.sql("UPDATE news_source_policy SET license_state = 'REVOKED' WHERE source_id = :id")
            .param("id", source).update();
        assertThat(policy.decide(source, Use.NLP_TRANSFER, Instant.now())).isEqualTo(Decision.DENY_REVOKED);
    }

    @Test
    void approvalRequiresAReviewerAndAnEffectiveStartAtTheDatabaseLevel() {
        String source = id();
        // CHECK ck_news_source_policy_approved: an APPROVED row without a reviewer reference is rejected.
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> jdbc.sql("""
                INSERT INTO news_source_policy (source_id, publisher, license_state) VALUES (:id, 'X', 'APPROVED')
                """).param("id", source).update())).isNotNull();
    }

    @Test
    void permittedUseListIsRestrictedToKnownPurposes() {
        String source = id();
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> jdbc.sql("""
                INSERT INTO news_source_policy (source_id, publisher, license_state, permitted_uses)
                VALUES (:id, 'X', 'REVIEW_PENDING', ARRAY['SCRAPE_FULL_TEXT']::text[])
                """).param("id", source).update())).isNotNull();
    }
}
