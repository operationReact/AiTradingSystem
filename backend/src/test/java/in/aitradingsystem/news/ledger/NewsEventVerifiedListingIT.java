package in.aitradingsystem.news.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.aitradingsystem.news.policy.NewsSourcePolicyTestSupport;
import in.aitradingsystem.support.AbstractPostgresIT;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Additive Flyway V130 integration: an event's issuer linkage must refer to a
 * genuine dated listing-evidence record and cannot be backdated or rewritten.
 * Fixtures are synthetic; this does not approve a real source or issuer.
 */
class NewsEventVerifiedListingIT extends AbstractPostgresIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired JdbcClient client;

    private record Fixture(UUID eventId, UUID receiptId, UUID listingId, String digest,
                           Instant firstKnowableAt) {}

    private Fixture fixture() {
        Instant now = Instant.now();
        Instant eventKnownAt = now.minusSeconds(120);
        Instant listingEnteredAt = now.minusSeconds(60);
        UUID securityId = UUID.randomUUID();
        UUID listingId = UUID.randomUUID();
        UUID receiptId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        String source = "fictional-" + UUID.randomUUID();
        String digest = "a".repeat(64);
        String receiptHash = UUID.randomUUID().toString().replace("-", "")
            + UUID.randomUUID().toString().replace("-", "");
        NewsSourcePolicyTestSupport.approveFixture(client, source);

        jdbc.update("""
                INSERT INTO golden_security_identity
                    (security_id, issuer_name, isin_when_verified,
                     identity_source, identity_evidence_digest, created_at)
                VALUES (?, 'Fictional Listed Issuer', 'IN0000000001',
                        'SYNTHETIC_OFFICIAL_FIXTURE', ?, ?)
                """, securityId, digest, Timestamp.from(listingEnteredAt));
        jdbc.update("""
                INSERT INTO golden_listing_identity
                    (listing_id, security_id, exchange, listing_series, listed_from,
                     identity_evidence_digest, created_at)
                VALUES (?, ?, 'NSE', 'EQ', ?, ?, ?)
                """, listingId, securityId, Date.valueOf(LocalDate.parse("2018-01-01")),
            digest, Timestamp.from(listingEnteredAt));
        jdbc.update("""
                INSERT INTO news_ingest_receipt
                    (receipt_id, source_id, source_url, title, content_hash,
                     published_at, first_observed_at, fetched_at)
                VALUES (?, ?, ?, 'Fictional issuer disclosure', ?, ?, ?, ?)
                """, receiptId, source,
            "https://news.example.test/" + receiptId, receiptHash,
            Timestamp.from(eventKnownAt.minusSeconds(3600)),
            Timestamp.from(eventKnownAt), Timestamp.from(eventKnownAt));
        jdbc.update("""
                INSERT INTO news_canonical_event
                    (event_id, event_class, status, first_seen_at, current_version)
                VALUES (?, 'UNCLASSIFIED', 'ACTIVE', ?, 1)
                """, eventId, Timestamp.from(eventKnownAt));
        jdbc.update("""
                INSERT INTO news_event_version
                    (event_id, version, effective_known_at, category)
                VALUES (?, 1, ?, 'UNCLASSIFIED')
                """, eventId, Timestamp.from(eventKnownAt));
        jdbc.update("""
                INSERT INTO news_event_evidence
                    (event_id, version, receipt_id, evidence_kind, observed_at)
                VALUES (?, 1, ?, 'OTHER', ?)
                """, eventId, receiptId, Timestamp.from(eventKnownAt));
        return new Fixture(eventId, receiptId, listingId, digest, listingEnteredAt);
    }

    private void link(Fixture f, String digest, Instant knownAt) {
        jdbc.update("""
                INSERT INTO news_event_verified_listing_evidence
                    (event_id, version, receipt_id, listing_id, relationship,
                     listing_identity_digest, mapping_first_known_at, evidence_method)
                VALUES (?, 1, ?, ?, 'AFFECTED_ISSUER', ?, ?, 'FICTIONAL_MANUAL_REVIEW')
                """, f.eventId(), f.receiptId(), f.listingId(), digest, Timestamp.from(knownAt));
    }

    @Test
    void acceptsEvidenceOnlyAfterBothNewsAndDatedListingAreKnowable() {
        Fixture f = fixture();
        link(f, f.digest(), Instant.now().plusSeconds(1));
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM news_event_verified_listing_evidence
                 WHERE event_id = ? AND version = 1
                """, Integer.class, f.eventId());
        assertThat(count).isEqualTo(1);
    }

    @Test
    void preventsBackdatedAndMismatchedIssuerEvidence() {
        Fixture f = fixture();
        assertThatThrownBy(() -> link(f, f.digest(), f.firstKnowableAt().minusSeconds(5)))
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining("listing mapping precedes");
        assertThatThrownBy(() -> link(f, "b".repeat(64), Instant.now().plusSeconds(5)))
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining("listing evidence digest mismatch");
    }

    @Test
    void verifiedNewsListingAssociationsAreImmutable() {
        Fixture f = fixture();
        link(f, f.digest(), Instant.now().plusSeconds(1));
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE news_event_verified_listing_evidence
                   SET relationship = 'MENTION_ONLY'
                 WHERE event_id = ?
                """, f.eventId()))
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("""
                DELETE FROM news_event_verified_listing_evidence WHERE event_id = ?
                """, f.eventId()))
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining("append-only");
    }
}
