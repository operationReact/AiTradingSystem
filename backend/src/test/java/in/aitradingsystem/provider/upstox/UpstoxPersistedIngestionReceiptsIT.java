package in.aitradingsystem.provider.upstox;

import static org.assertj.core.api.Assertions.assertThat;

import in.aitradingsystem.support.AbstractPostgresIT;
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
 * Actual Flyway V77-V79 tables on runner-local PostgreSQL.
 * Test-only synthetic rows are rolled back; no vendor data is generated.
 */
class UpstoxPersistedIngestionReceiptsIT extends AbstractPostgresIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired UpstoxPersistedIngestionReceipts receipts;

    private static OffsetDateTime utc(Instant at) {
        return OffsetDateTime.ofInstant(at, ZoneOffset.UTC);
    }

    @Test
    @Transactional
    void registeredProviderSeparatesArchivedChunksFromRightsAttestedChunks() {
        var now = Instant.parse("2026-11-14T09:00:00Z");
        var digest = "a".repeat(64);
        var provider = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO golden_data_provider (provider_id, provider_code, legal_name, source_kind)
            VALUES (?, 'UPSTOX', 'Upstox synthetic integration fixture', 'EXTERNAL')
            ON CONFLICT (provider_code) DO NOTHING
            """, provider);
        provider = jdbc.queryForObject(
                "SELECT provider_id FROM golden_data_provider WHERE provider_code='UPSTOX'",
                UUID.class);
        assertThat(provider).isNotNull();

        var start = receipts.snapshot(now);
        assertThat(start.evidenceState()).isEqualTo("PERSISTED_CHECKPOINTS_ONLY");
        var security = UUID.randomUUID();
        var listing = UUID.randomUUID();
        var job = UUID.randomUUID();
        var chunk = UUID.randomUUID();
        var day = Date.valueOf(LocalDate.of(2026, 10, 20));
        var domain = "receipt_test_" + UUID.randomUUID().toString()
                .replace("-", "").substring(0, 16);

        jdbc.update("""
            INSERT INTO golden_security_identity
              (security_id, issuer_name, identity_source, identity_evidence_digest)
            VALUES (?, 'Synthetic integration listing', 'TEST_ONLY', ?)
            """, security, digest);
        jdbc.update("""
            INSERT INTO golden_listing_identity
              (listing_id, security_id, exchange, listing_series, listed_from, identity_evidence_digest)
            VALUES (?, ?, 'NSE', 'EQ', ?, ?)
            """, listing, security, day, digest);
        jdbc.update("""
            INSERT INTO golden_dataset_backfill_job
              (job_id, provider_id, listing_id, source_domain, interval_name,
               from_date, through_date, dataset_revision)
            VALUES (?, ?, ?, ?, 'DAY', ?, ?, 1)
            """, job, provider, listing, domain, day, day);
        jdbc.update("""
            INSERT INTO golden_dataset_backfill_chunk
              (chunk_id, job_id, from_date, through_date, status, attempts,
               expected_records, valid_records, archive_uri, dataset_digest, source_evidence_digest)
            VALUES (?, ?, ?, ?, 'VERIFIED', 1, 1, 1, 's3://synthetic-test-only/not-live', ?, ?)
            """, chunk, job, day, day, digest, digest);

        var persisted = receipts.snapshot(now);
        assertThat(persisted.jobs()).isEqualTo(start.jobs() + 1);
        assertThat(persisted.chunks()).isEqualTo(start.chunks() + 1);
        assertThat(persisted.archivedVerifiedChunks())
                .isEqualTo(start.archivedVerifiedChunks() + 1);
        assertThat(persisted.attestedRetentionChunks())
                .isEqualTo(start.attestedRetentionChunks());
        assertThat(persisted.archivedVerifiedRecords())
                .isEqualTo(start.archivedVerifiedRecords() + 1);
        assertThat(persisted.attestedRetentionRecords())
                .isEqualTo(start.attestedRetentionRecords());

        var rights = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO golden_source_rights_receipt
              (receipt_id, provider_id, source_domain, permitted_use, receipt_status,
               terms_digest, valid_from, valid_to, verified_at, verified_by,
               source_reference, captured_at)
            VALUES (?, ?, ?, 'RAW_RETENTION', 'VERIFIED',
                    ?, ?, ?, ?, 'CI_TEST_ONLY', 'synthetic-source-rights-test', ?)
            """, rights, provider, domain, digest,
                utc(now.minusSeconds(3600)), utc(now.plusSeconds(3600)),
                utc(now.minusSeconds(120)), utc(now));

        var withRights = receipts.snapshot(now);
        assertThat(withRights.attestedRetentionChunks())
                .isEqualTo(start.attestedRetentionChunks() + 1);
        assertThat(withRights.attestedRetentionRecords())
                .isEqualTo(start.attestedRetentionRecords() + 1);

        // Retention-only rights do not magically become permanent. Expiry
        // removes current attestation but never deletes the archived row.
        var afterExpiry = receipts.snapshot(now.plusSeconds(3601));
        assertThat(afterExpiry.attestedRetentionChunks())
                .isEqualTo(start.attestedRetentionChunks());
        assertThat(afterExpiry.archivedVerifiedChunks())
                .isEqualTo(start.archivedVerifiedChunks() + 1);
    }
}
