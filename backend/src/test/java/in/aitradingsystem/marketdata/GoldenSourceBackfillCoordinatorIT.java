package in.aitradingsystem.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.aitradingsystem.support.AbstractPostgresIT;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** All sources, rights, bytes and archive receipts are synthetic test fixtures. */
class GoldenSourceBackfillCoordinatorIT extends AbstractPostgresIT {
    private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");
    private static final LocalDate FROM = LocalDate.parse("2026-10-01");
    private static final String HASH = "a".repeat(64);
    private static final String SOURCE = "daily_bars";

    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired GoldenSourceRightsRepository rights;

    @Test
    void idempotentPlanCannotFetchWithoutExplicitRawRetentionRights() {
        Fixture f = fixture();
        GoldenSourceBackfillCoordinator c = coordinator(Duration.ZERO);
        UUID job = c.plan(f.provider(), SOURCE, f.listing(), "ONE_DAY",
                FROM, FROM.plusDays(4), 1, 2);
        assertThat(c.plan(f.provider(), SOURCE, f.listing(), "ONE_DAY",
                FROM, FROM.plusDays(4), 1, 2)).isEqualTo(job);
        assertThat(c.progress(job).chunks()).isEqualTo(3);
        AtomicInteger calls = new AtomicInteger();
        assertThat(c.processNext(source(f.provider(), calls, false), validator(2, 2),
                archive(false))).isFalse();
        assertThat(calls).hasValue(0);
        assertThat(c.progress(job).verified()).isZero();
        assertThat(c.progress(job).status()).isEqualTo("PENDING");
        assertThat(c.progress(job).expectedRecords()).isZero();
    }

    @Test
    void licensedFixtureAdvancesAllDurableChunksAndCoverageWithoutDuplicates() {
        Fixture f = fixture();
        grant(f, "RAW_RETENTION");
        GoldenSourceBackfillCoordinator c = coordinator(Duration.ZERO);
        UUID job = c.plan(f.provider(), SOURCE, f.listing(), "ONE_DAY",
                FROM, FROM.plusDays(4), 1, 2);
        AtomicInteger calls = new AtomicInteger();
        while (c.processNext(source(f.provider(), calls, false), validator(2, 2),
                archive(false))) {
            assertThat(calls.get()).isLessThanOrEqualTo(3);
        }
        var progress = c.progress(job);
        assertThat(progress.status()).isEqualTo("COMPLETE");
        assertThat(progress.chunks()).isEqualTo(3);
        assertThat(progress.verified()).isEqualTo(3);
        assertThat(progress.expectedRecords()).isEqualTo(6);
        assertThat(progress.validRecords()).isEqualTo(6);
        assertThat(calls).hasValue(3);
        assertThat(coverageCount(f)).isEqualTo(3L);
        assertThat(c.processNext(source(f.provider(), calls, false), validator(2, 2),
                archive(false))).isFalse();
        assertThat(calls).hasValue(3);
    }

    @Test
    void incompleteCalendarCoverageNeverBecomesVerifiedOrComplete() {
        Fixture f = fixture();
        grant(f, "RAW_RETENTION");
        GoldenSourceBackfillCoordinator c = coordinator(Duration.ZERO);
        UUID job = c.plan(f.provider(), SOURCE, f.listing(), "ONE_DAY",
                FROM, FROM, 1, 1);
        assertThat(c.processNext(source(f.provider(), new AtomicInteger(), false),
                validator(3, 2), archive(false))).isTrue();
        var progress = c.progress(job);
        assertThat(progress.status()).isEqualTo("BLOCKED");
        assertThat(progress.incomplete()).isEqualTo(1);
        assertThat(progress.verified()).isZero();
        assertThat(progress.expectedRecords()).isEqualTo(3);
        assertThat(progress.validRecords()).isEqualTo(2);
        String quality = jdbc.queryForObject("""
                SELECT quality_status FROM golden_source_coverage_evidence
                 WHERE provider_id = ? AND dataset_digest = ?
                   AND from_date = ? AND scope_id = ?
                """, String.class, f.providerId(), shaOfPayload(),
                java.sql.Date.valueOf(FROM), "LISTING:" + f.listing());
        assertThat(quality).isEqualTo("INCOMPLETE");
    }

    @Test
    void transientOutageDoesNotStormProviderAndCanResumeAfterBackoff() {
        Fixture f = fixture();
        grant(f, "RAW_RETENTION");
        GoldenSourceBackfillCoordinator c = coordinator(Duration.ZERO);
        UUID job = c.plan(f.provider(), SOURCE, f.listing(), "ONE_DAY",
                FROM, FROM, 1, 1);
        AtomicInteger calls = new AtomicInteger();
        assertThat(c.processNext(source(f.provider(), calls, true), validator(1, 1),
                archive(false))).isTrue();
        assertThat(c.processNext(source(f.provider(), calls, false), validator(1, 1),
                archive(false))).isFalse();
        assertThat(calls).hasValue(1);
        assertThat(c.progress(job).status()).isEqualTo("RUNNING");
        GoldenSourceBackfillCoordinator resumed = coordinator(Duration.ofMinutes(3));
        assertThat(resumed.processNext(source(f.provider(), calls, false),
                validator(1, 1), archive(false))).isTrue();
        assertThat(calls).hasValue(2);
        assertThat(resumed.progress(job).status()).isEqualTo("COMPLETE");
    }

    @Test
    void expiredLeaseRecoversFromCrashWithoutTrustingFormerWorker() {
        Fixture f = fixture();
        grant(f, "RAW_RETENTION");
        GoldenSourceBackfillCoordinator c = coordinator(Duration.ZERO);
        UUID job = c.plan(f.provider(), SOURCE, f.listing(), "ONE_DAY",
                FROM, FROM, 1, 1);
        jdbc.update("""
                UPDATE golden_dataset_backfill_chunk
                   SET status = 'LEASED', attempts = 1,
                       lease_token = ?, lease_until = ?
                 WHERE job_id = ?
                """, UUID.randomUUID(), at(NOW.minusSeconds(30)), job);
        assertThat(c.processNext(source(f.provider(), new AtomicInteger(), false),
                validator(1, 1), archive(false))).isTrue();
        assertThat(c.progress(job).status()).isEqualTo("COMPLETE");
        Integer attempts = jdbc.queryForObject(
                "SELECT attempts FROM golden_dataset_backfill_chunk WHERE job_id = ?",
                Integer.class, job);
        assertThat(attempts).isEqualTo(2);
    }

    @Test
    void mismatchedArchiveDigestAndInvalidValidationQuarantineRatherThanPublish() {
        Fixture f = fixture();
        grant(f, "RAW_RETENTION");
        GoldenSourceBackfillCoordinator c = coordinator(Duration.ZERO);
        UUID job = c.plan(f.provider(), SOURCE, f.listing(), "ONE_DAY",
                FROM, FROM, 1, 1);
        assertThat(c.processNext(source(f.provider(), new AtomicInteger(), false),
                validator(1, 1), archive(true))).isTrue();
        assertThat(c.progress(job).quarantined()).isEqualTo(1);
        assertThat(c.progress(job).verified()).isZero();
        assertThat(coverageCount(f)).isZero();

        UUID other = c.plan(f.provider(), SOURCE, f.listing(), "ONE_DAY",
                FROM.plusDays(1), FROM.plusDays(1), 2, 1);
        assertThat(c.processNext(source(f.provider(), new AtomicInteger(), false),
                validator(1, 2), archive(false))).isTrue();
        assertThat(c.progress(other).quarantined()).isEqualTo(1);
        assertThat(coverageCount(f)).isZero();
    }

    @Test
    void revocationOverridesPriorGrantAndStopsFurtherSourceCalls() {
        Fixture f = fixture();
        grant(f, "RAW_RETENTION");
        GoldenSourceBackfillCoordinator c = coordinator(Duration.ZERO);
        UUID job = c.plan(f.provider(), SOURCE, f.listing(), "ONE_DAY",
                FROM, FROM, 1, 1);
        jdbc.update("""
                INSERT INTO golden_source_rights_receipt
                    (receipt_id, provider_id, source_domain, permitted_use,
                     receipt_status, terms_digest, valid_from, revoked_at,
                     source_reference, captured_at)
                VALUES (?, ?, 'daily_bars', 'RAW_RETENTION', 'REVOKED',
                        ?, ?, ?, 'synthetic revocation', ?)
                """, UUID.randomUUID(), f.providerId(), HASH,
                at(Instant.parse("2025-01-01T00:00:00Z")),
                at(Instant.parse("2026-10-15T00:00:00Z")),
                at(Instant.parse("2026-10-16T00:00:00Z")));
        AtomicInteger calls = new AtomicInteger();
        assertThat(c.processNext(source(f.provider(), calls, false), validator(1, 1),
                archive(false))).isFalse();
        assertThat(calls).hasValue(0);
        assertThat(c.progress(job).verified()).isZero();
    }

    @Test
    void planningRejectsDatesOutsideListingEpisodeAndOversizedWindows() {
        Fixture f = fixture();
        GoldenSourceBackfillCoordinator c = coordinator(Duration.ZERO);
        assertThatThrownBy(() -> c.plan(f.provider(), SOURCE, f.listing(), "ONE_DAY",
                LocalDate.parse("1999-01-01"), FROM, 1, 31))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> c.plan(f.provider(), SOURCE, f.listing(), "ONE_DAY",
                FROM, FROM.plusDays(50), 1, 32))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private GoldenSourceBackfillCoordinator coordinator(Duration forward) {
        return new GoldenSourceBackfillCoordinator(jdbc, tx, rights,
                Clock.offset(Clock.fixed(NOW, ZoneOffset.UTC), forward));
    }

    private Fixture fixture() {
        UUID providerId = UUID.randomUUID();
        String provider = "TEST_" + UUID.randomUUID().toString()
                .replace("-", "").toUpperCase();
        UUID security = UUID.randomUUID();
        UUID listing = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO golden_data_provider
                    (provider_id, provider_code, legal_name, source_kind)
                VALUES (?, ?, 'Synthetic licensed fixture only', 'EXTERNAL')
                """, providerId, provider);
        jdbc.update("""
                INSERT INTO golden_security_identity
                    (security_id, issuer_name, identity_source, identity_evidence_digest)
                VALUES (?, 'Synthetic issuer', 'integration test', ?)
                """, security, HASH);
        jdbc.update("""
                INSERT INTO golden_listing_identity
                    (listing_id, security_id, exchange, listing_series,
                     listed_from, identity_evidence_digest)
                VALUES (?, ?, 'NSE', 'EQ', DATE '2000-01-01', ?)
                """, listing, security, HASH);
        return new Fixture(provider, providerId, listing);
    }

    private void grant(Fixture f, String use) {
        jdbc.update("""
                INSERT INTO golden_source_rights_receipt
                    (receipt_id, provider_id, source_domain, permitted_use,
                     receipt_status, terms_digest, valid_from, valid_to,
                     verified_at, verified_by, source_reference, captured_at)
                VALUES (?, ?, 'daily_bars', ?, 'VERIFIED', ?, ?, ?, ?,
                        'TEST_VERIFIER_ONLY', 'synthetic fixture, not an agreement', ?)
                """, UUID.randomUUID(), f.providerId(), use, HASH,
                at(Instant.parse("2025-01-01T00:00:00Z")),
                at(Instant.parse("2028-01-01T00:00:00Z")),
                at(Instant.parse("2026-01-01T00:00:00Z")),
                at(Instant.parse("2026-01-02T00:00:00Z")));
    }

    private GoldenHistoricalDatasetProvider source(String provider,
                                                    AtomicInteger calls,
                                                    boolean failFirst) {
        return new GoldenHistoricalDatasetProvider() {
            @Override public String providerCode() { return provider; }
            @Override public String sourceDomain() { return SOURCE; }
            @Override public SourcePayload fetch(ChunkRequest request) {
                int count = calls.incrementAndGet();
                if (failFirst && count == 1)
                    throw new RetryableSourceException("SOURCE_TEMPORARILY_UNAVAILABLE");
                return new SourcePayload("fixture-candles".getBytes(StandardCharsets.UTF_8),
                        HASH);
            }
        };
    }

    private GoldenDatasetChunkValidator validator(long expected, long valid) {
        return (request, raw) -> new GoldenDatasetChunkValidator.ValidatedChunk(
                expected, valid, valid == 0 ? null
                        : Instant.parse("2026-10-01T10:00:00Z"),
                valid == 0 ? null : Instant.parse("2026-10-05T10:00:00Z"));
    }

    private GoldenChunkArchive archive(boolean mismatch) {
        return (request, bytes, digest) -> new GoldenChunkArchive.ArchiveReceipt(
                "fixture://immutable/" + request.chunkId(),
                mismatch ? "f".repeat(64) : digest);
    }

    private long coverageCount(Fixture f) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM golden_source_coverage_evidence WHERE provider_id = ?",
                Long.class, f.providerId());
    }

    private static String shaOfPayload() {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(
                            "fixture-candles".getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new AssertionError(ex);
        }
    }

    private static OffsetDateTime at(Instant time) {
        return OffsetDateTime.ofInstant(time, ZoneOffset.UTC);
    }

    private record Fixture(String provider, UUID providerId, UUID listing) {}
}
