package in.aitradingsystem.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.aitradingsystem.support.AbstractPostgresIT;
import java.sql.Date;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * All receipts, archive objects and inspector results are synthetic fixtures.
 * No provider agreement or external S3 object is asserted by these tests.
 */
class GoldenDatasetManifestIT extends AbstractPostgresIT {
    private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");
    private static final String HASH = "a".repeat(64);
    private static final String OTHER_HASH = "b".repeat(64);
    @Autowired JdbcTemplate jdbc;
    @Autowired GoldenSourceRightsRepository rights;

    @Test
    void noRetentionRightsPreventsPhysicalInspectionAndPublication() {
        Fixture f = fixture("VERIFIED");
        AtomicInteger inspections = new AtomicInteger();
        assertThatThrownBy(() -> repository().publishBronze(
                f.chunk(), inspected(inspections, HASH, "v1"), "bars.v1"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(inspections).hasValue(0);
        assertThat(manifestCount(f.chunk())).isZero();
    }

    @Test
    void verifiedReadbackPublishesOnceAndPinsObjectVersionAndSchema() {
        Fixture f = fixture("VERIFIED");
        grant(f, "RAW_RETENTION");
        AtomicInteger inspections = new AtomicInteger();
        var repo = repository();
        var manifest = repo.publishBronze(f.chunk(),
                inspected(inspections, HASH, "s3-version-fixture-1"), "bars.v1");
        assertThat(manifest.objectVersionId()).isEqualTo("s3-version-fixture-1");
        assertThat(manifest.objectSha256()).isEqualTo(HASH);
        assertThat(manifest.objectSizeBytes()).isEqualTo(145);
        assertThat(manifest.manifestDigest()).matches("[a-f0-9]{64}");
        assertThat(repo.publishBronze(f.chunk(),
                inspected(inspections, HASH, "s3-version-fixture-1"), "bars.v1")
                .manifestId()).isEqualTo(manifest.manifestId());
        assertThat(manifestCount(f.chunk())).isEqualTo(1L);
        assertThat(inspections).hasValue(2);
        assertThat(repo.findAuthorizedBronze(manifest.manifestId(),
                GoldenSourceRightsRepository.Use.MODEL_TRAINING)).isEmpty();
        assertThat(repo.findAuthorizedBronze(manifest.manifestId(),
                GoldenSourceRightsRepository.Use.RAW_RETENTION)).isPresent();
    }

    @Test
    void changedObjectVersionMustUseNewSourceRevisionNotOverwriteManifest() {
        Fixture f = fixture("VERIFIED");
        grant(f, "RAW_RETENTION");
        var repo = repository();
        var original = repo.publishBronze(f.chunk(), inspected(
                new AtomicInteger(), HASH, "version-1"), "bars.v1");
        assertThatThrownBy(() -> repo.publishBronze(f.chunk(), inspected(
                new AtomicInteger(), HASH, "version-2"), "bars.v1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("new source revision");
        assertThat(repo.findAuthorizedBronze(original.manifestId(),
                GoldenSourceRightsRepository.Use.RAW_RETENTION)).isPresent();
        assertThat(manifestCount(f.chunk())).isEqualTo(1L);
    }

    @Test
    void badIndependentChecksumIsRejectedBeforeAnyManifestInsert() {
        Fixture f = fixture("VERIFIED");
        grant(f, "RAW_RETENTION");
        assertThatThrownBy(() -> repository().publishBronze(f.chunk(),
                inspected(new AtomicInteger(), OTHER_HASH, "version-1"), "bars.v1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("physical archive evidence");
        assertThat(manifestCount(f.chunk())).isZero();
    }

    @Test
    void onlyVerifiedNonemptyCompleteChunksMayBePublished() {
        Fixture f = fixture("INCOMPLETE");
        grant(f, "RAW_RETENTION");
        AtomicInteger inspections = new AtomicInteger();
        assertThatThrownBy(() -> repository().publishBronze(f.chunk(),
                inspected(inspections, HASH, "v1"), "bars.v1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(inspections).hasValue(0);
        assertThat(manifestCount(f.chunk())).isZero();
    }

    @Test
    void separatelyAuthorizedTrainingDoesNotGrantRedistribution() {
        Fixture f = fixture("VERIFIED");
        grant(f, "RAW_RETENTION");
        grant(f, "MODEL_TRAINING");
        var repo = repository();
        var m = repo.publishBronze(f.chunk(),
                inspected(new AtomicInteger(), HASH, "version-1"), "bars.v1");
        assertThat(repo.findAuthorizedBronze(m.manifestId(),
                GoldenSourceRightsRepository.Use.MODEL_TRAINING)).isPresent();
        assertThat(repo.findAuthorizedBronze(m.manifestId(),
                GoldenSourceRightsRepository.Use.END_USER_DISPLAY)).isEmpty();
        assertThat(repo.findAuthorizedBronze(m.manifestId(),
                GoldenSourceRightsRepository.Use.REDISTRIBUTION)).isEmpty();
    }

    @Test
    void laterRecordedRevocationDeniesReadingPreviouslyPublishedVersion() {
        Fixture f = fixture("VERIFIED");
        grant(f, "RAW_RETENTION");
        grant(f, "MODEL_TRAINING");
        var repo = repository();
        var m = repo.publishBronze(f.chunk(),
                inspected(new AtomicInteger(), HASH, "version-1"), "bars.v1");
        assertThat(repo.findAuthorizedBronze(m.manifestId(),
                GoldenSourceRightsRepository.Use.MODEL_TRAINING)).isPresent();
        revoke(f, "MODEL_TRAINING");
        assertThat(repo.findAuthorizedBronze(m.manifestId(),
                GoldenSourceRightsRepository.Use.MODEL_TRAINING)).isEmpty();
        revoke(f, "RAW_RETENTION");
        assertThat(repo.findAuthorizedBronze(m.manifestId(),
                GoldenSourceRightsRepository.Use.RAW_RETENTION)).isEmpty();
    }

    @Test
    void databaseRejectsInPlaceMutationOfImmutablePublishedManifests() {
        Fixture f = fixture("VERIFIED");
        grant(f, "RAW_RETENTION");
        var m = repository().publishBronze(f.chunk(),
                inspected(new AtomicInteger(), HASH, "version-1"), "bars.v1");
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE golden_dataset_object_manifest
                   SET object_version_id = 'malicious-replacement'
                 WHERE manifest_id = ?
                """, m.manifestId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM golden_dataset_object_manifest WHERE manifest_id = ?",
                m.manifestId())).isInstanceOf(DataIntegrityViolationException.class);
    }

    private Fixture fixture(String chunkStatus) {
        UUID providerId = UUID.randomUUID();
        String providerCode = "TEST_" + UUID.randomUUID()
                .toString().replace("-", "").toUpperCase();
        UUID security = UUID.randomUUID();
        UUID listing = UUID.randomUUID();
        UUID job = UUID.randomUUID();
        UUID chunk = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO golden_data_provider
                    (provider_id, provider_code, legal_name, source_kind)
                VALUES (?, ?, 'Synthetic archive only', 'EXTERNAL')
                """, providerId, providerCode);
        jdbc.update("""
                INSERT INTO golden_security_identity
                    (security_id, issuer_name, identity_source, identity_evidence_digest)
                VALUES (?, 'Synthetic listing', 'integration test', ?)
                """, security, HASH);
        jdbc.update("""
                INSERT INTO golden_listing_identity
                    (listing_id, security_id, exchange, listing_series,
                     listed_from, identity_evidence_digest)
                VALUES (?, ?, 'NSE', 'EQ', DATE '2000-01-01', ?)
                """, listing, security, HASH);
        jdbc.update("""
                INSERT INTO golden_dataset_backfill_job
                    (job_id, provider_id, listing_id, source_domain, interval_name,
                     from_date, through_date, dataset_revision, status)
                VALUES (?, ?, ?, 'daily_bars', 'ONE_DAY',
                        DATE '2026-10-01', DATE '2026-10-05', 1, 'COMPLETE')
                """, job, providerId, listing);
        jdbc.update("""
                INSERT INTO golden_dataset_backfill_chunk
                    (chunk_id, job_id, from_date, through_date, status,
                     attempts, expected_records, valid_records, archive_uri,
                     dataset_digest, source_evidence_digest,
                     first_available_at, last_available_at)
                VALUES (?, ?, DATE '2026-10-01', DATE '2026-10-05',
                        ?, 1, 2, ?, 'fixture://content-addressed/object-01',
                        ?, ?, ?, ?)
                """, chunk, job, chunkStatus, "VERIFIED".equals(chunkStatus) ? 2 : 1,
                HASH, HASH,
                at(Instant.parse("2026-10-01T12:00:00Z")),
                at(Instant.parse("2026-10-05T12:00:00Z")));
        return new Fixture(providerId, chunk);
    }

    private GoldenDatasetManifestRepository repository() {
        return new GoldenDatasetManifestRepository(jdbc, rights,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private GoldenObjectInspector inspected(AtomicInteger calls, String checksum,
                                             String version) {
        return uri -> {
            calls.incrementAndGet();
            return new GoldenObjectInspector.InspectedObject(version, checksum, 145);
        };
    }

    private void grant(Fixture f, String use) {
        jdbc.update("""
                INSERT INTO golden_source_rights_receipt
                    (receipt_id, provider_id, source_domain, permitted_use,
                     receipt_status, terms_digest, valid_from, valid_to,
                     verified_at, verified_by, source_reference, captured_at)
                VALUES (?, ?, 'daily_bars', ?, 'VERIFIED', ?, ?, ?,
                        ?, 'TEST_VERIFIER_ONLY', 'synthetic evidence', ?)
                """, UUID.randomUUID(), f.providerId(), use, HASH,
                at(Instant.parse("2025-01-01T00:00:00Z")),
                at(Instant.parse("2028-01-01T00:00:00Z")),
                at(Instant.parse("2026-01-01T00:00:00Z")),
                at(Instant.parse("2026-01-02T00:00:00Z")));
    }

    private void revoke(Fixture f, String use) {
        jdbc.update("""
                INSERT INTO golden_source_rights_receipt
                    (receipt_id, provider_id, source_domain, permitted_use,
                     receipt_status, terms_digest, valid_from, revoked_at,
                     source_reference, captured_at)
                VALUES (?, ?, 'daily_bars', ?, 'REVOKED', ?, ?, ?,
                        'synthetic revocation', ?)
                """, UUID.randomUUID(), f.providerId(), use, HASH,
                at(Instant.parse("2025-01-01T00:00:00Z")),
                at(Instant.parse("2026-10-15T00:00:00Z")),
                at(Instant.parse("2026-10-16T00:00:00Z")));
    }

    private long manifestCount(UUID chunkId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM golden_dataset_object_manifest WHERE chunk_id = ?",
                Long.class, chunkId);
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private record Fixture(UUID providerId, UUID chunk) {}
}
