package in.aitradingsystem.provider.upstox;

import static org.assertj.core.api.Assertions.assertThat;

import in.aitradingsystem.support.AbstractPostgresIT;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * PostgreSQL + production Flyway V77-V79: only synthetic test fixtures. The
 * transaction rolls back every provider, rights receipt and chunk after test.
 * A rights row here is not an actual Upstox legal entitlement.
 */
class UpstoxHistoricalJobPlanAuditIT extends AbstractPostgresIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired UpstoxHistoricalJobPlanAudit audit;

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private UUID registerUpstox() {
        jdbc.update("""
            INSERT INTO golden_data_provider
              (provider_id, provider_code, legal_name, source_kind)
            VALUES (?, 'UPSTOX', 'Synthetic test provider', 'EXTERNAL')
            ON CONFLICT (provider_code) DO NOTHING
            """, UUID.randomUUID());
        return jdbc.queryForObject(
                "SELECT provider_id FROM golden_data_provider WHERE provider_code = 'UPSTOX'",
                UUID.class);
    }

    private UUID listing(LocalDate start) {
        UUID security = UUID.randomUUID();
        UUID listing = UUID.randomUUID();
        String digest = "a".repeat(64);
        jdbc.update("""
            INSERT INTO golden_security_identity
              (security_id, issuer_name, identity_source, identity_evidence_digest)
            VALUES (?, 'Synthetic plan test', 'TEST_ONLY', ?)
            """, security, digest);
        jdbc.update("""
            INSERT INTO golden_listing_identity
              (listing_id, security_id, exchange, listing_series, listed_from, identity_evidence_digest)
            VALUES (?, ?, 'NSE', 'EQ', ?, ?)
            """, listing, security, Date.valueOf(start), digest);
        return listing;
    }

    private UUID plan(UUID provider, UUID listing, String domain,
                      LocalDate from, LocalDate to, int revision) {
        UUID jobId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO golden_dataset_backfill_job
              (job_id, provider_id, listing_id, source_domain,
               interval_name, from_date, through_date, dataset_revision)
            VALUES (?, ?, ?, ?, 'DAY', ?, ?, ?)
            """, jobId, provider, listing, domain,
                Date.valueOf(from), Date.valueOf(to), revision);
        return jobId;
    }

    private void chunk(UUID job, LocalDate from, LocalDate to, String status) {
        boolean verified = "VERIFIED".equals(status);
        String digest = "b".repeat(64);
        jdbc.update("""
            INSERT INTO golden_dataset_backfill_chunk
              (chunk_id, job_id, from_date, through_date, status,
               attempts, expected_records, valid_records, archive_uri,
               dataset_digest, source_evidence_digest)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, UUID.randomUUID(), job, Date.valueOf(from), Date.valueOf(to),
                status, verified ? 1 : 0, verified ? 1L : null,
                verified ? 1L : null,
                verified ? "s3://synthetic-only/nonexistent" : null,
                verified ? digest : null, verified ? digest : null);
    }

    @Test
    @Transactional
    void completeNonOverlappingPlanCountsOnlyCurrentlyRetentionEligibleReceipts() {
        Instant now = Instant.parse("2026-11-14T09:00:00Z");
        UUID provider = registerUpstox();
        LocalDate d0 = LocalDate.of(2026, 10, 20);
        UUID job = plan(provider, listing(d0), "plan_test_alpha", d0,
                d0.plusDays(2), 1);
        chunk(job, d0, d0.plusDays(1), "VERIFIED");
        chunk(job, d0.plusDays(2), d0.plusDays(2), "PENDING");

        var noRights = audit.snapshot(now);
        assertThat(noRights.evidenceState()).isEqualTo("VALID_CURRENT_JOB_PLANS");
        assertThat(noRights.selectedJobs()).isEqualTo(1L);
        assertThat(noRights.plannedChunks()).isEqualTo(2L);
        assertThat(noRights.receiptVerifiedChunks()).isEqualTo(1L);
        assertThat(noRights.retentionEligibleChunks()).isZero();
        assertThat(noRights.retentionEligiblePercent()).isEqualByComparingTo("0.0");
        assertThat(noRights.denominatorScope()).contains("NOT_FULL_MARKET_UNIVERSE");

        jdbc.update("""
            INSERT INTO golden_source_rights_receipt
              (receipt_id, provider_id, source_domain, permitted_use,
               receipt_status, terms_digest, valid_from, valid_to,
               verified_at, verified_by, source_reference, captured_at)
            VALUES (?, ?, 'plan_test_alpha', 'RAW_RETENTION', 'VERIFIED',
                    ?, ?, ?, ?, 'TEST_ONLY', 'synthetic-test', ?)
            """, UUID.randomUUID(), provider, "d".repeat(64),
                utc(now.minusSeconds(3600)), utc(now.plusSeconds(3600)),
                utc(now.minusSeconds(120)), utc(now));

        var attested = audit.snapshot(now);
        assertThat(attested.evidenceState()).isEqualTo("VALID_CURRENT_JOB_PLANS");
        assertThat(attested.retentionEligibleChunks()).isEqualTo(1L);
        assertThat(attested.retentionEligiblePercent()).isEqualByComparingTo("50.0");
        // A legitimately recorded expiry removes current rights eligibility.
        var expired = audit.snapshot(now.plusSeconds(3601));
        assertThat(expired.retentionEligibleChunks()).isZero();
        assertThat(expired.retentionEligiblePercent()).isEqualByComparingTo("0.0");
    }

    @Test
    @Transactional
    void gapOrOverlappingCurrentJobPlansHaveNoTrustworthyPercentage() {
        Instant now = Instant.parse("2026-11-14T09:00:00Z");
        UUID provider = registerUpstox();
        LocalDate d0 = LocalDate.of(2026, 10, 20);
        UUID listing = listing(d0);
        UUID first = plan(provider, listing, "plan_test_beta",
                d0, d0.plusDays(2), 1);
        // First planned chunk skips d0. The repository must not extrapolate
        // the missing partition into the denominator or award a percentage.
        chunk(first, d0.plusDays(1), d0.plusDays(2), "VERIFIED");
        var gapped = audit.snapshot(now);
        assertThat(gapped.evidenceState()).isEqualTo("UNVERIFIED_OR_OVERLAPPING_PLAN");
        assertThat(gapped.retentionEligiblePercent()).isNull();

        UUID second = plan(provider, listing, "plan_test_beta",
                d0.plusDays(1), d0.plusDays(3), 1);
        chunk(second, d0.plusDays(1), d0.plusDays(3), "PENDING");
        var overlapping = audit.snapshot(now);
        assertThat(overlapping.selectedJobs()).isEqualTo(2L);
        assertThat(overlapping.retentionEligiblePercent()).isNull();
        assertThat(overlapping.evidenceState()).isEqualTo("UNVERIFIED_OR_OVERLAPPING_PLAN");
    }

    @Test
    @Transactional
    void newerDatasetRevisionReplacesOldPlanNotDoubleCountsIt() {
        Instant now = Instant.parse("2026-11-14T09:00:00Z");
        UUID provider = registerUpstox();
        LocalDate day = LocalDate.of(2026, 10, 20);
        UUID listing = listing(day);
        UUID old = plan(provider, listing, "plan_test_gamma", day, day, 1);
        chunk(old, day, day, "VERIFIED");
        UUID replacement = plan(provider, listing, "plan_test_gamma", day, day, 2);
        chunk(replacement, day, day, "PENDING");
        var current = audit.snapshot(now);
        assertThat(current.selectedJobs()).isEqualTo(1L);
        assertThat(current.plannedChunks()).isEqualTo(1L);
        assertThat(current.receiptVerifiedChunks()).isZero();
        assertThat(current.retentionEligiblePercent()).isEqualByComparingTo("0.0");
    }
}
