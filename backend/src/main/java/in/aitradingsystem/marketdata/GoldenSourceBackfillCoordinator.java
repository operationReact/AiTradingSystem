package in.aitradingsystem.marketdata;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Date;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * D20-03B dormant ingestion control plane, separate from existing operational
 * historical candles, account sessions, model inference and order execution.
 *
 * No scheduler, controller, real source adapter or archive sink is registered.
 * Every request requires an explicitly supplied, reviewed provider, independent
 * validator and durable archive. An empty rights registry fails closed.
 */
@Repository
public class GoldenSourceBackfillCoordinator {
    public static final int MAX_CHUNK_DAYS = 31;
    public static final int MAX_BYTES = 32 * 1024 * 1024;
    public static final int MAX_ATTEMPTS = 5;
    private static final Duration LEASE_LENGTH = Duration.ofMinutes(5);
    private static final String HASH = "[a-f0-9]{64}";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final GoldenSourceRightsRepository rights;
    private final Clock clock;

    @Autowired
    public GoldenSourceBackfillCoordinator(
            JdbcTemplate jdbc, TransactionTemplate transactions,
            GoldenSourceRightsRepository rights) {
        this(jdbc, transactions, rights, Clock.systemUTC());
    }

    GoldenSourceBackfillCoordinator(
            JdbcTemplate jdbc, TransactionTemplate transactions,
            GoldenSourceRightsRepository rights, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = Objects.requireNonNull(transactions);
        this.rights = Objects.requireNonNull(rights);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * Idempotently plan a bounded series of chunks for an independently
     * reconciled historical listing episode. Planning DOES NOT grant rights or
     * initiate any provider request. A corrected dataset uses a new revision.
     */
    public UUID plan(String providerCode, String sourceDomain, UUID listingId,
                     String intervalName, LocalDate from, LocalDate through,
                     int revision, int chunkDays) {
        requireSource(providerCode, sourceDomain);
        if (listingId == null || intervalName == null
                || !intervalName.matches("[A-Z][A-Z0-9_]{1,23}")
                || from == null || through == null || from.isAfter(through)
                || ChronoUnit.DAYS.between(from, through) > 25L * 366
                || revision <= 0 || chunkDays < 1 || chunkDays > MAX_CHUNK_DAYS) {
            throw new IllegalArgumentException("invalid bounded historical job");
        }
        // No automatic mapping from today's provider token to an old listing.
        Long validListing = jdbc.queryForObject("""
                SELECT count(*) FROM golden_listing_identity
                 WHERE listing_id = ? AND listed_from <= ?
                   AND (delisted_on IS NULL OR delisted_on > ?)
                """, Long.class, listingId, Date.valueOf(from), Date.valueOf(through));
        if (validListing == null || validListing != 1L) {
            throw new IllegalArgumentException("historical listing episode is not verified for range");
        }
        UUID providerId = providerId(providerCode);
        return Objects.requireNonNull(transactions.execute(ignored -> {
            UUID newId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO golden_dataset_backfill_job
                        (job_id, provider_id, listing_id, source_domain, interval_name,
                         from_date, through_date, dataset_revision)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT ON CONSTRAINT uq_golden_dataset_job DO NOTHING
                    """, newId, providerId, listingId, sourceDomain, intervalName,
                    Date.valueOf(from), Date.valueOf(through), revision);
            UUID jobId = jdbc.queryForObject("""
                    SELECT job_id FROM golden_dataset_backfill_job
                     WHERE provider_id = ? AND listing_id = ? AND source_domain = ?
                       AND interval_name = ? AND from_date = ? AND through_date = ?
                       AND dataset_revision = ?
                    """, UUID.class, providerId, listingId, sourceDomain, intervalName,
                    Date.valueOf(from), Date.valueOf(through), revision);
            for (LocalDate start = from; !start.isAfter(through); ) {
                LocalDate end = start.plusDays(chunkDays - 1L);
                if (end.isAfter(through)) end = through;
                jdbc.update("""
                        INSERT INTO golden_dataset_backfill_chunk
                            (chunk_id, job_id, from_date, through_date)
                        VALUES (?, ?, ?, ?)
                        ON CONFLICT ON CONSTRAINT uq_golden_backfill_chunk_range DO NOTHING
                        """, UUID.randomUUID(), jobId, Date.valueOf(start), Date.valueOf(end));
                start = end.plusDays(1);
            }
            return jobId;
        }));
    }

    /**
     * Process at most one eligible chunk. No scheduled invocation is installed.
     * Provider failures use durable bounded retries; invalid evidence is
     * terminal and requires independent operator review/new dataset revision.
     *
     * @return false when no source-authorized, due chunk could be claimed.
     */
    public boolean processNext(
            GoldenHistoricalDatasetProvider provider,
            GoldenDatasetChunkValidator validator, GoldenChunkArchive archive) {
        Objects.requireNonNull(provider);
        Objects.requireNonNull(validator);
        Objects.requireNonNull(archive);
        requireSource(provider.providerCode(), provider.sourceDomain());
        Optional<Lease> acquired = claim(provider.providerCode(), provider.sourceDomain());
        if (acquired.isEmpty()) return false;
        Lease lease = acquired.get();
        try {
            var raw = Objects.requireNonNull(provider.fetch(lease.request()),
                    "source payload required");
            byte[] bytes = raw.bytes();
            if (bytes.length == 0 || bytes.length > MAX_BYTES
                    || !validHash(raw.sourceEvidenceDigest())) {
                quarantine(lease, "INVALID_SOURCE_PAYLOAD");
                return true;
            }
            var validated = Objects.requireNonNull(
                    validator.validate(lease.request(), raw), "validation required");
            if (!validValidation(validated, clock.instant())) {
                quarantine(lease, "INVALID_DATASET_COVERAGE");
                return true;
            }
            // Recheck at the actual time data would be persisted, never at
            // the historical simulation date. Revocation wins.
            if (!allowed(lease.providerCode(), lease.sourceDomain())) {
                quarantine(lease, "RETENTION_RIGHTS_REVOKED");
                return true;
            }
            String computedHash = sha256(bytes);
            var stored = Objects.requireNonNull(
                    archive.putImmutable(lease.request(), bytes, computedHash),
                    "durable archival receipt required");
            if (stored.objectUri() == null || stored.objectUri().isBlank()
                    || stored.objectUri().length() > 1024
                    || !computedHash.equals(stored.persistedSha256())) {
                quarantine(lease, "ARCHIVE_DIGEST_MISMATCH");
                return true;
            }
            // No metadata-only success. Check rights again before publishing
            // the checkpoint; a later legal revoke needs a separate purge path.
            if (!allowed(lease.providerCode(), lease.sourceDomain())) {
                quarantine(lease, "RETENTION_RIGHTS_REVOKED");
                return true;
            }
            checkpoint(lease, validated, raw.sourceEvidenceDigest(), computedHash,
                    stored.objectUri());
        } catch (GoldenHistoricalDatasetProvider.RetryableSourceException e) {
            retry(lease);
        } catch (RuntimeException e) {
            // Never include provider credentials, raw responses or exception
            // messages in persisted diagnostic fields.
            quarantine(lease, "UNEXPECTED_SOURCE_OR_VALIDATOR_ERROR");
        }
        return true;
    }

    private Optional<Lease> claim(String providerCode, String sourceDomain) {
        // The full provider/specific-domain/raw-retention grant is mandatory
        // even to initiate a remote request. Empty rights => zero calls.
        if (!allowed(providerCode, sourceDomain)) return Optional.empty();
        Instant now = clock.instant();
        OffsetDateTime at = utc(now);
        // Exhausted leases never run again, including after worker crashes.
        jdbc.update("""
                UPDATE golden_dataset_backfill_chunk
                   SET status = 'FAILED', lease_token = NULL, lease_until = NULL,
                       error_code = 'LEASE_ATTEMPTS_EXHAUSTED', updated_at = ?
                 WHERE status = 'LEASED' AND lease_until <= ? AND attempts >= ?
                """, at, at, MAX_ATTEMPTS);

        UUID token = UUID.randomUUID();
        OffsetDateTime until = utc(now.plus(LEASE_LENGTH));
        List<Claimed> candidates = jdbc.query("""
                WITH candidate AS (
                    SELECT c.chunk_id
                      FROM golden_dataset_backfill_chunk c
                      JOIN golden_dataset_backfill_job j ON j.job_id = c.job_id
                      JOIN golden_data_provider p ON p.provider_id = j.provider_id
                     WHERE p.provider_code = ? AND p.source_kind = 'EXTERNAL'
                       AND j.source_domain = ?
                       AND j.status IN ('PENDING', 'RUNNING')
                       AND c.attempts < ?
                       AND NOT EXISTS (
                           SELECT 1 FROM golden_dataset_provider_cooldown cd
                            WHERE cd.provider_id = j.provider_id
                              AND cd.retry_after > ?
                       )
                       AND (
                           (c.status IN ('PENDING', 'RETRY') AND c.next_attempt_at <= ?)
                           OR (c.status = 'LEASED' AND c.lease_until <= ?)
                       )
                     ORDER BY j.created_at, c.from_date, c.chunk_id
                     FOR UPDATE OF c SKIP LOCKED
                     LIMIT 1
                )
                UPDATE golden_dataset_backfill_chunk c
                   SET status = 'LEASED', attempts = c.attempts + 1,
                       lease_token = ?, lease_until = ?, error_code = NULL,
                       updated_at = ?
                  FROM candidate
                 WHERE c.chunk_id = candidate.chunk_id
                RETURNING c.chunk_id, c.job_id, c.from_date, c.through_date, c.attempts
                """, (rs, ignored) -> new Claimed(
                        rs.getObject("chunk_id", UUID.class),
                        rs.getObject("job_id", UUID.class),
                        rs.getDate("from_date").toLocalDate(),
                        rs.getDate("through_date").toLocalDate(),
                        rs.getInt("attempts")),
                providerCode, sourceDomain, MAX_ATTEMPTS, at, at, at,
                token, until, at);
        if (candidates.isEmpty()) return Optional.empty();
        Claimed c = candidates.get(0);
        var parent = jdbc.queryForObject("""
                SELECT listing_id, interval_name FROM golden_dataset_backfill_job
                 WHERE job_id = ?
                """, (rs, ignored) -> new JobScope(
                        rs.getObject("listing_id", UUID.class),
                        rs.getString("interval_name")), c.jobId());
        if (parent == null) throw new IllegalStateException("missing backfill job");
        jdbc.update("""
                UPDATE golden_dataset_backfill_job SET status = 'RUNNING',
                       updated_at = ? WHERE job_id = ? AND status = 'PENDING'
                """, at, c.jobId());
        return Optional.of(new Lease(providerCode, sourceDomain, c,
                new GoldenHistoricalDatasetProvider.ChunkRequest(
                        c.jobId(), c.chunkId(), parent.listingId(),
                        parent.intervalName(), c.from(), c.through(), MAX_BYTES),
                token, until.toInstant()));
    }

    private void checkpoint(Lease lease, GoldenDatasetChunkValidator.ValidatedChunk validation,
                            String evidence, String hash, String archiveUri) {
        Instant now = clock.instant();
        String state = validation.expectedRecords() > 0
                && validation.validRecords() == validation.expectedRecords()
                ? "VERIFIED" : "INCOMPLETE";
        transactions.executeWithoutResult(ignored -> {
            int updated = jdbc.update("""
                    UPDATE golden_dataset_backfill_chunk
                       SET status = ?, lease_token = NULL, lease_until = NULL,
                           expected_records = ?, valid_records = ?, archive_uri = ?,
                           dataset_digest = ?, source_evidence_digest = ?,
                           first_available_at = ?, last_available_at = ?,
                           error_code = NULL, updated_at = ?
                     WHERE chunk_id = ? AND status = 'LEASED' AND lease_token = ?
                       AND lease_until > ?
                    """, state, validation.expectedRecords(), validation.validRecords(),
                    archiveUri, hash, evidence,
                    validation.firstAvailableAt() == null ? null : utc(validation.firstAvailableAt()),
                    validation.lastAvailableAt() == null ? null : utc(validation.lastAvailableAt()),
                    utc(now), lease.claimed().chunkId(), lease.token(), utc(now));
            if (updated != 1) return; // Expired lease: an old worker cannot acknowledge.
            if (validation.validRecords() > 0) {
                jdbc.update("""
                        INSERT INTO golden_source_coverage_evidence
                            (coverage_id, provider_id, source_domain, scope_id,
                             dataset_digest, interval_name, from_date, through_date,
                             expected_records, valid_records, first_available_at,
                             last_available_at, quality_status, source_evidence_digest,
                             recorded_at)
                        SELECT ?, j.provider_id, j.source_domain, ?, ?,
                               j.interval_name, ?, ?, ?, ?, ?, ?, ?, ?, ?
                          FROM golden_dataset_backfill_job j
                         WHERE j.job_id = ?
                        ON CONFLICT ON CONSTRAINT uq_golden_source_coverage_revision DO NOTHING
                        """, UUID.randomUUID(), "LISTING:" + lease.request().listingId(),
                        hash, Date.valueOf(lease.claimed().from()),
                        Date.valueOf(lease.claimed().through()),
                        validation.expectedRecords(), validation.validRecords(),
                        utc(validation.firstAvailableAt()), utc(validation.lastAvailableAt()),
                        state, evidence, utc(now), lease.claimed().jobId());
            }
            // A successful provider response clears vendor-level transient
            // cooldown, even if a genuine session gap remains incomplete.
            jdbc.update("""
                    DELETE FROM golden_dataset_provider_cooldown
                     WHERE provider_id = (SELECT provider_id
                                            FROM golden_dataset_backfill_job
                                           WHERE job_id = ?)
                    """, lease.claimed().jobId());
            refreshJob(lease.claimed().jobId(), now);
        });
    }

    private void retry(Lease lease) {
        Instant now = clock.instant();
        long backoffSeconds = Math.min(1800L, 30L <<
                Math.min(6, Math.max(0, lease.claimed().attempts() - 1)));
        // Stable bounded jitter: avoid simultaneous retries without relying
        // on a nondeterministic global clock/random number source.
        backoffSeconds += Math.floorMod(lease.claimed().chunkId().hashCode(), 17);
        final long seconds = backoffSeconds;
        transactions.executeWithoutResult(ignored -> {
            int changed = jdbc.update("""
                    UPDATE golden_dataset_backfill_chunk
                       SET status = CASE WHEN attempts >= ? THEN 'FAILED' ELSE 'RETRY' END,
                           lease_token = NULL, lease_until = NULL, next_attempt_at = ?,
                           error_code = 'TRANSIENT_SOURCE_ERROR', updated_at = ?
                     WHERE chunk_id = ? AND status = 'LEASED' AND lease_token = ?
                       AND lease_until > ?
                    """, MAX_ATTEMPTS, utc(now.plusSeconds(seconds)), utc(now),
                    lease.claimed().chunkId(), lease.token(), utc(now));
            if (changed != 1) return;
            jdbc.update("""
                    INSERT INTO golden_dataset_provider_cooldown
                        (provider_id, consecutive_failures, retry_after, updated_at)
                    SELECT provider_id, 1, ?, ? FROM golden_dataset_backfill_job
                     WHERE job_id = ?
                    ON CONFLICT (provider_id) DO UPDATE SET
                        consecutive_failures = LEAST(8,
                            golden_dataset_provider_cooldown.consecutive_failures + 1),
                        retry_after = GREATEST(
                            golden_dataset_provider_cooldown.retry_after,
                            EXCLUDED.retry_after),
                        updated_at = EXCLUDED.updated_at
                    """, utc(now.plusSeconds(seconds)), utc(now), lease.claimed().jobId());
            refreshJob(lease.claimed().jobId(), now);
        });
    }

    private void quarantine(Lease lease, String errorCode) {
        Instant now = clock.instant();
        transactions.executeWithoutResult(ignored -> {
            int changed = jdbc.update("""
                    UPDATE golden_dataset_backfill_chunk
                       SET status = 'QUARANTINED', lease_token = NULL,
                           lease_until = NULL, error_code = ?, updated_at = ?
                     WHERE chunk_id = ? AND status = 'LEASED' AND lease_token = ?
                       AND lease_until > ?
                    """, errorCode, utc(now), lease.claimed().chunkId(),
                    lease.token(), utc(now));
            if (changed == 1) refreshJob(lease.claimed().jobId(), now);
        });
    }

    private void refreshJob(UUID jobId, Instant now) {
        jdbc.update("""
                UPDATE golden_dataset_backfill_job j
                   SET status = CASE
                       WHEN NOT EXISTS (
                           SELECT 1 FROM golden_dataset_backfill_chunk c
                            WHERE c.job_id = j.job_id AND c.status <> 'VERIFIED'
                       ) THEN 'COMPLETE'
                       WHEN NOT EXISTS (
                           SELECT 1 FROM golden_dataset_backfill_chunk c
                            WHERE c.job_id = j.job_id
                              AND c.status IN ('PENDING', 'RETRY', 'LEASED')
                       ) THEN 'BLOCKED'
                       ELSE 'RUNNING' END,
                       updated_at = ?
                 WHERE job_id = ?
                """, utc(now), jobId);
    }

    /** Durable per-source, per-listing progress; not an eligibility declaration. */
    public Progress progress(UUID jobId) {
        if (jobId == null) throw new IllegalArgumentException("job ID required");
        return jdbc.queryForObject("""
                SELECT j.status, count(c.chunk_id) AS chunks,
                       count(c.chunk_id) FILTER (WHERE c.status = 'VERIFIED') AS verified,
                       count(c.chunk_id) FILTER (WHERE c.status = 'INCOMPLETE') AS incomplete,
                       count(c.chunk_id) FILTER (WHERE c.status = 'QUARANTINED') AS quarantined,
                       count(c.chunk_id) FILTER (WHERE c.status = 'FAILED') AS failed,
                       coalesce(sum(c.expected_records), 0) AS expected,
                       coalesce(sum(c.valid_records), 0) AS valid
                  FROM golden_dataset_backfill_job j
                  JOIN golden_dataset_backfill_chunk c ON c.job_id = j.job_id
                 WHERE j.job_id = ?
                 GROUP BY j.status
                """, (rs, ignored) -> new Progress(
                        rs.getString("status"), rs.getLong("chunks"),
                        rs.getLong("verified"), rs.getLong("incomplete"),
                        rs.getLong("quarantined"), rs.getLong("failed"),
                        rs.getLong("expected"), rs.getLong("valid")), jobId);
    }

    public record Progress(String status, long chunks, long verified,
                           long incomplete, long quarantined, long failed,
                           long expectedRecords, long validRecords) {}
    private record Claimed(UUID chunkId, UUID jobId, LocalDate from,
                           LocalDate through, int attempts) {}
    private record JobScope(UUID listingId, String intervalName) {}
    private record Lease(String providerCode, String sourceDomain, Claimed claimed,
                         GoldenHistoricalDatasetProvider.ChunkRequest request,
                         UUID token, Instant leaseUntil) {}

    private boolean allowed(String provider, String domain) {
        return rights.isPermitted(provider, domain,
                GoldenSourceRightsRepository.Use.RAW_RETENTION, clock.instant());
    }

    private UUID providerId(String providerCode) {
        List<UUID> ids = jdbc.query("""
                SELECT provider_id FROM golden_data_provider
                 WHERE provider_code = ? AND source_kind = 'EXTERNAL'
                """, (rs, ignored) -> rs.getObject("provider_id", UUID.class), providerCode);
        if (ids.size() != 1) throw new IllegalArgumentException(
                "externally registered provider required");
        return ids.get(0);
    }

    private static void requireSource(String provider, String domain) {
        if (provider == null || !provider.matches("[A-Z0-9_]{3,80}")
                || domain == null || !domain.matches("[a-z][a-z0-9_]{2,79}")) {
            throw new IllegalArgumentException("registered provider and source domain required");
        }
    }

    private static boolean validHash(String digest) {
        return digest != null && digest.matches(HASH);
    }

    private static boolean validValidation(
            GoldenDatasetChunkValidator.ValidatedChunk v, Instant now) {
        if (v.expectedRecords() < 0 || v.expectedRecords() > 1_000_000
                || v.validRecords() < 0 || v.validRecords() > v.expectedRecords()) return false;
        if (v.validRecords() == 0) {
            return v.firstAvailableAt() == null && v.lastAvailableAt() == null;
        }
        return v.firstAvailableAt() != null && v.lastAvailableAt() != null
                && !v.firstAvailableAt().isAfter(v.lastAvailableAt())
                && !v.lastAvailableAt().isAfter(now);
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static String sha256(byte[] raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 required by Java", impossible);
        }
    }
}
