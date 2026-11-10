package in.aitradingsystem.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.aitradingsystem.support.AbstractPostgresIT;
import java.sql.Date;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Synthetic rights, issuer, provider and objects; zero Angel One/AWS calls. */
class GoldenVendorPagePlanRepositoryIT extends AbstractPostgresIT {
    private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");
    private static final String HASH = "a".repeat(64);
    private static final LocalDate START = LocalDate.parse("2006-11-10");
    private static final LocalDate END = LocalDate.parse("2026-11-10");

    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired GoldenSourceRightsRepository rights;

    @Test
    void twentyYearPagesAreIdempotentAndNoRightsNeverClaims() {
        Fixture f = fixture();
        var repo = planner(Duration.ZERO);
        assertThat(repo.plan(f.provider(), f.listing(), START, END, 1).pageCount())
                .isEqualTo(4);
        assertThat(repo.plan(f.provider(), f.listing(), START, END, 1).pageCount())
                .isEqualTo(4);
        var p = repo.progress(f.provider(), f.listing(), 1);
        assertThat(p.pages()).isEqualTo(4);
        assertThat(p.pending()).isEqualTo(4);
        assertThat(repo.claim(f.provider())).isEmpty();
        assertThat(repo.progress(f.provider(), f.listing(), 1).pending()).isEqualTo(4);
        assertThat(jdbc.queryForObject("""
                SELECT min(from_date) FROM golden_vendor_page_plan
                 WHERE provider_id = ?
                """, Date.class, f.providerId()).toLocalDate()).isEqualTo(START);
    }

    @Test
    void sourceLeaseIsBoundedAndGloballyPacedAcrossListings() {
        Fixture f = fixture();
        UUID other = additionalListing();
        grant(f);
        var repo = planner(Duration.ZERO);
        repo.plan(f.provider(), f.listing(), START, START.plusDays(2), 1);
        repo.plan(f.provider(), other, START, START.plusDays(2), 1);
        var first = repo.claim(f.provider()).orElseThrow();
        assertThat(first.from()).isEqualTo(START);
        assertThat(first.through()).isEqualTo(START.plusDays(2));
        assertThat(first.token()).isNotNull();
        assertThat(repo.claim(f.provider())).isEmpty(); // provider-wide 10s gate
        assertThat(planner(Duration.ofSeconds(9)).claim(f.provider())).isEmpty();
        var second = planner(Duration.ofSeconds(10)).claim(f.provider()).orElseThrow();
        assertThat(second.pageId()).isNotEqualTo(first.pageId());
        assertThat(second.listingId()).isNotEqualTo(first.listingId());
        assertThat(repo.progress(f.provider(), f.listing(), 1).pages()).isEqualTo(1);
    }

    @Test
    void expiredLeaseCanBeRecoveredWithoutTrustingStaleToken() {
        Fixture f = fixture();
        grant(f);
        var repo = planner(Duration.ZERO);
        repo.plan(f.provider(), f.listing(), START, START, 1);
        var before = repo.claim(f.provider()).orElseThrow();
        var after = planner(Duration.ofMinutes(6)).claim(f.provider()).orElseThrow();
        assertThat(after.pageId()).isEqualTo(before.pageId());
        assertThat(after.token()).isNotEqualTo(before.token());
        assertThat(planner(Duration.ofMinutes(6)).retry(before)).isFalse();
        assertThat(planner(Duration.ofMinutes(6)).retry(after)).isTrue();
        assertThat(planner(Duration.ofMinutes(6)).retry(after)).isFalse();
    }

    @Test
    void transientErrorActivatesProviderWideCooldownRatherThanStormOtherPages() {
        Fixture f = fixture();
        grant(f);
        var repo = planner(Duration.ZERO);
        repo.plan(f.provider(), f.listing(), START, START.plusDays(2000), 1);
        var first = repo.claim(f.provider()).orElseThrow();
        assertThat(repo.retry(first)).isTrue();
        assertThat(planner(Duration.ofSeconds(10)).claim(f.provider())).isEmpty();
        assertThat(planner(Duration.ofSeconds(29)).claim(f.provider())).isEmpty();
        assertThat(planner(Duration.ofSeconds(31)).claim(f.provider())).isPresent();
    }

    @Test
    void revocationOverridesOlderRawRetentionAndStopsAllNextLeases() {
        Fixture f = fixture();
        grant(f);
        var repo = planner(Duration.ZERO);
        repo.plan(f.provider(), f.listing(), START, START.plusDays(2000), 1);
        var first = repo.claim(f.provider()).orElseThrow();
        revoke(f);
        assertThat(planner(Duration.ofSeconds(30)).claim(f.provider())).isEmpty();
        assertThat(planner(Duration.ofSeconds(30)).retry(first)).isTrue();
        assertThat(repo.progress(f.provider(), f.listing(), 1).staged()).isZero();
    }

    @Test
    void wrongHistoricalListingOrUnknownProviderNeverPlans() {
        Fixture f = fixture();
        var repo = planner(Duration.ZERO);
        assertThatThrownBy(() -> repo.plan(f.provider(), UUID.randomUUID(),
                START, END, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repo.plan("NOT_REGISTERED", f.listing(),
                START, END, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repo.plan(f.provider(), f.listing(),
                LocalDate.parse("1999-01-01"), END, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repo.plan(f.provider(), f.listing(),
                START, END, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void futureHistoryNeverExceedsTwoThousandDaysAndRevisionIsDistinct() {
        Fixture f = fixture();
        var repo = planner(Duration.ZERO);
        repo.plan(f.provider(), f.listing(), START, END, 1);
        repo.plan(f.provider(), f.listing(), START, END, 2);
        assertThat(repo.progress(f.provider(), f.listing(), 1).pages()).isEqualTo(4);
        assertThat(repo.progress(f.provider(), f.listing(), 2).pages()).isEqualTo(4);
        assertThat(jdbc.queryForObject("""
                SELECT max(through_date - from_date) FROM golden_vendor_page_plan
                 WHERE provider_id = ?
                """, Integer.class, f.providerId())).isLessThanOrEqualTo(1999);
    }

    @Test
    void stagedObjectReceiptCannotBeChangedOrDeletedAfterPublication() {
        Fixture f = fixture();
        var repo = planner(Duration.ZERO);
        repo.plan(f.provider(), f.listing(), START, START, 1);
        UUID page = jdbc.queryForObject("""
                SELECT page_id FROM golden_vendor_page_plan WHERE provider_id = ?
                """, UUID.class, f.providerId());
        jdbc.update("""
                UPDATE golden_vendor_page_plan
                   SET status = 'STAGED', native_object_sha256 = ?,
                       native_object_uri = 'synthetic://immutable-native',
                       native_object_version = 'test-revision-1',
                       source_evidence_digest = ?
                 WHERE page_id = ?
                """, HASH, HASH, page);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE golden_vendor_page_plan SET native_object_version = 'rewritten'
                 WHERE page_id = ?
                """, page)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                DELETE FROM golden_vendor_page_plan WHERE page_id = ?
                """, page)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void unregisteredRightsAreNotImplicitlyGrantedByPlanning() {
        Fixture f = fixture();
        planner(Duration.ZERO).plan(f.provider(), f.listing(), START, START, 1);
        Long grants = jdbc.queryForObject("""
                SELECT count(*) FROM golden_source_rights_receipt
                 WHERE provider_id = ?
                """, Long.class, f.providerId());
        assertThat(grants).isZero();
        assertThat(planner(Duration.ZERO).claim(f.provider())).isEmpty();
    }

    private GoldenVendorPagePlanRepository planner(Duration delta) {
        return new GoldenVendorPagePlanRepository(jdbc, tx, rights,
                Clock.offset(Clock.fixed(NOW, ZoneOffset.UTC), delta));
    }

    private Fixture fixture() {
        UUID providerId = UUID.randomUUID();
        String code = "TEST_" + UUID.randomUUID().toString()
                .replace("-", "").toUpperCase();
        jdbc.update("""
                INSERT INTO golden_data_provider
                    (provider_id, provider_code, legal_name, source_kind)
                VALUES (?, ?, 'Synthetic vendor page test', 'EXTERNAL')
                """, providerId, code);
        return new Fixture(code, providerId, additionalListing());
    }

    private UUID additionalListing() {
        UUID sec = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO golden_security_identity
                    (security_id, issuer_name, identity_source, identity_evidence_digest)
                VALUES (?, 'Synthetic long-listed stock', 'integration test', ?)
                """, sec, HASH);
        jdbc.update("""
                INSERT INTO golden_listing_identity
                    (listing_id, security_id, exchange, listing_series,
                     listed_from, identity_evidence_digest)
                VALUES (?, ?, 'NSE', 'EQ', DATE '2000-01-01', ?)
                """, id, sec, HASH);
        return id;
    }

    private void grant(Fixture f) {
        jdbc.update("""
                INSERT INTO golden_source_rights_receipt
                    (receipt_id, provider_id, source_domain, permitted_use,
                     receipt_status, terms_digest, valid_from, valid_to,
                     verified_at, verified_by, source_reference, captured_at)
                VALUES (?, ?, 'daily_bars', 'RAW_RETENTION', 'VERIFIED',
                        ?, ?, ?, ?, 'SYNTHETIC_ONLY', 'no real legal licence', ?)
                """, UUID.randomUUID(), f.providerId(), HASH,
                at(Instant.parse("2025-01-01T00:00:00Z")),
                at(Instant.parse("2028-01-01T00:00:00Z")),
                at(Instant.parse("2026-01-01T00:00:00Z")),
                at(Instant.parse("2026-01-02T00:00:00Z")));
    }

    private void revoke(Fixture f) {
        jdbc.update("""
                INSERT INTO golden_source_rights_receipt
                    (receipt_id, provider_id, source_domain, permitted_use,
                     receipt_status, terms_digest, valid_from, revoked_at,
                     source_reference, captured_at)
                VALUES (?, ?, 'daily_bars', 'RAW_RETENTION', 'REVOKED', ?,
                        ?, ?, 'synthetic revocation', ?)
                """, UUID.randomUUID(), f.providerId(), HASH,
                at(Instant.parse("2025-01-01T00:00:00Z")),
                at(Instant.parse("2026-10-15T00:00:00Z")),
                at(Instant.parse("2026-10-16T00:00:00Z")));
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private record Fixture(String provider, UUID providerId, UUID listing) {}
}
