package in.aitradingsystem.news.policy;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Test-only fixture helper. An approval written here is a FIXTURE approval for a test database; it is not a
 * rights decision for any real publisher. Each test that needs an approved source approves it in its own setup.
 */
public final class NewsSourcePolicyTestSupport {
    private NewsSourcePolicyTestSupport() {}

    /** Records a fixture approval for all four purposes, effective from one day ago, with no expiry. */
    public static void approveFixture(JdbcClient jdbc, String sourceId) {
        jdbc.sql("""
                INSERT INTO news_source_policy (source_id, publisher, license_state, permitted_uses, reviewer_ref,
                                                terms_ref, effective_from, effective_to, content_ttl_days, updated_at)
                VALUES (:id, 'FIXTURE PUBLISHER', 'APPROVED', ARRAY['INGEST','DISPLAY','NLP_TRANSFER','RETAIN'],
                        'test-fixture-reviewer', 'fixture-terms', now() - interval '1 day', NULL, 30, now())
                ON CONFLICT (source_id) DO UPDATE SET license_state = 'APPROVED',
                    permitted_uses = ARRAY['INGEST','DISPLAY','NLP_TRANSFER','RETAIN'],
                    reviewer_ref = 'test-fixture-reviewer', effective_from = now() - interval '1 day',
                    effective_to = NULL, updated_at = now()
                """)
            .param("id", sourceId).update();
    }

    /** Restores a seeded source to REVIEW_PENDING with no permitted use, the default-deny state. */
    public static void resetToPending(JdbcClient jdbc, String sourceId) {
        jdbc.sql("""
                UPDATE news_source_policy SET license_state = 'REVIEW_PENDING', permitted_uses = '{}',
                       reviewer_ref = NULL, effective_from = NULL, effective_to = NULL, updated_at = now()
                 WHERE source_id = :id
                """).param("id", sourceId).update();
    }
}
