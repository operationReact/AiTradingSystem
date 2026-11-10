package in.aitradingsystem.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.aitradingsystem.support.AbstractPostgresIT;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** All stored external source evidence and rights here are synthetic test fixtures. */
class GoldenPersistedHistoricalSessionEvidenceIT extends AbstractPostgresIT {
    private static final String HASH = "a".repeat(64);
    private static final String DOMAIN = "exchange_calendar";
    private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");
    private static final AtomicInteger N = new AtomicInteger();
    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    @Autowired JdbcTemplate jdbc;
    @Autowired GoldenSourceRightsRepository rights;

    @Test
    void emptyCalendarRegistryFailsClosedAndListingLookupNeverInventsIdentity() {
        LocalDate date = uniqueDate();
        assertThat(resolver().resolve("NSE", date).status())
                .isEqualTo(GoldenHistoricalSessionEvidence.Status.UNKNOWN);
        assertThat(new GoldenJdbcHistoricalListingEvidence(jdbc)
                .resolve(UUID.randomUUID())).isNull();
    }

    @Test
    void attestedSourceWithoutVerifiedRightsStillReturnsUnknown() {
        Fixture f = fixture();
        insert(f, f.date(), 1, "TRADING", close(f.date()), at("2026-11-02T00:00:00Z"));
        assertThat(resolver().resolve("NSE", f.date()).status())
                .isEqualTo(GoldenHistoricalSessionEvidence.Status.UNKNOWN);
    }

    @Test
    void explicitGrantEnablesOnlyAttestedMatchingExchangeAndDate() {
        Fixture f = fixture();
        grant(f);
        Instant close = close(f.date());
        insert(f, f.date(), 1, "TRADING", close, at("2026-11-02T00:00:00Z"));
        var found = resolver().resolve("NSE", f.date());
        assertThat(found.status()).isEqualTo(GoldenHistoricalSessionEvidence.Status.TRADING);
        assertThat(found.sessionClose()).isEqualTo(close);
        assertThat(found.evidenceDigest()).isEqualTo(HASH);
        assertThat(resolver().resolve("BSE", f.date()).status())
                .isEqualTo(GoldenHistoricalSessionEvidence.Status.UNKNOWN);
        var listing = new GoldenJdbcHistoricalListingEvidence(jdbc).resolve(f.listing());
        assertThat(listing.listingId()).isEqualTo(f.listing());
        assertThat(listing.exchange()).isEqualTo("NSE");
        assertThat(listing.listedFrom()).isEqualTo(LocalDate.of(2000, 1, 1));
    }

    @Test
    void laterRevisionDoesNotRewritePreviousSessionRecord() {
        Fixture f = fixture();
        grant(f);
        insert(f, f.date(), 1, "TRADING", close(f.date()), at("2026-11-02T00:00:00Z"));
        insert(f, f.date(), 2, "CLOSED", null, at("2026-11-03T00:00:00Z"));
        assertThat(resolver().resolve("NSE", f.date()).status())
                .isEqualTo(GoldenHistoricalSessionEvidence.Status.CLOSED);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM golden_historical_exchange_session_evidence
                 WHERE provider_id = ? AND session_date = ?
                """, Long.class, f.providerId(), Date.valueOf(f.date())))
                .isEqualTo(2L);
    }

    @Test
    void multipleProviderCalendarsNeverSilentlySelectAWinningSource() {
        Fixture f = fixture();
        grant(f);
        insert(f, f.date(), 1, "TRADING", close(f.date()), at("2026-11-02T00:00:00Z"));
        Fixture g = fixture(f.date());
        grant(g);
        insert(g, g.date(), 1, "CLOSED", null, at("2026-11-02T00:00:00Z"));
        assertThat(resolver().resolve("NSE", f.date()).status())
                .isEqualTo(GoldenHistoricalSessionEvidence.Status.UNKNOWN);
    }

    @Test
    void futureVerificationCannotRetroactivelyClaimCalendarReadiness() {
        Fixture f = fixture();
        grant(f);
        insert(f, f.date(), 1, "TRADING", close(f.date()), at("2026-11-12T00:00:00Z"));
        assertThat(resolver().resolve("NSE", f.date()).status())
                .isEqualTo(GoldenHistoricalSessionEvidence.Status.UNKNOWN);
    }

    @Test
    void revokedRawCalendarRetentionStopsPreviouslyAttestedReads() {
        Fixture f = fixture();
        grant(f);
        insert(f, f.date(), 1, "TRADING", close(f.date()), at("2026-11-02T00:00:00Z"));
        assertThat(resolver().resolve("NSE", f.date()).status())
                .isEqualTo(GoldenHistoricalSessionEvidence.Status.TRADING);
        jdbc.update("""
                INSERT INTO golden_source_rights_receipt
                    (receipt_id, provider_id, source_domain, permitted_use,
                     receipt_status, terms_digest, valid_from,
                     revoked_at, source_reference, captured_at)
                VALUES (?, ?, ?, 'RAW_RETENTION', 'REVOKED', ?, ?, ?,
                        'synthetic revocation', ?)
                """, UUID.randomUUID(), f.providerId(), DOMAIN, HASH,
                at("2025-01-01T00:00:00Z"), at("2026-11-01T00:00:00Z"),
                at("2026-11-02T00:00:00Z"));
        assertThat(resolver().resolve("NSE", f.date()).status())
                .isEqualTo(GoldenHistoricalSessionEvidence.Status.UNKNOWN);
    }

    @Test
    void publishedCalendarEvidenceCannotBeUpdatedOrDeletedInPlace() {
        Fixture f = fixture();
        insert(f, f.date(), 1, "TRADING", close(f.date()), at("2026-11-02T00:00:00Z"));
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE golden_historical_exchange_session_evidence
                   SET session_status = 'CLOSED', session_close_at = NULL
                 WHERE provider_id = ? AND session_date = ?
                """, f.providerId(), Date.valueOf(f.date())))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                DELETE FROM golden_historical_exchange_session_evidence
                 WHERE provider_id = ? AND session_date = ?
                """, f.providerId(), Date.valueOf(f.date())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void postgresBackedCalendarCanValidateExactlyOneSyntheticDailyPilot() {
        Fixture f = fixture(LocalDate.parse("2026-11-06"));
        grant(f);
        LocalDate friday = LocalDate.parse("2026-11-06");
        LocalDate saturday = friday.plusDays(1);
        LocalDate sunday = saturday.plusDays(1);
        insert(f, friday, 1, "TRADING", Instant.parse("2026-11-06T10:00:00Z"),
                at("2026-11-10T11:00:00Z"));
        insert(f, saturday, 1, "CLOSED", null, at("2026-11-10T11:00:00Z"));
        insert(f, sunday, 1, "TRADING", Instant.parse("2026-11-08T09:45:00Z"),
                at("2026-11-10T11:00:00Z"));
        var validator = new GoldenDailyBarValidator(
                new GoldenJdbcHistoricalListingEvidence(jdbc),
                resolver(), Clock.fixed(NOW, ZoneOffset.UTC));
        var request = new GoldenHistoricalDatasetProvider.ChunkRequest(
                UUID.randomUUID(), UUID.randomUUID(), f.listing(), "ONE_DAY",
                friday, sunday, 4096);
        var oneBar = payload(f.listing(), "2026-11-06",
                "2026-11-06T10:01:00Z", "2026-11-06T10:10:00Z");
        var onlyFriday = validator.inspect(request, oneBar);
        assertThat(onlyFriday.complete()).isFalse();
        assertThat(onlyFriday.missingSessions()).containsExactly(sunday);
        var both = new GoldenHistoricalDatasetProvider.SourcePayload((
                GoldenDailyBarValidator.HEADER + "\n"
                + row(f.listing(), "2026-11-06", "2026-11-06T10:01:00Z",
                      "2026-11-06T10:10:00Z") + "\n"
                + row(f.listing(), "2026-11-08", "2026-11-08T09:50:00Z",
                      "2026-11-08T10:00:00Z") + "\n"
                ).getBytes(StandardCharsets.UTF_8), HASH);
        assertThat(validator.inspect(request, both).complete()).isTrue();
    }

    private GoldenPersistedHistoricalSessionEvidence resolver() {
        return new GoldenPersistedHistoricalSessionEvidence(jdbc, rights,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private Fixture fixture() {
        return fixture(uniqueDate());
    }

    private static LocalDate uniqueDate() {
        return LocalDate.of(2010, 1, 1).plusDays(N.incrementAndGet());
    }

    private Fixture fixture(LocalDate date) {
        UUID providerId = UUID.randomUUID();
        String providerCode = "TEST_" + UUID.randomUUID()
                .toString().replace("-", "").toUpperCase();
        UUID securityId = UUID.randomUUID();
        UUID listingId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO golden_data_provider
                    (provider_id, provider_code, legal_name, source_kind)
                VALUES (?, ?, 'Synthetic session authority', 'EXTERNAL')
                """, providerId, providerCode);
        jdbc.update("""
                INSERT INTO golden_security_identity
                    (security_id, issuer_name, identity_source, identity_evidence_digest)
                VALUES (?, 'Synthetic historical issuer', 'integration test', ?)
                """, securityId, HASH);
        jdbc.update("""
                INSERT INTO golden_listing_identity
                    (listing_id, security_id, exchange, listing_series,
                     listed_from, identity_evidence_digest)
                VALUES (?, ?, 'NSE', 'EQ', DATE '2000-01-01', ?)
                """, listingId, securityId, HASH);
        return new Fixture(providerCode, providerId, listingId, date);
    }

    private void grant(Fixture f) {
        jdbc.update("""
                INSERT INTO golden_source_rights_receipt
                    (receipt_id, provider_id, source_domain, permitted_use,
                     receipt_status, terms_digest, valid_from, valid_to,
                     verified_at, verified_by, source_reference, captured_at)
                VALUES (?, ?, ?, 'RAW_RETENTION', 'VERIFIED', ?, ?, ?, ?,
                        'SYNTHETIC_FIXTURE', 'synthetic, not legal authority', ?)
                """, UUID.randomUUID(), f.providerId(), DOMAIN, HASH,
                at("2025-01-01T00:00:00Z"), at("2028-01-01T00:00:00Z"),
                at("2026-11-01T00:00:00Z"), at("2026-11-02T00:00:00Z"));
    }

    private void insert(Fixture f, LocalDate date, int revision,
                        String status, Instant close, OffsetDateTime captured) {
        Instant published = date.atStartOfDay(INDIA).toInstant().plusSeconds(86400);
        if (close != null && !published.isAfter(close)) published = close.plusSeconds(60);
        Instant available = published.plusSeconds(60);
        OffsetDateTime verified = captured.toInstant().isAfter(NOW)
                ? captured.minusHours(1) : at("2026-11-01T00:00:00Z");
        jdbc.update("""
                INSERT INTO golden_historical_exchange_session_evidence
                    (evidence_id, provider_id, source_domain, exchange,
                     session_date, source_revision, session_status, session_close_at,
                     source_published_at, first_available_at,
                     calendar_evidence_digest, verified_at, verified_by,
                     source_reference, captured_at)
                VALUES (?, ?, ?, 'NSE', ?, ?, ?, ?, ?, ?, ?, ?,
                        'SYNTHETIC_TEST', 'synthetic fixture', ?)
                """, UUID.randomUUID(), f.providerId(), DOMAIN,
                Date.valueOf(date), revision, status,
                close == null ? null : OffsetDateTime.ofInstant(close, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(published, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(available, ZoneOffset.UTC),
                HASH, verified, captured);
    }

    private GoldenHistoricalDatasetProvider.SourcePayload payload(
            UUID listingId, String date, String published, String available) {
        String data = GoldenDailyBarValidator.HEADER + "\n"
                + row(listingId, date, published, available) + "\n";
        return new GoldenHistoricalDatasetProvider.SourcePayload(
                data.getBytes(StandardCharsets.UTF_8), HASH);
    }

    private static String row(UUID listingId, String day, String published, String available) {
        return String.join(",", listingId.toString(), "NSE", day, "105",
                "110", "90", "100", "1000", published, available, "1", "RAW");
    }

    private static Instant close(LocalDate date) {
        return date.atTime(15, 30).atZone(INDIA).toInstant();
    }

    private static OffsetDateTime at(String iso) {
        return OffsetDateTime.ofInstant(Instant.parse(iso), ZoneOffset.UTC);
    }

    private record Fixture(String providerCode, UUID providerId,
                           UUID listing, LocalDate date) {}
}
