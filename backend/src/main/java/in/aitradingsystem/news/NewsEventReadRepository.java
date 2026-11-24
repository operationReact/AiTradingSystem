package in.aitradingsystem.news;

import in.aitradingsystem.common.PageResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Rights-filtered, read-only projection of the immutable news event ledger.
 * The current event version is shown only when its known-at time is not future.
 * At least one evidence source must have current DISPLAY permission; an internal
 * ledger receipt is not itself a license to redistribute publisher information.
 */
@Repository
public class NewsEventReadRepository {
    private static final String FROM = """
            FROM news_canonical_event e
            JOIN news_event_version v
              ON v.event_id = e.event_id AND v.version = e.current_version
            JOIN LATERAL (
                SELECT r.source_id, r.source_url, r.title, r.published_at,
                       r.first_observed_at, p.publisher
                  FROM news_event_evidence proof
                  JOIN news_ingest_receipt r ON r.receipt_id = proof.receipt_id
                  JOIN news_source_policy p ON p.source_id = r.source_id
                 WHERE proof.event_id = e.event_id AND proof.version = v.version
                   AND p.license_state = 'APPROVED'
                   AND 'DISPLAY' = ANY(p.permitted_uses)
                   AND p.effective_from <= CURRENT_TIMESTAMP
                   AND (p.effective_to IS NULL OR CURRENT_TIMESTAMP < p.effective_to)
                   AND proof.observed_at <= CURRENT_TIMESTAMP
                   AND r.first_observed_at <= CURRENT_TIMESTAMP
                 ORDER BY proof.observed_at ASC, r.receipt_id ASC
                 LIMIT 1
            ) src ON TRUE
           WHERE e.first_seen_at <= CURRENT_TIMESTAMP
             AND v.effective_known_at <= CURRENT_TIMESTAMP
            """;

    private final JdbcTemplate jdbc;

    public NewsEventReadRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public PageResponse<EventCard> events(int page, int size, String sourceId) {
        if (page < 0 || page > 200 || size < 1 || size > 50
            || (sourceId != null && sourceId.length() > 80)) {
            throw new IllegalArgumentException("Invalid bounded news event query");
        }
        String sourceFilter = sourceId == null || sourceId.isBlank()
            ? "" : " AND src.source_id = ?";
        List<Object> args = new ArrayList<>();
        if (!sourceFilter.isEmpty()) args.add(sourceId.trim());
        Long count = jdbc.queryForObject("SELECT count(*) " + FROM + sourceFilter,
            Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((long) page * size);
        List<EventCard> items = jdbc.query("""
                SELECT e.event_id, e.current_version, e.event_class, e.status,
                       v.category, v.effective_known_at, v.retraction,
                       src.title, src.source_id, src.publisher, src.source_url,
                       src.published_at, src.first_observed_at
                """ + FROM + sourceFilter + """
                ORDER BY e.first_seen_at DESC, e.event_id DESC
                LIMIT ? OFFSET ?
                """, (rs, index) -> new EventCard(
                    rs.getObject("event_id", UUID.class),
                    rs.getInt("current_version"),
                    rs.getString("title"), rs.getString("event_class"),
                    rs.getString("category"),
                    rs.getBoolean("retraction") ? "RETRACTED" : rs.getString("status"),
                    rs.getString("source_id"), rs.getString("publisher"),
                    rs.getString("source_url"),
                    rs.getTimestamp("published_at") == null ? null
                        : rs.getTimestamp("published_at").toInstant(),
                    rs.getTimestamp("first_observed_at").toInstant(),
                    rs.getTimestamp("effective_known_at").toInstant(),
                    "UNVERIFIED"),
                pageArgs.toArray());
        return PageResponse.of(List.copyOf(items), page, size, count == null ? 0 : count);
    }

    /**
     * An event card does not imply issuer linkage or sentiment. The original
     * publisher headline is shown; no model-generated number or stock signal.
     */
    public record EventCard(
        UUID eventId, int version, String headline, String eventClass,
        String category, String eventStatus, String sourceId, String publisher,
        String sourceUrl, Instant publishedAt, Instant firstObservedAt,
        Instant effectiveKnownAt, String instrumentResolutionStatus) {}
}
