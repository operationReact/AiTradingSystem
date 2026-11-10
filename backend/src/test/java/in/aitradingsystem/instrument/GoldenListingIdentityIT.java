package in.aitradingsystem.instrument;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.aitradingsystem.support.AbstractPostgresIT;
import java.sql.Date;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * End-to-end PostgreSQL/Flyway D20-02 identity evidence tests. None of these
 * synthetic issuers, tokens or actions represent real exchange history.
 */
class GoldenListingIdentityIT extends AbstractPostgresIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired GoldenListingIdentityRepository resolver;

    private static final String HASH = "a".repeat(64);
    private static final Instant BEFORE_REUSE = Instant.parse("2018-04-01T10:00:00Z");
    private static final Instant AFTER_REUSE = Instant.parse("2021-04-01T10:00:00Z");

    @Test
    void recycledProviderTokenMapsToDifferentListingWithoutRewritingOldHistory() {
        UUID oldSecurity = security("Former issuer");
        UUID oldListing = listing(oldSecurity, "NSE", "2002-01-01", "2020-04-01");
        UUID newSecurity = security("Successor unrelated issuer");
        UUID newListing = listing(newSecurity, "NSE", "2020-04-01", null);
        String token = "TOKEN-" + UUID.randomUUID();

        alias(oldListing, "ANGEL_ONE", "NSE", token, "FORMER-EQ",
                "2002-01-01T00:00:00Z", "2020-04-01T00:00:00Z",
                "2002-01-01T01:00:00Z", "2002-01-01T01:01:00Z");
        alias(newListing, "ANGEL_ONE", "NSE", token, "NEW-EQ",
                "2020-04-01T00:00:00Z", null,
                "2020-04-01T01:00:00Z", "2020-04-01T01:01:00Z");

        assertThat(resolver.resolveAsOf("ANGEL_ONE", "NSE", token, BEFORE_REUSE))
                .contains(oldListing);
        assertThat(resolver.resolveAsOf("ANGEL_ONE", "NSE", token, AFTER_REUSE))
                .contains(newListing);
        assertThat(resolver.resolveAsOf("ANGEL_ONE", "BSE", token, AFTER_REUSE))
                .isEmpty();
        assertThat(resolver.resolveAsOf("ANGEL_ONE", "NSE", token,
                Instant.parse("2000-01-01T10:00:00Z"))).isEmpty();
    }

    @Test
    void originalPublicationTimeNotArchiveCaptureTimeControlsHistoricalAsOf() {
        UUID issuer = security("Test renamed issuer");
        UUID listing = listing(issuer, "NSE", "2010-01-01", null);
        String token = "TOKEN-" + UUID.randomUUID();

        alias(listing, "ANGEL_ONE", "NSE", token, "RENAMED-EQ",
                "2020-04-01T00:00:00Z", null,
                "2020-04-05T00:00:00Z", "2020-04-10T00:00:00Z");

        assertThat(resolver.resolveAsOf("ANGEL_ONE", "NSE", token,
                Instant.parse("2020-04-06T10:00:00Z"))).isEmpty();
        assertThat(resolver.resolveAsOf("ANGEL_ONE", "NSE", token,
                Instant.parse("2020-04-12T10:00:00Z"))).contains(listing);
    }

    @Test
    void conflictingCrossListingAliasEvidenceMustFailClosed() {
        UUID issuer1 = security("Issuer one");
        UUID issuer2 = security("Issuer two");
        UUID one = listing(issuer1, "BSE", "2010-01-01", null);
        UUID two = listing(issuer2, "BSE", "2010-01-01", null);
        String token = "TOKEN-" + UUID.randomUUID();
        alias(one, "VENDOR", "BSE", token, "ONE-EQ",
                "2012-01-01T00:00:00Z", null,
                "2012-01-01T01:00:00Z", "2012-01-01T01:00:00Z");
        alias(two, "VENDOR", "BSE", token, "TWO-EQ",
                "2012-01-01T00:00:00Z", null,
                "2012-01-01T01:00:00Z", "2012-01-01T01:00:00Z");

        assertThatThrownBy(() -> resolver.resolveAsOf(
                "VENDOR", "BSE", token, AFTER_REUSE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ambiguous");
    }

    @Test
    void corporateActionsPreserveCorrectionsWithTheirOwnPublicationCutoffs() {
        UUID issuer = security("Action revision fixture");
        UUID listing = listing(issuer, "NSE", "2015-01-01", null);
        String eventId = "EVT-" + UUID.randomUUID();

        action(listing, eventId, 1, "ANNOUNCED",
                "2020-05-01T09:00:00Z", "2020-05-01T10:00:00Z", "2.0");
        action(listing, eventId, 2, "REVISED",
                "2020-05-05T09:00:00Z", "2020-05-05T10:00:00Z", "3.0");

        int beforeCorrection = jdbc.queryForObject("""
                SELECT count(*) FROM golden_corporate_action_evidence
                 WHERE listing_id = ? AND first_available_at <= ?
                """, Integer.class, listing, at("2020-05-03T00:00:00Z"));
        int afterCorrection = jdbc.queryForObject("""
                SELECT count(*) FROM golden_corporate_action_evidence
                 WHERE listing_id = ? AND first_available_at <= ?
                """, Integer.class, listing, at("2020-05-06T00:00:00Z"));
        assertThat(beforeCorrection).isEqualTo(1);
        assertThat(afterCorrection).isEqualTo(2);

        // No in-place revision overwrites; evidence remains append-only by
        // (listing, source event, revision) uniqueness.
        assertThatThrownBy(() -> action(listing, eventId, 1, "REVISED",
                "2020-05-07T09:00:00Z", "2020-05-07T10:00:00Z", "4.0"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void invalidListingIntervalsAndEvidenceChronologyAreRejected() {
        UUID issuer = security("Invalid evidence fixture");
        assertThatThrownBy(() -> listing(issuer, "NSE", "2020-01-01", "2019-12-31"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        UUID listing = listing(issuer, "NSE", "2020-01-01", null);
        assertThatThrownBy(() -> alias(listing, "ANGEL_ONE", "NSE",
                "TOKEN-" + UUID.randomUUID(), "TEST-EQ",
                "2020-04-05T00:00:00Z", "2020-04-01T00:00:00Z",
                "2020-04-05T01:00:00Z", "2020-04-05T02:00:00Z"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> action(listing, "EVT-" + UUID.randomUUID(),
                1, "ANNOUNCED",
                "2020-05-05T10:00:00Z", "2020-05-05T09:00:00Z", "2.0"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    private UUID security(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO golden_security_identity
                       (security_id, issuer_name, identity_source, identity_evidence_digest)
                VALUES (?, ?, 'SYNTHETIC_TEST_ONLY', ?)
                """, id, name, HASH);
        return id;
    }

    private UUID listing(UUID security, String exchange, String from, String to) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO golden_listing_identity
                       (listing_id, security_id, exchange, listing_series,
                        listed_from, delisted_on, identity_evidence_digest)
                VALUES (?, ?, ?, 'EQ', ?, ?, ?)
                """, id, security, exchange, Date.valueOf(from),
                to == null ? null : Date.valueOf(to), HASH);
        return id;
    }

    private void alias(
            UUID listing, String provider, String exchange, String token, String symbol,
            String from, String to, String published, String available) {
        jdbc.update("""
                INSERT INTO golden_provider_symbol_alias
                       (alias_id, listing_id, provider, exchange, provider_token,
                        trading_symbol, valid_from, valid_to, source_published_at,
                        first_available_at, source_event_id, source_evidence_digest)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(), listing, provider, exchange, token, symbol,
                at(from), to == null ? null : at(to), at(published), at(available),
                "SOURCE-" + UUID.randomUUID(), HASH);
    }

    private void action(
            UUID listing, String eventId, int revision, String status,
            String announced, String available, String factor) {
        jdbc.update("""
                INSERT INTO golden_corporate_action_evidence
                       (action_id, listing_id, event_type, event_status,
                        announcement_at, first_available_at, ex_date, effective_date,
                        adjustment_factor, source_name, source_event_id,
                        source_revision, source_evidence_digest)
                VALUES (?, ?, 'SPLIT', ?, ?, ?, '2020-06-01', '2020-06-01',
                        ?, 'SYNTHETIC_TEST_ONLY', ?, ?, ?)
                """, UUID.randomUUID(), listing, status, at(announced),
                at(available), new java.math.BigDecimal(factor),
                eventId, revision, HASH);
    }

    private static OffsetDateTime at(String iso) {
        return OffsetDateTime.ofInstant(Instant.parse(iso), ZoneOffset.UTC);
    }
}
