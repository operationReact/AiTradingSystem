package in.aitradingsystem.news;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

/**
 * Customer-safe immutable event history with full currently display-authorized
 * source provenance. Source approval is evaluated in every SQL statement.
 *
 * <p>No current DISPLAY right means no headline, URL, version or event existence
 * can be exposed. Source timestamps remain immutable; this never turns publisher
 * claims into verified exchange event times or inferred issuer mappings.
 */
@Repository
public class NewsEventEvidenceDetailRepository {
    static final int MAX_VERSIONS = 20;
    static final int MAX_EVIDENCE_PER_VERSION = 25;

    private final JdbcTemplate jdbc;

    public NewsEventEvidenceDetailRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public EventDetail detail(UUID eventId) {
        if (eventId == null) throw new IllegalArgumentException("eventId is required");

        List<EventHeader> headers = jdbc.query("""
                SELECT e.event_id, e.event_class, e.status, e.current_version,
                       e.first_seen_at, v.effective_known_at, v.retraction
                  FROM news_canonical_event e
                  JOIN news_event_version v
                    ON v.event_id = e.event_id AND v.version = e.current_version
                 WHERE e.event_id = ?
                   AND e.first_seen_at <= CURRENT_TIMESTAMP
                   AND v.effective_known_at <= CURRENT_TIMESTAMP
                   AND EXISTS (
                       SELECT 1 FROM news_event_evidence proof
                       JOIN news_ingest_receipt r ON r.receipt_id = proof.receipt_id
                       JOIN news_source_policy p ON p.source_id = r.source_id
                       WHERE proof.event_id = e.event_id AND proof.version = v.version
                         AND p.license_state = 'APPROVED'
                         AND 'DISPLAY' = ANY(p.permitted_uses)
                         AND p.effective_from <= CURRENT_TIMESTAMP
                         AND (p.effective_to IS NULL OR CURRENT_TIMESTAMP < p.effective_to)
                         AND r.first_observed_at <= CURRENT_TIMESTAMP
                         AND proof.observed_at <= CURRENT_TIMESTAMP
                   )
                """, (rs, row) -> new EventHeader(
                    rs.getObject("event_id", UUID.class), rs.getString("event_class"),
                    rs.getBoolean("retraction") ? "RETRACTED" : rs.getString("status"),
                    rs.getInt("current_version"),
                    rs.getTimestamp("first_seen_at").toInstant(),
                    rs.getTimestamp("effective_known_at").toInstant()), eventId);
        if (headers.isEmpty()) {
            // Respond identically for absent, future-known and source-without-rights.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Market event not available");
        }
        EventHeader header = headers.getFirst();

        List<VersionRow> versions = jdbc.query("""
                SELECT v.version, v.category, v.effective_known_at,
                       v.retraction, v.correction_of_version
                  FROM news_event_version v
                 WHERE v.event_id = ?
                   AND v.version <= ?
                   AND v.effective_known_at <= CURRENT_TIMESTAMP
                   AND EXISTS (
                       SELECT 1 FROM news_event_evidence proof
                       JOIN news_ingest_receipt r ON r.receipt_id = proof.receipt_id
                       JOIN news_source_policy p ON p.source_id = r.source_id
                       WHERE proof.event_id = v.event_id AND proof.version = v.version
                         AND p.license_state = 'APPROVED'
                         AND 'DISPLAY' = ANY(p.permitted_uses)
                         AND p.effective_from <= CURRENT_TIMESTAMP
                         AND (p.effective_to IS NULL OR CURRENT_TIMESTAMP < p.effective_to)
                         AND r.first_observed_at <= CURRENT_TIMESTAMP
                         AND proof.observed_at <= CURRENT_TIMESTAMP
                   )
                 ORDER BY v.version DESC
                 LIMIT ?
                """, (rs, row) -> new VersionRow(rs.getInt("version"),
                    rs.getString("category"),
                    rs.getTimestamp("effective_known_at").toInstant(),
                    rs.getBoolean("retraction"),
                    (Integer) rs.getObject("correction_of_version")),
                eventId, header.currentVersion(), MAX_VERSIONS + 1);

        boolean versionsTruncated = versions.size() > MAX_VERSIONS;
        List<EventVersion> visibleVersions = new ArrayList<>();
        for (VersionRow version : versions.stream().limit(MAX_VERSIONS).toList()) {
            List<Evidence> sourceEvidence = jdbc.query("""
                    SELECT r.receipt_id, r.source_id, p.publisher, r.title,
                           r.source_url, r.published_at, r.first_observed_at,
                           proof.observed_at, proof.evidence_kind
                      FROM news_event_evidence proof
                      JOIN news_ingest_receipt r ON r.receipt_id = proof.receipt_id
                      JOIN news_source_policy p ON p.source_id = r.source_id
                     WHERE proof.event_id = ? AND proof.version = ?
                       AND p.license_state = 'APPROVED'
                       AND 'DISPLAY' = ANY(p.permitted_uses)
                       AND p.effective_from <= CURRENT_TIMESTAMP
                       AND (p.effective_to IS NULL OR CURRENT_TIMESTAMP < p.effective_to)
                       AND r.first_observed_at <= CURRENT_TIMESTAMP
                       AND proof.observed_at <= CURRENT_TIMESTAMP
                     ORDER BY proof.observed_at ASC, r.receipt_id ASC
                     LIMIT ?
                    """, (rs, row) -> new Evidence(
                        rs.getObject("receipt_id", UUID.class),
                        rs.getString("source_id"), rs.getString("publisher"),
                        rs.getString("title"), rs.getString("source_url"),
                        rs.getTimestamp("published_at") == null ? null
                            : rs.getTimestamp("published_at").toInstant(),
                        rs.getTimestamp("first_observed_at").toInstant(),
                        rs.getTimestamp("observed_at").toInstant(),
                        rs.getString("evidence_kind")),
                    eventId, version.version(), MAX_EVIDENCE_PER_VERSION + 1);
            if (sourceEvidence.isEmpty()) continue;
            boolean evidenceTruncated = sourceEvidence.size() > MAX_EVIDENCE_PER_VERSION;
            visibleVersions.add(new EventVersion(
                version.version(), version.category(), version.effectiveKnownAt(),
                version.retraction(), version.correctionOfVersion(),
                evidenceTruncated, List.copyOf(
                    sourceEvidence.stream().limit(MAX_EVIDENCE_PER_VERSION).toList())));
        }
        if (visibleVersions.isEmpty() || visibleVersions.getFirst().version() != header.currentVersion()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Market event not available");
        }
        return new EventDetail(header.eventId(), header.eventClass(), header.eventStatus(),
            header.currentVersion(), header.firstSeenAt(), header.currentKnownAt(),
            "UNVERIFIED", versionsTruncated, List.copyOf(visibleVersions));
    }

    private record EventHeader(UUID eventId, String eventClass, String eventStatus,
                               int currentVersion, Instant firstSeenAt, Instant currentKnownAt) {}

    private record VersionRow(int version, String category, Instant effectiveKnownAt,
                              boolean retraction, Integer correctionOfVersion) {}

    public record Evidence(UUID receiptId, String sourceId, String publisher,
                           String title, String sourceUrl, Instant publishedAt,
                           Instant firstObservedAt, Instant observedAt, String evidenceKind) {}

    public record EventVersion(int version, String category, Instant effectiveKnownAt,
                               boolean retraction, Integer correctionOfVersion,
                               boolean evidenceTruncated, List<Evidence> evidence) {}

    public record EventDetail(UUID eventId, String eventClass, String eventStatus,
                              int currentVersion, Instant firstSeenAt, Instant currentKnownAt,
                              String instrumentResolutionStatus, boolean versionsTruncated,
                              List<EventVersion> versions) {}
}
