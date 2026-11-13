package in.aitradingsystem.provider.upstox;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Read-only, provider-scoped PostgreSQL ingestion receipts. These are durable
 * checkpoint observations, NOT proof of an active downloader, licensed data
 * use, model readiness, or market quotes. No account data is queried.
 */
@Repository
public class UpstoxPersistedIngestionReceipts {
    private final JdbcTemplate jdbc;

    public UpstoxPersistedIngestionReceipts(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Snapshot(String evidenceState, Long jobs, Long chunks,
                           Long attempts, Long verifiedChunks, Long verifiedRecords,
                           Long failedChunks, Long retryChunks,
                           Instant lastCheckpointAt, Instant cooldownUntil) {}

    /**
     * Null counters mean that the UPSTOX provider has not been registered.
     * Registered provider with zero jobs is an observed zero, not a successful
     * historical ingestion run. Verified records count only verified chunks.
     */
    public Snapshot snapshot() {
        return jdbc.queryForObject("""
            SELECT p.provider_id,
                   (SELECT count(*) FROM golden_dataset_backfill_job j
                     WHERE j.provider_id = p.provider_id) AS jobs,
                   (SELECT count(*) FROM golden_dataset_backfill_chunk c
                     JOIN golden_dataset_backfill_job j ON j.job_id = c.job_id
                     WHERE j.provider_id = p.provider_id) AS chunks,
                   (SELECT coalesce(sum(c.attempts), 0)
                      FROM golden_dataset_backfill_chunk c
                      JOIN golden_dataset_backfill_job j ON j.job_id = c.job_id
                     WHERE j.provider_id = p.provider_id) AS attempts,
                   (SELECT count(*) FROM golden_dataset_backfill_chunk c
                      JOIN golden_dataset_backfill_job j ON j.job_id = c.job_id
                     WHERE j.provider_id = p.provider_id AND c.status = 'VERIFIED') AS verified_chunks,
                   (SELECT coalesce(sum(c.valid_records), 0)
                      FROM golden_dataset_backfill_chunk c
                      JOIN golden_dataset_backfill_job j ON j.job_id = c.job_id
                     WHERE j.provider_id = p.provider_id AND c.status = 'VERIFIED') AS verified_records,
                   (SELECT count(*) FROM golden_dataset_backfill_chunk c
                      JOIN golden_dataset_backfill_job j ON j.job_id = c.job_id
                     WHERE j.provider_id = p.provider_id AND c.status = 'FAILED') AS failed_chunks,
                   (SELECT count(*) FROM golden_dataset_backfill_chunk c
                      JOIN golden_dataset_backfill_job j ON j.job_id = c.job_id
                     WHERE j.provider_id = p.provider_id AND c.status = 'RETRY') AS retry_chunks,
                   (SELECT max(c.updated_at) FROM golden_dataset_backfill_chunk c
                      JOIN golden_dataset_backfill_job j ON j.job_id = c.job_id
                     WHERE j.provider_id = p.provider_id) AS last_checkpoint_at,
                   (SELECT retry_after FROM golden_dataset_provider_cooldown
                     WHERE provider_id = p.provider_id) AS cooldown_until
              FROM (SELECT provider_id FROM golden_data_provider
                     WHERE provider_code = 'UPSTOX') p
            """, rs -> rs.next() ? map(rs) :
                new Snapshot("NO_PROVIDER_RECEIPT", null, null, null, null,
                             null, null, null, null, null));
    }

    static Snapshot map(ResultSet rs) throws SQLException {
        return new Snapshot("PERSISTED_CHECKPOINTS_ONLY",
                rs.getLong("jobs"), rs.getLong("chunks"),
                rs.getLong("attempts"), rs.getLong("verified_chunks"),
                rs.getLong("verified_records"), rs.getLong("failed_chunks"),
                rs.getLong("retry_chunks"), instant(rs, "last_checkpoint_at"),
                instant(rs, "cooldown_until"));
    }

    private static Instant instant(ResultSet rs, String field) throws SQLException {
        OffsetDateTime value = rs.getObject(field, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
