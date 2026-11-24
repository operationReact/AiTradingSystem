package in.aitradingsystem.news;

import static org.assertj.core.api.Assertions.assertThat;

import in.aitradingsystem.news.policy.NewsSourcePolicyTestSupport;
import in.aitradingsystem.support.AbstractPostgresIT;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Real PostgreSQL V12/V128/V129 projection invariants with synthetic publisher records. */
class NewsReceiptEventProjectorIT extends AbstractPostgresIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired JdbcClient client;
    @Autowired NewsReceiptEventProjector projector;

    private static String source() {
        return "fixture-" + UUID.randomUUID();
    }

    private String rss(String source, Instant published, Instant fetched, String title) {
        String hash = UUID.randomUUID().toString().replace("-", "") +
            UUID.randomUUID().toString().replace("-", "");
        jdbc.update("""
                INSERT INTO news_item (source, publisher, source_url, published_at, fetched_at,
                                       title, normalized_content, content_hash)
                VALUES (?, ?, ?, ?, ?, ?, 'FICTIONAL TEST BODY', ?)
                """, source, "FICTIONAL TEST PUBLISHER",
            "https://example.test/fixture/" + UUID.randomUUID(),
            published == null ? null : Timestamp.from(published),
            Timestamp.from(fetched), title, hash);
        return hash;
    }

    private long receipts(String source) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM news_ingest_receipt WHERE source_id = ?
                """, Long.class, source);
    }

    @Test
    void approvalProjectsImmutableReceiptVersionAndEvidenceWithoutBackdating() {
        String publisher = source();
        NewsSourcePolicyTestSupport.approveFixture(client, publisher);
        Instant fetched = Instant.now().minusSeconds(30).truncatedTo(ChronoUnit.MICROS);
        Instant published = fetched.minusSeconds(3600);
        String hash = rss(publisher, published, fetched, "Synthetic quarterly announcement");

        var result = projector.projectSource(publisher, 20);

        assertThat(result.scanned()).isEqualTo(1);
        assertThat(result.inserted()).isEqualTo(1);
        assertThat(result.failures()).isZero();
        var linked = jdbc.queryForMap("""
                SELECT r.source_id, r.content_hash, r.published_at, r.first_observed_at,
                       r.fetched_at, e.event_class, e.primary_issuer_key,
                       v.effective_known_at, v.category, v.analysis_provider,
                       proof.evidence_kind, proof.observed_at
                  FROM news_ingest_receipt r
                  JOIN news_event_evidence proof ON proof.receipt_id = r.receipt_id
                  JOIN news_event_version v
                    ON v.event_id = proof.event_id AND v.version = proof.version
                  JOIN news_canonical_event e ON e.event_id = v.event_id
                 WHERE r.source_id = ? AND r.content_hash = ?
                """, publisher, hash);
        assertThat(linked.get("source_id")).isEqualTo(publisher);
        assertThat(((Timestamp) linked.get("published_at")).toInstant()).isEqualTo(published);
        assertThat(((Timestamp) linked.get("first_observed_at")).toInstant()).isEqualTo(fetched);
        assertThat(((Timestamp) linked.get("effective_known_at")).toInstant()).isEqualTo(fetched);
        assertThat(((Timestamp) linked.get("observed_at")).toInstant()).isEqualTo(fetched);
        assertThat(linked.get("event_class")).isEqualTo("UNCLASSIFIED");
        assertThat(linked.get("category")).isEqualTo("UNCLASSIFIED");
        assertThat(linked.get("evidence_kind")).isEqualTo("OTHER");
        assertThat(linked.get("primary_issuer_key")).isNull();
        assertThat(linked.get("analysis_provider")).isNull();

        // Reprocessing does not create another receipt, version or event.
        var replay = projector.projectSource(publisher, 20);
        assertThat(replay.inserted()).isZero();
        assertThat(receipts(publisher)).isEqualTo(1);
    }

    @Test
    void unapprovedAndRevokedSourceNeverEntersEventLedger() {
        String unknown = source();
        Instant fetched = Instant.now().minusSeconds(30);
        rss(unknown, fetched.minusSeconds(100), fetched, "Unapproved fixture");
        assertThat(projector.projectSource(unknown, 20).scanned()).isZero();
        assertThat(receipts(unknown)).isZero();

        String revoked = source();
        NewsSourcePolicyTestSupport.approveFixture(client, revoked);
        rss(revoked, fetched.minusSeconds(100), fetched, "Fixture before revocation");
        assertThat(projector.projectSource(revoked, 20).inserted()).isEqualTo(1);

        client.sql("UPDATE news_source_policy SET license_state = 'REVOKED' WHERE source_id = :id")
            .param("id", revoked).update();
        rss(revoked, fetched.minusSeconds(90), fetched, "Fixture after revocation");
        assertThat(projector.projectSource(revoked, 20).scanned()).isZero();
        assertThat(receipts(revoked)).isEqualTo(1);
    }

    @Test
    void absentPublicationDateDoesNotCauseInventedHistoricalKnownAt() {
        String publisher = source();
        NewsSourcePolicyTestSupport.approveFixture(client, publisher);
        Instant fetched = Instant.now().minusSeconds(30).truncatedTo(ChronoUnit.MICROS);
        String hash = rss(publisher, null, fetched, "No publisher clock");
        assertThat(projector.projectSource(publisher, 20).inserted()).isEqualTo(1);
        var result = jdbc.queryForMap("""
                SELECT published_at, first_observed_at FROM news_ingest_receipt
                 WHERE source_id = ? AND content_hash = ?
                """, publisher, hash);
        assertThat(result.get("published_at")).isNull();
        assertThat(((Timestamp) result.get("first_observed_at")).toInstant()).isEqualTo(fetched);
    }

    @Test
    void rejectsUnboundedBatchRequests() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> projector.projectSource(null, 101))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
