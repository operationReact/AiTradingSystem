package in.aitradingsystem.provider.upstox;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Read-only, provider-specific observations from the actual V77–V79 PostgreSQL
 * schema. This is not a downloader, a historical-coverage denominator, a
 * licence verifier, a MintTick Mind model input, or PAPER execution readiness.
 *
 * Even an attested RAW_RETENTION receipt does not grant model-training,
 * model-inference or redistribution permissions.
 */
@Repository
public class UpstoxPersistedIngestionReceipts {
    private final JdbcTemplate jdbc;

    public UpstoxPersistedIngestionReceipts(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Snapshot(
            String evidenceState,
            Long jobs,
            Long chunks,
            Long attempts,
            Long archivedVerifiedChunks,
            Long attestedRetentionChunks,
            Long archivedVerifiedRecords,
            Long attestedRetentionRecords,
            Long failedChunks,
            Long retryChunks,
            Instant lastCheckpointAt,
            Instant cooldownUntil) {}

    /**
     * A missing registered provider yields null counters (unobserved), never
     * an invented zero. A registered provider with no jobs has real zeroes.
     * One chunk contributes at most once even with multiple rights receipts.
     */
    public Snapshot snapshot(Instant at) {
        if (at == null) throw new IllegalArgumentException("Observation time required");
        OffsetDateTime now = OffsetDateTime.ofInstant(at, ZoneOffset.UTC);
        return jdbc.query("""
            SELECT p.provider_id,
                   count(DISTINCT j.job_id) AS jobs,
                   count(c.chunk_id) AS chunks,
                   coalesce(sum(c.attempts), 0) AS attempts,
                   count(c.chunk_id) FILTER (WHERE c.status = 'VERIFIED')
                     AS verified_chunks,
                   count(c.chunk_id) FILTER (
                     WHERE c.status = 'VERIFIED'
                       AND rights.verified_use = true) AS retention_chunks,
                   coalesce(sum(c.valid_records) FILTER (
                     WHERE c.status = 'VERIFIED'), 0) AS verified_records,
                   coalesce(sum(c.valid_records) FILTER (
                     WHERE c.status = 'VERIFIED'
                       AND rights.verified_use = true), 0) AS retention_records,
                   count(c.chunk_id) FILTER (WHERE c.status = 'FAILED')
                     AS failed_chunks,
                   count(c.chunk_id) FILTER (WHERE c.status = 'RETRY')
                     AS retry_chunks,
                   max(c.updated_at) AS last_checkpoint_at,
                   max(cooldown.retry_after) AS cooldown_until
              FROM golden_data_provider p
              LEFT JOIN golden_dataset_backfill_job j ON j.provider_id = p.provider_id
              LEFT JOIN golden_dataset_backfill_chunk c ON c.job_id = j.job_id
              LEFT JOIN LATERAL (
                  SELECT true AS verified_use
                    FROM golden_source_rights_receipt r
                   WHERE r.provider_id = p.provider_id
                     AND r.source_domain = j.source_domain
                     AND r.permitted_use = 'RAW_RETENTION'
                     AND r.receipt_status = 'VERIFIED'
                     AND r.verified_at IS NOT NULL
                     AND r.revoked_at IS NULL
                     AND r.valid_from <= ?
                     AND (r.valid_to IS NULL OR r.valid_to > ?)
                   LIMIT 1
              ) rights ON true
              LEFT JOIN golden_dataset_provider_cooldown cooldown
                ON cooldown.provider_id = p.provider_id
             WHERE p.provider_code = 'UPSTOX'
             GROUP BY p.provider_id
            """, rs -> rs.next() ? map(rs)
                    : new Snapshot("NO_PROVIDER_RECEIPT", null, null, null, null,
                        null, null, null, null, null, null, null), now, now);
    }

    static Snapshot map(ResultSet rs) throws SQLException {
        return new Snapshot("PERSISTED_CHECKPOINTS_ONLY",
                rs.getLong("jobs"),
                rs.getLong("chunks"),
                rs.getLong("attempts"),
                rs.getLong("verified_chunks"),
                rs.getLong("retention_chunks"),
                rs.getLong("verified_records"),
                rs.getLong("retention_records"),
                rs.getLong("failed_chunks"),
                rs.getLong("retry_chunks"),
                instant(rs, "last_checkpoint_at"),
                instant(rs, "cooldown_until"));
    }

    private static Instant instant(ResultSet rs, String name) throws SQLException {
        OffsetDateTime value = rs.getObject(name, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
