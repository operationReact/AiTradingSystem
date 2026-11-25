package in.aitradingsystem.news.feature;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.aitradingsystem.news.policy.NewsSourcePolicyTestSupport;
import in.aitradingsystem.support.AbstractPostgresIT;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Synthetic source and listing evidence, tested against real V77/V128/V129/V130 PostgreSQL. */
class NewsVerifiedListingEventEvidenceRepositoryIT extends AbstractPostgresIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired JdbcClient client;
    @Autowired NewsVerifiedListingEventEvidenceRepository evidence;

    private record Fixture(UUID listingId, UUID eventId, UUID receiptId, String sourceId,
                           Instant observedAt, Instant mappingKnownAt, Instant decisionAt,
                           String digest) {}

    private Fixture fixture(String relationship) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant sourcePolicyKnown = now.minusSeconds(600);
        Instant listingKnown = now.minusSeconds(500);
        Instant observed = now.minusSeconds(300);
        Instant mappingKnown = now.minusSeconds(120);
        Instant decision = now.minusSeconds(60);
        UUID listing = UUID.randomUUID();
        UUID security = UUID.randomUUID();
        UUID receipt = UUID.randomUUID();
        UUID event = UUID.randomUUID();
        String source = "synthetic-news-" + UUID.randomUUID();
        String digest = "a".repeat(64);
        String hash = UUID.randomUUID().toString().replace("-", "")
            + UUID.randomUUID().toString().replace("-", "");

        NewsSourcePolicyTestSupport.approveFixture(client, source);
        client.sql("""
                UPDATE news_source_policy SET updated_at = :known
                 WHERE source_id = :id
                """)
            .param("known", OffsetDateTime.ofInstant(sourcePolicyKnown, ZoneOffset.UTC))
            .param("id", source).update();

        jdbc.update("""
                INSERT INTO golden_security_identity
                    (security_id, issuer_name, isin_when_verified,
                     identity_source, identity_evidence_digest, created_at)
                VALUES (?, 'Synthetic Legal Entity', 'IN0000000001',
                        'TEST_OFFICIAL_PROVENANCE_ONLY', ?, ?)
                """, security, digest, Timestamp.from(listingKnown));
        jdbc.update("""
                INSERT INTO golden_listing_identity
                    (listing_id, security_id, exchange, listing_series, listed_from,
                     identity_evidence_digest, created_at)
                VALUES (?, ?, 'NSE', 'EQ', ?, ?, ?)
                """, listing, security, Date.valueOf(LocalDate.parse("2019-01-01")),
            digest, Timestamp.from(listingKnown));
        jdbc.update("""
                INSERT INTO news_ingest_receipt
                    (receipt_id, source_id, source_url, title, content_hash,
                     published_at, first_observed_at, fetched_at)
                VALUES (?, ?, ?, 'Synthetic issuer disclosure', ?, ?, ?, ?)
                """, receipt, source, "https://news.example.test/synthetic/" + receipt,
            hash, Timestamp.from(observed.minusSeconds(60)),
            Timestamp.from(observed), Timestamp.from(observed));
        jdbc.update("""
                INSERT INTO news_canonical_event
                    (event_id, event_class, status, first_seen_at, current_version)
                VALUES (?, 'UNCLASSIFIED', 'ACTIVE', ?, 1)
                """, event, Timestamp.from(observed));
        jdbc.update("""
                INSERT INTO news_event_version(event_id, version, effective_known_at, category)
                VALUES (?, 1, ?, 'UNCLASSIFIED')
                """, event, Timestamp.from(observed));
        jdbc.update("""
                INSERT INTO news_event_evidence
                    (event_id, version, receipt_id, evidence_kind, observed_at)
                VALUES (?, 1, ?, 'OTHER', ?)
                """, event, receipt, Timestamp.from(observed));
        jdbc.update("""
                INSERT INTO news_event_verified_listing_evidence
                    (event_id, version, receipt_id, listing_id, relationship,
                     listing_identity_digest, mapping_first_known_at, evidence_method)
                VALUES (?, 1, ?, ?, ?, ?, ?, 'SYNTHETIC_MANUAL_REVIEW')
                """, event, receipt, listing, relationship, digest, Timestamp.from(mappingKnown));

        return new Fixture(listing, event, receipt, source, observed, mappingKnown, decision, digest);
    }

    @Test
    void verifiedSourceAsOfProvesIssuerRelationOnlyAfterMappingExists() {
        Fixture f = fixture("AFFECTED_ISSUER");
        var matched = evidence.verifiedAsOf(f.listingId(), f.decisionAt());
        assertThat(matched).hasSize(1);
        var row = matched.getFirst();
        assertThat(row.eventId()).isEqualTo(f.eventId());
        assertThat(row.version()).isEqualTo(1);
        assertThat(row.listingId()).isEqualTo(f.listingId());
        assertThat(row.sourceId()).isEqualTo(f.sourceId());
        assertThat(row.title()).isEqualTo("Synthetic issuer disclosure");
        assertThat(row.listingIdentityDigest()).isEqualTo(f.digest());
        assertThat(row.firstObservedAt()).isEqualTo(f.observedAt());
        assertThat(row.mappingFirstKnownAt()).isEqualTo(f.mappingKnownAt());
        assertThat(row.retraction()).isFalse();

        // Before the listing association was knowable, there is no proof.
        var historical = evidence.verifiedAsOf(f.listingId(), f.mappingKnownAt().minusSeconds(1));
        assertThat(historical).isEmpty();
    }

    @Test
    void publisherRevocationOrRetroactiveApprovalCannotRewriteHistoricalEvidence() {
        Fixture f = fixture("AFFECTED_ISSUER");
        assertThat(evidence.verifiedAsOf(f.listingId(), f.decisionAt())).hasSize(1);

        client.sql("""
                UPDATE news_source_policy SET license_state='REVOKED', updated_at=now()
                 WHERE source_id=:id
                """).param("id", f.sourceId()).update();
        assertThat(evidence.verifiedAsOf(f.listingId(), f.decisionAt())).isEmpty();

        client.sql("""
                UPDATE news_source_policy SET license_state='APPROVED', updated_at=now()
                 WHERE source_id=:id
                """).param("id", f.sourceId()).update();
        // Approval was only entered after this historical decision timestamp.
        assertThat(evidence.verifiedAsOf(f.listingId(), f.decisionAt())).isEmpty();
    }

    @Test
    void mentionOnlyOrUnrelatedListingNeverBecomesVerifiedImpact() {
        Fixture f = fixture("MENTION_ONLY");
        assertThat(evidence.verifiedAsOf(f.listingId(), f.decisionAt())).isEmpty();
        assertThat(evidence.verifiedAsOf(UUID.randomUUID(), f.decisionAt())).isEmpty();
        assertThatThrownBy(() ->
            evidence.verifiedAsOf(f.listingId(), Instant.now().plusSeconds(120)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void historicalViewUsesLatestVersionAvailableAtThatCutoff() {
        Fixture f = fixture("AFFECTED_ISSUER");
        Instant correctionAt = f.decisionAt().plusSeconds(15);
        Instant afterCorrection = correctionAt.plusSeconds(5);

        jdbc.update("""
                INSERT INTO news_event_version
                    (event_id, version, effective_known_at, category,
                     correction_of_version, retraction)
                VALUES (?, 2, ?, 'CORRECTION', 1, true)
                """, f.eventId(), Timestamp.from(correctionAt));
        jdbc.update("""
                INSERT INTO news_event_evidence
                    (event_id, version, receipt_id, evidence_kind, observed_at)
                VALUES (?, 2, ?, 'OTHER', ?)
                """, f.eventId(), f.receiptId(), Timestamp.from(correctionAt));
        jdbc.update("""
                INSERT INTO news_event_verified_listing_evidence
                    (event_id, version, receipt_id, listing_id, relationship,
                     listing_identity_digest, mapping_first_known_at, evidence_method)
                VALUES (?, 2, ?, ?, 'AFFECTED_ISSUER', ?, ?, 'SYNTHETIC_CORRECTION')
                """, f.eventId(), f.receiptId(), f.listingId(), f.digest(),
            Timestamp.from(correctionAt));

        assertThat(evidence.verifiedAsOf(f.listingId(), f.decisionAt())).singleElement()
            .satisfies(row -> {
                assertThat(row.version()).isEqualTo(1);
                assertThat(row.retraction()).isFalse();
            });
        assertThat(evidence.verifiedAsOf(f.listingId(), afterCorrection)).singleElement()
            .satisfies(row -> {
                assertThat(row.version()).isEqualTo(2);
                assertThat(row.retraction()).isTrue();
                assertThat(row.category()).isEqualTo("CORRECTION");
            });
    }
}
