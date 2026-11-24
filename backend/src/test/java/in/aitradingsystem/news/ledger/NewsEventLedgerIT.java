package in.aitradingsystem.news.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import in.aitradingsystem.support.AbstractPostgresIT;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Real-PostgreSQL checks of the MI-PR03 ledger invariants. Receipts and events here are FIXTURE rows that prove the
 * schema rules; they are not evidence about real disclosures.
 */
class NewsEventLedgerIT extends AbstractPostgresIT {
    private static final Instant OBSERVED = Instant.parse("2026-11-24T05:34:12Z");

    @Autowired JdbcClient jdbc;

    private static String hash() {
        return (UUID.randomUUID().toString() + UUID.randomUUID().toString()).replace("-", "");
    }

    private UUID receipt(String source, String hash, String url, Instant firstObserved, Instant fetched) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO news_ingest_receipt (receipt_id, source_id, source_url, title, content_hash,
                                                 published_at, first_observed_at, fetched_at)
                VALUES (:id, :source, :url, 'FIXTURE title', :hash, :published, :observed, :fetched)
                """)
            .param("id", id).param("source", source).param("url", url).param("hash", hash)
            .param("published", OffsetDateTime.ofInstant(OBSERVED.minusSeconds(120), ZoneOffset.UTC))
            .param("observed", OffsetDateTime.ofInstant(firstObserved, ZoneOffset.UTC))
            .param("fetched", OffsetDateTime.ofInstant(fetched, ZoneOffset.UTC))
            .update();
        return id;
    }

    private UUID receipt(String source) {
        return receipt(source, hash(), "https://www.moneycontrol.com/news/fixture-" + UUID.randomUUID() + ".html",
            OBSERVED, OBSERVED.plusSeconds(5));
    }

    private UUID event(String issuer) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO news_canonical_event (event_id, primary_issuer_key, event_class, first_seen_at)
                VALUES (:id, :issuer, 'EARNINGS_RESULT', :seen)
                """)
            .param("id", id).param("issuer", issuer)
            .param("seen", OffsetDateTime.ofInstant(OBSERVED, ZoneOffset.UTC)).update();
        return id;
    }

    private void version(UUID event, int version, Integer correctionOf, boolean retraction) {
        jdbc.sql("""
                INSERT INTO news_event_version (event_id, version, effective_known_at, category, summary,
                                                correction_of_version, retraction)
                VALUES (:event, :version, :known, 'EARNINGS', 'FIXTURE summary', :correction, :retraction)
                """)
            .param("event", event).param("version", version)
            .param("known", OffsetDateTime.ofInstant(OBSERVED.plusSeconds(60), ZoneOffset.UTC))
            .param("correction", correctionOf).param("retraction", retraction).update();
    }

    private void evidence(UUID event, int version, UUID receipt) {
        jdbc.sql("""
                INSERT INTO news_event_evidence (event_id, version, receipt_id, evidence_kind, observed_at)
                VALUES (:event, :version, :receipt, 'PRIMARY_DISCLOSURE', :observed)
                """)
            .param("event", event).param("version", version).param("receipt", receipt)
            .param("observed", OffsetDateTime.ofInstant(OBSERVED, ZoneOffset.UTC)).update();
    }

    @Test
    void duplicateReceiptForTheSameSourceAndContentIsRejected() {
        String h = hash();
        receipt("moneycontrol", h, "https://www.moneycontrol.com/news/dup-" + UUID.randomUUID() + ".html", OBSERVED, OBSERVED);
        assertThat(catchThrowable(() -> receipt("moneycontrol", h,
            "https://www.moneycontrol.com/news/dup2-" + UUID.randomUUID() + ".html", OBSERVED, OBSERVED))).isNotNull();
    }

    @Test
    void receiptsAreNeverUpdatedSoFirstObservationCannotBeRewritten() {
        UUID r = receipt("moneycontrol");
        assertThat(catchThrowable(() -> jdbc.sql("UPDATE news_ingest_receipt SET first_observed_at = now() WHERE receipt_id = :id")
            .param("id", r).update())).isNotNull();
        assertThat(catchThrowable(() -> jdbc.sql("DELETE FROM news_ingest_receipt WHERE receipt_id = :id")
            .param("id", r).update())).isNotNull();
    }

    @Test
    void receiptFromAnUnknownSourceIsRejectedByTheRightsReference() {
        assertThat(catchThrowable(() -> receipt("never-registered-source"))).isNotNull();
    }

    @Test
    void firstObservedCannotPrecedeTheFetchItRecords() {
        assertThat(catchThrowable(() -> receipt("moneycontrol", hash(),
            "https://www.moneycontrol.com/news/order-" + UUID.randomUUID() + ".html",
            OBSERVED.plusSeconds(60), OBSERVED))).isNotNull();
    }

    @Test
    void nonHttpUrlsCannotBeStored() {
        assertThat(catchThrowable(() -> receipt("moneycontrol", hash(), "javascript:void(0)", OBSERVED, OBSERVED))).isNotNull();
    }

    @Test
    void versionsAreAppendOnlyAndACorrectionIsANewVersion() {
        UUID event = event("FIXTURE-ISSUER");
        version(event, 1, null, false);
        version(event, 2, 1, false);
        assertThat(jdbc.sql("SELECT count(*) FROM news_event_version WHERE event_id = :e").param("e", event)
            .query(Long.class).single()).isEqualTo(2);
        assertThat(catchThrowable(() -> jdbc.sql("UPDATE news_event_version SET summary = 'rewritten' WHERE event_id = :e AND version = 1")
            .param("e", event).update())).isNotNull();
        assertThat(catchThrowable(() -> jdbc.sql("DELETE FROM news_event_version WHERE event_id = :e AND version = 1")
            .param("e", event).update())).isNotNull();
    }

    @Test
    void correctionMustReferenceAnEarlierVersion() {
        UUID event = event("FIXTURE-ISSUER-2");
        version(event, 1, null, false);
        assertThat(catchThrowable(() -> version(event, 2, 2, false))).isNotNull();
    }

    @Test
    void retractionIsRecordedAsAVersionWithoutDeletingEarlierEvidence() {
        UUID event = event("FIXTURE-ISSUER-3");
        UUID r = receipt("moneycontrol");
        version(event, 1, null, false);
        evidence(event, 1, r);
        version(event, 2, 1, true);
        assertThat(jdbc.sql("SELECT retraction FROM news_event_version WHERE event_id = :e AND version = 2")
            .param("e", event).query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM news_event_evidence WHERE event_id = :e").param("e", event)
            .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void republicationsClusterUnderOneEventWhileEachReceiptIsKept() {
        UUID event = event("FIXTURE-ISSUER-4");
        UUID primary = receipt("sebi");
        UUID syndicated = receipt("moneycontrol");
        version(event, 1, null, false);
        evidence(event, 1, primary);
        evidence(event, 1, syndicated);
        assertThat(jdbc.sql("SELECT count(*) FROM news_canonical_event WHERE event_id = :e").param("e", event)
            .query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM news_event_evidence WHERE event_id = :e").param("e", event)
            .query(Long.class).single()).isEqualTo(2);
    }
}
