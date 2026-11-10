package in.aitradingsystem.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.aitradingsystem.support.AbstractPostgresIT;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** No genuine data-use permissions are asserted: every row is synthetic. */
class GoldenSourceRightsIT extends AbstractPostgresIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired GoldenSourceRightsRepository rights;

    private static final String HASH = "e".repeat(64);
    private static final Instant USE_TIME = Instant.parse("2024-04-01T12:00:00Z");

    @Test
    void noRightsDefaultDeniesHistoricalIngestionTrainingAndRedistribution() {
        String provider = provider("EXTERNAL");
        assertThat(rights.isPermitted(provider, "daily_bars",
                GoldenSourceRightsRepository.Use.RAW_RETENTION, USE_TIME)).isFalse();
        assertThat(rights.permitsRetentionAndTraining(provider, "daily_bars", USE_TIME))
                .isFalse();
        assertThat(rights.isPermitted(provider, "daily_bars",
                GoldenSourceRightsRepository.Use.REDISTRIBUTION, USE_TIME)).isFalse();
    }

    @Test
    void independentlyVerifiedPermissionsAreUseSpecificAndExpire() {
        String provider = provider("EXTERNAL");
        receipt(provider, "daily_bars", "RAW_RETENTION", "VERIFIED",
                "2024-01-01T00:00:00Z", "2024-07-01T00:00:00Z",
                "2024-01-03T00:00:00Z", null);

        assertThat(rights.isPermitted(provider, "daily_bars",
                GoldenSourceRightsRepository.Use.RAW_RETENTION, USE_TIME)).isTrue();
        assertThat(rights.isPermitted(provider, "daily_bars",
                GoldenSourceRightsRepository.Use.MODEL_TRAINING, USE_TIME)).isFalse();
        assertThat(rights.permitsRetentionAndTraining(provider, "daily_bars", USE_TIME))
                .isFalse();

        receipt(provider, "daily_bars", "MODEL_TRAINING", "VERIFIED",
                "2024-01-01T00:00:00Z", "2024-07-01T00:00:00Z",
                "2024-01-03T00:00:00Z", null);

        assertThat(rights.permitsRetentionAndTraining(provider, "daily_bars", USE_TIME))
                .isTrue();
        assertThat(rights.isPermitted(provider, "daily_bars",
                GoldenSourceRightsRepository.Use.END_USER_DISPLAY, USE_TIME)).isFalse();
        assertThat(rights.isPermitted(provider, "daily_bars",
                GoldenSourceRightsRepository.Use.REDISTRIBUTION, USE_TIME)).isFalse();
        assertThat(rights.permitsRetentionAndTraining(
                provider, "daily_bars", Instant.parse("2024-07-01T00:00:00Z")))
                .isFalse();
        assertThat(rights.permitsRetentionAndTraining(
                provider, "daily_bars", Instant.parse("2023-12-01T00:00:00Z")))
                .isFalse();
        assertThat(rights.permitsRetentionAndTraining(provider, "news_events", USE_TIME))
                .isFalse();
    }

    @Test
    void pendingRevokedOrSyntheticEvidenceDoesNotConferRights() {
        String provider = provider("EXTERNAL");
        receipt(provider, "daily_bars", "MODEL_TRAINING", "PENDING",
                "2024-01-01T00:00:00Z", null, null, null);
        receipt(provider, "daily_bars", "MODEL_TRAINING", "REVOKED",
                "2024-01-01T00:00:00Z", null,
                "2024-01-02T00:00:00Z", "2024-03-01T00:00:00Z");
        assertThat(rights.isPermitted(provider, "daily_bars",
                GoldenSourceRightsRepository.Use.MODEL_TRAINING, USE_TIME)).isFalse();

        String fakeProvider = provider("SYNTHETIC_TEST");
        receipt(fakeProvider, "daily_bars", "MODEL_TRAINING", "VERIFIED",
                "2024-01-01T00:00:00Z", null,
                "2024-01-02T00:00:00Z", null);
        assertThat(rights.isPermitted(fakeProvider, "daily_bars",
                GoldenSourceRightsRepository.Use.MODEL_TRAINING, USE_TIME)).isFalse();
    }

    @Test
    void verifiedClaimsRequireIndependentVerifierIdentityAndTimestamp() {
        String provider = provider("EXTERNAL");
        UUID id = lookupProviderId(provider);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO golden_source_rights_receipt
                     (receipt_id, provider_id, source_domain, permitted_use,
                      receipt_status, terms_digest, valid_from,
                      source_reference, captured_at)
                VALUES (?, ?, 'daily_bars', 'MODEL_TRAINING', 'VERIFIED', ?, ?,
                        'synthetic test only', ?)
                """, UUID.randomUUID(), id, HASH, at("2024-01-01T00:00:00Z"),
                at("2024-01-04T00:00:00Z")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void qualityAndExpectedCoverageCannotBeInflatedToDeclareFullHistory() {
        String provider = provider("EXTERNAL");
        UUID id = lookupProviderId(provider);
        jdbc.update("""
                INSERT INTO golden_source_coverage_evidence
                      (coverage_id, provider_id, source_domain, scope_id,
                       dataset_digest, interval_name, from_date, through_date,
                       expected_records, valid_records, first_available_at,
                       last_available_at, quality_status, source_evidence_digest)
                VALUES (?, ?, 'daily_bars', 'LISTING:NSE:EXAMPLE',
                       ?, 'ONE_DAY', DATE '2006-01-01', DATE '2024-01-01',
                       4500, 4000, ?, ?, 'INCOMPLETE', ?)
                """, UUID.randomUUID(), id, HASH, at("2006-01-02T12:00:00Z"),
                at("2024-01-02T12:00:00Z"), HASH);

        Long rows = jdbc.queryForObject("""
                SELECT count(*) FROM golden_source_coverage_evidence
                 WHERE provider_id = ? AND valid_records < expected_records
                """, Long.class, id);
        assertThat(rows).isEqualTo(1L);

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO golden_source_coverage_evidence
                      (coverage_id, provider_id, source_domain, scope_id,
                       dataset_digest, interval_name, from_date, through_date,
                       expected_records, valid_records, first_available_at,
                       last_available_at, quality_status, source_evidence_digest)
                VALUES (?, ?, 'daily_bars', 'LISTING:NSE:EXAMPLE',
                       ?, 'ONE_DAY', DATE '2006-01-01', DATE '2024-01-01',
                       10, 11, ?, ?, 'VERIFIED', ?)
                """, UUID.randomUUID(), id, "f".repeat(64),
                at("2006-01-02T12:00:00Z"), at("2024-01-02T12:00:00Z"), HASH))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private String provider(String sourceKind) {
        String code = "TEST_" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
        jdbc.update("""
                INSERT INTO golden_data_provider
                    (provider_id, provider_code, legal_name, source_kind)
                VALUES (?, ?, 'Synthetic test provider, not licensed', ?)
                """, UUID.randomUUID(), code, sourceKind);
        return code;
    }

    private UUID lookupProviderId(String code) {
        return jdbc.queryForObject(
                "SELECT provider_id FROM golden_data_provider WHERE provider_code = ?",
                UUID.class, code);
    }

    private void receipt(String providerCode, String source, String use, String status,
                         String from, String until, String verified, String revoked) {
        jdbc.update("""
                INSERT INTO golden_source_rights_receipt
                    (receipt_id, provider_id, source_domain, permitted_use,
                     receipt_status, terms_digest, valid_from, valid_to,
                     verified_at, verified_by, revoked_at, source_reference, captured_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        'synthetic fixture, not a vendor agreement', ?)
                """,
                UUID.randomUUID(), lookupProviderId(providerCode), source, use, status,
                HASH, at(from), until == null ? null : at(until),
                verified == null ? null : at(verified),
                verified == null ? null : "TEST_VERIFIER_ONLY",
                revoked == null ? null : at(revoked),
                at("2024-01-05T00:00:00Z"));
    }

    private static OffsetDateTime at(String timestamp) {
        return OffsetDateTime.ofInstant(Instant.parse(timestamp), ZoneOffset.UTC);
    }
}
