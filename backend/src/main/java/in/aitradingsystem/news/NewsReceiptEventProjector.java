package in.aitradingsystem.news;

import in.aitradingsystem.news.policy.NewsSourcePolicy.Use;
import in.aitradingsystem.news.policy.NewsSourcePolicyService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * First-stage, append-only evidence projection: an approved RSS news_item becomes
 * an immutable receipt and one UNCLASSIFIED, unlinked event version. The source is
 * never re-fetched here, and no AI model is invoked. This is not cross-publisher
 * event clustering or issuer verification; both are later independently gated steps.
 *
 * <p>Publication time is only a publisher claim; firstObservedAt/effectiveKnownAt
 * conservatively use the original stored RSS fetchedAt and are never backdated.
 */
@Service
public class NewsReceiptEventProjector {
    private static final Logger log = LoggerFactory.getLogger(NewsReceiptEventProjector.class);
    private static final int MAX_BATCH = 100;
    private final JdbcTemplate jdbc;
    private final NewsSourcePolicyService rights;
    private final TransactionTemplate transaction;

    public NewsReceiptEventProjector(
        JdbcTemplate jdbc, NewsSourcePolicyService rights,
        PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.rights = rights;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public RunSummary runOnce() {
        return projectSource(null, MAX_BATCH);
    }

    /** Optional source restriction is useful for bounded diagnostics and isolated tests. */
    public RunSummary projectSource(String sourceId, int requestedLimit) {
        if (requestedLimit < 1 || requestedLimit > MAX_BATCH) {
            throw new IllegalArgumentException("RSS receipt projection limit must be 1.." + MAX_BATCH);
        }
        List<Row> candidates = jdbc.query("""
                SELECT n.source, n.source_url, n.title, n.content_hash,
                       n.published_at, n.fetched_at
                  FROM news_item n
                  JOIN news_source_policy p ON p.source_id = n.source
                 WHERE n.fetched_at <= CURRENT_TIMESTAMP
                   AND p.license_state = 'APPROVED'
                   AND 'INGEST' = ANY(p.permitted_uses)
                   AND 'RETAIN' = ANY(p.permitted_uses)
                   AND p.effective_from <= CURRENT_TIMESTAMP
                   AND (p.effective_to IS NULL OR CURRENT_TIMESTAMP < p.effective_to)
                   AND (? IS NULL OR n.source = ?)
                   AND NOT EXISTS (
                       SELECT 1 FROM news_ingest_receipt r
                        WHERE r.source_id = n.source AND r.content_hash = n.content_hash
                   )
                 ORDER BY n.fetched_at ASC, n.id ASC
                 LIMIT ?
                """, (rs, index) -> new Row(
                    rs.getString("source"), rs.getString("source_url"),
                    rs.getString("title"), rs.getString("content_hash"),
                    rs.getTimestamp("published_at") == null ? null
                        : rs.getTimestamp("published_at").toInstant(),
                    rs.getTimestamp("fetched_at").toInstant()),
                sourceId, sourceId, requestedLimit);

        int inserted = 0;
        int duplicates = 0;
        int rightsBlocked = 0;
        int failures = 0;
        for (Row article : candidates) {
            try {
                Outcome outcome = transaction.execute(status -> insertOne(article));
                if (outcome == Outcome.INSERTED) inserted++;
                else if (outcome == Outcome.DUPLICATE) duplicates++;
                else rightsBlocked++;
            } catch (RuntimeException ex) {
                // The transaction rolls back receipt + event/version/evidence atomically.
                failures++;
                log.warn("RSS evidence projection failed for source {}", article.source());
            }
        }
        return new RunSummary(candidates.size(), inserted, duplicates, rightsBlocked, failures);
    }

    private Outcome insertOne(Row article) {
        // Recheck inside the transaction: permission may have changed since batch selection.
        Instant at = Instant.now();
        if (!rights.permitted(article.source(), Use.INGEST, at)
            || !rights.permitted(article.source(), Use.RETAIN, at)) {
            return Outcome.RIGHTS_BLOCKED;
        }
        UUID receiptId = UUID.randomUUID();
        List<UUID> newReceipt = jdbc.query("""
                INSERT INTO news_ingest_receipt
                    (receipt_id, source_id, source_url, title, content_hash, published_at,
                     first_observed_at, fetched_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (source_id, content_hash) DO NOTHING
                RETURNING receipt_id
                """, (rs, index) -> rs.getObject("receipt_id", UUID.class),
                receiptId, article.source(), article.sourceUrl(), article.title(),
                article.contentHash(),
                article.publishedAt() == null ? null : Timestamp.from(article.publishedAt()),
                Timestamp.from(article.fetchedAt()), Timestamp.from(article.fetchedAt()));
        if (newReceipt.isEmpty()) return Outcome.DUPLICATE;

        UUID eventId = UUID.randomUUID();
        // Deliberately unclassified: title-only RSS must not manufacture issuer or
        // official filing class; cross-source clustering and Gemini are separate.
        jdbc.update("""
                INSERT INTO news_canonical_event
                    (event_id, event_class, status, first_seen_at, current_version)
                VALUES (?, 'UNCLASSIFIED', 'ACTIVE', ?, 1)
                """, eventId, Timestamp.from(article.fetchedAt()));
        jdbc.update("""
                INSERT INTO news_event_version
                    (event_id, version, effective_known_at, category)
                VALUES (?, 1, ?, 'UNCLASSIFIED')
                """, eventId, Timestamp.from(article.fetchedAt()));
        jdbc.update("""
                INSERT INTO news_event_evidence
                    (event_id, version, receipt_id, evidence_kind, observed_at)
                VALUES (?, 1, ?, 'OTHER', ?)
                """, eventId, receiptId, Timestamp.from(article.fetchedAt()));
        return Outcome.INSERTED;
    }

    private enum Outcome { INSERTED, DUPLICATE, RIGHTS_BLOCKED }

    private record Row(String source, String sourceUrl, String title, String contentHash,
                       Instant publishedAt, Instant fetchedAt) {}

    public record RunSummary(int scanned, int inserted, int duplicates,
                             int rightsBlocked, int failures) {}
}
