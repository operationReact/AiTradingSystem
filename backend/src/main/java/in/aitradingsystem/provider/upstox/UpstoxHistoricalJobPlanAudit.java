package in.aitradingsystem.provider.upstox;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Read-only audit of the *current registered backfill job plans* for UPSTOX.
 * Unlike source-probe samples, this uses only stored PostgreSQL V78/V79 rows.
 *
 * A denominator is reported only if all selected (latest revision) job plans
 * have at least one chunk and their chunk ranges exactly, contiguously cover
 * the parent job window. Overlapping jobs within a listing/source/interval
 * fail closed. This is not a market-calendar, all-NSE, archive-object-readback,
 * legal-entitlement, model-training or PAPER-readiness certificate.
 *
 * Never award top-level acceptance credit based solely on this audit.
 */
@Repository
public class UpstoxHistoricalJobPlanAudit {
    private static final int MAX_JOBS = 100_000;
    private final JdbcTemplate jdbc;

    public UpstoxHistoricalJobPlanAudit(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Snapshot(
            String evidenceState,
            Long selectedJobs,
            Long plannedChunks,
            Long receiptVerifiedChunks,
            Long retentionEligibleChunks,
            BigDecimal retentionEligiblePercent,
            String denominatorScope) {}

    private record JobRow(
            UUID listingId,
            String sourceDomain,
            String intervalName,
            LocalDate jobFrom,
            LocalDate jobThrough,
            long plannedChunks,
            long connectedChunks,
            LocalDate firstChunk,
            LocalDate lastChunk,
            long verifiedChunks,
            long rightsVerifiedChunks) {}

    public Snapshot snapshot(Instant observedAt) {
        Objects.requireNonNull(observedAt, "observation timestamp required");
        var kinds = jdbc.queryForList(
                "SELECT source_kind FROM golden_data_provider WHERE provider_code = 'UPSTOX'",
                String.class);
        if (kinds.isEmpty()) {
            return empty("NO_PROVIDER_RECEIPT");
        }
        // Synthetic-test records must never be promoted as production evidence.
        if (!"EXTERNAL".equals(kinds.get(0))) {
            return empty("NON_EXTERNAL_PROVIDER");
        }
        var at = OffsetDateTime.ofInstant(observedAt, ZoneOffset.UTC);
        List<JobRow> plans = jdbc.query("""
            WITH latest AS (
                SELECT j.*,
                       row_number() OVER (
                         PARTITION BY j.provider_id, j.listing_id,
                           j.source_domain, j.interval_name, j.from_date, j.through_date
                         ORDER BY j.dataset_revision DESC, j.created_at DESC
                       ) AS revision_rank
                  FROM golden_dataset_backfill_job j
                  JOIN golden_data_provider p ON p.provider_id = j.provider_id
                 WHERE p.provider_code = 'UPSTOX' AND p.source_kind = 'EXTERNAL'
            ), selected AS (
                SELECT * FROM latest WHERE revision_rank = 1
            ), sequenced AS (
                SELECT j.job_id, j.listing_id, j.source_domain, j.interval_name,
                       j.from_date AS job_from, j.through_date AS job_through,
                       c.chunk_id, c.from_date AS chunk_from,
                       c.through_date AS chunk_through, c.status,
                       row_number() OVER (
                         PARTITION BY j.job_id
                         ORDER BY c.from_date NULLS LAST, c.through_date NULLS LAST,
                                  c.chunk_id
                       ) AS sequence_no,
                       lag(c.through_date) OVER (
                         PARTITION BY j.job_id
                         ORDER BY c.from_date NULLS LAST, c.through_date NULLS LAST,
                                  c.chunk_id
                       ) AS previous_through,
                       EXISTS (
                           SELECT 1 FROM golden_source_rights_receipt rights
                            WHERE rights.provider_id = j.provider_id
                              AND rights.source_domain = j.source_domain
                              AND rights.permitted_use = 'RAW_RETENTION'
                              AND rights.receipt_status = 'VERIFIED'
                              AND rights.verified_at IS NOT NULL
                              AND rights.verified_at <= ?
                              AND rights.captured_at <= ?
                              AND rights.revoked_at IS NULL
                              AND rights.valid_from <= ?
                              AND (rights.valid_to IS NULL OR rights.valid_to > ?)
                       ) AS retention_rights
                  FROM selected j
                  LEFT JOIN golden_dataset_backfill_chunk c ON c.job_id = j.job_id
            )
            SELECT listing_id, source_domain, interval_name, job_from, job_through,
                   count(chunk_id) AS planned_chunks,
                   count(chunk_id) FILTER (
                     WHERE chunk_from >= job_from AND chunk_through <= job_through
                       AND ((sequence_no = 1 AND chunk_from = job_from)
                         OR (sequence_no > 1
                              AND chunk_from = previous_through + 1))
                   ) AS connected_chunks,
                   min(chunk_from) AS first_chunk, max(chunk_through) AS last_chunk,
                   count(chunk_id) FILTER (WHERE status = 'VERIFIED')
                     AS receipt_verified,
                   count(chunk_id) FILTER (
                     WHERE status = 'VERIFIED' AND retention_rights
                   ) AS retention_eligible
              FROM sequenced
             GROUP BY job_id, listing_id, source_domain, interval_name,
                      job_from, job_through
             ORDER BY listing_id, source_domain, interval_name, job_from, job_through
             LIMIT 100001
            """, (rs, i) -> new JobRow(
                    rs.getObject("listing_id", UUID.class),
                    rs.getString("source_domain"),
                    rs.getString("interval_name"),
                    rs.getDate("job_from").toLocalDate(),
                    rs.getDate("job_through").toLocalDate(),
                    rs.getLong("planned_chunks"),
                    rs.getLong("connected_chunks"),
                    date(rs.getDate("first_chunk")),
                    date(rs.getDate("last_chunk")),
                    rs.getLong("receipt_verified"),
                    rs.getLong("retention_eligible")),
                at, at, at, at);
        if (plans.isEmpty()) return empty("NO_JOB_PLANS");
        if (plans.size() > MAX_JOBS) return empty("PLAN_TOO_LARGE");

        long planned = 0;
        long verified = 0;
        long retentionEligible = 0;
        boolean valid = true;
        Map<String, LocalDate> priorEnd = new HashMap<>();
        // Query rows are ordered but ensure disjointness independent of SQL
        // executor ordering and detect overlapping parent jobs.
        plans.sort(Comparator
                .comparing(JobRow::listingId)
                .thenComparing(JobRow::sourceDomain)
                .thenComparing(JobRow::intervalName)
                .thenComparing(JobRow::jobFrom));
        for (JobRow job : plans) {
            String group = job.listingId() + "|" + job.sourceDomain() + "|"
                    + job.intervalName();
            LocalDate previous = priorEnd.put(group, job.jobThrough());
            if (previous != null && !job.jobFrom().isAfter(previous)) valid = false;
            if (job.plannedChunks() <= 0
                    || job.connectedChunks() != job.plannedChunks()
                    || !job.jobFrom().equals(job.firstChunk())
                    || !job.jobThrough().equals(job.lastChunk())) {
                valid = false;
            }
            planned = Math.addExact(planned, job.plannedChunks());
            verified = Math.addExact(verified, job.verifiedChunks());
            retentionEligible = Math.addExact(
                    retentionEligible, job.rightsVerifiedChunks());
        }
        // Individual checkpoints and their counts remain observable even
        // when the target cannot be validated; the percent is then unknown.
        BigDecimal percent = valid && planned > 0
                ? BigDecimal.valueOf(retentionEligible)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(planned), 1, RoundingMode.DOWN)
                : null;
        return new Snapshot(valid ? "VALID_CURRENT_JOB_PLANS"
                        : "UNVERIFIED_OR_OVERLAPPING_PLAN",
                (long) plans.size(), planned, verified, retentionEligible,
                percent,
                "CURRENT_UPSTOX_BACKFILL_JOB_CHUNKS_NOT_FULL_MARKET_UNIVERSE");
    }

    private static LocalDate date(Date date) {
        return date == null ? null : date.toLocalDate();
    }

    private static Snapshot empty(String reason) {
        return new Snapshot(reason, null, null, null, null, null,
                "CURRENT_UPSTOX_BACKFILL_JOB_CHUNKS_NOT_FULL_MARKET_UNIVERSE");
    }
}
