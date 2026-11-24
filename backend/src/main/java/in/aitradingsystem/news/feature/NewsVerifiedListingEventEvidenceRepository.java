package in.aitradingsystem.news.feature;

import java.sql.Date;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Point-in-time, source-rights-filtered evidence for an ALREADY independently
 * verified exchange listing UUID. This does not resolve a mutable ticker to
 * a listing, invoke Gemini, or score/authorize any trading decision.
 */
@Repository
public class NewsVerifiedListingEventEvidenceRepository {
    public static final int MAX_EVIDENCE = 100;
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private final JdbcTemplate jdbc;

    public NewsVerifiedListingEventEvidenceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Returns only explicitly attested AFFECTED_ISSUER relationships known by
     * asOf. When an event was corrected later, selects the latest event version
     * knowable at the historic cutoff, never the current event pointer/status.
     *
     * <p>Publisher policy must have been recorded by asOf AND remain currently
     * permitted; a retroactive current approval cannot rewrite past research.
     * Failure to establish a listing or publisher permission returns no proof.
     */
    public List<Evidence> verifiedAsOf(UUID listingId, Instant asOf) {
        Objects.requireNonNull(listingId, "listingId");
        Objects.requireNonNull(asOf, "asOf");
        if (asOf.isAfter(Instant.now())) {
            throw new IllegalArgumentException("Future news decision cutoffs are not verifiable");
        }
        OffsetDateTime cutoff = OffsetDateTime.ofInstant(asOf, ZoneOffset.UTC);
        Date sessionDate = Date.valueOf(asOf.atZone(IST).toLocalDate());
        List<Evidence> rows = jdbc.query("""
                SELECT map.event_id, map.version, map.listing_id,
                       r.receipt_id, r.source_id, r.source_url, r.title,
                       r.published_at, r.first_observed_at, r.fetched_at,
                       p.publisher, map.mapping_first_known_at,
                       map.listing_identity_digest, proof.evidence_kind,
                       v.category, v.retraction
                  FROM news_event_verified_listing_evidence map
                  JOIN golden_listing_identity listing ON listing.listing_id = map.listing_id
                  JOIN news_event_version v
                    ON v.event_id = map.event_id AND v.version = map.version
                  JOIN news_event_evidence proof
                    ON proof.event_id = map.event_id AND proof.version = map.version
                   AND proof.receipt_id = map.receipt_id
                  JOIN news_ingest_receipt r ON r.receipt_id = map.receipt_id
                  JOIN news_source_policy p ON p.source_id = r.source_id
                 WHERE map.listing_id = ?
                   AND map.relationship = 'AFFECTED_ISSUER'
                   AND map.mapping_first_known_at <= ?
                   AND v.effective_known_at <= ?
                   AND v.version = (
                       SELECT MAX(v2.version) FROM news_event_version v2
                        WHERE v2.event_id = v.event_id
                          AND v2.effective_known_at <= ?
                   )
                   AND proof.observed_at <= ?
                   AND r.first_observed_at <= ?
                   AND r.fetched_at <= ?
                   AND (r.published_at IS NULL OR r.published_at <= ?)
                   AND listing.created_at <= ?
                   AND listing.listed_from <= ?
                   AND (listing.delisted_on IS NULL OR listing.delisted_on > ?)
                   AND p.license_state = 'APPROVED'
                   AND 'INGEST' = ANY(p.permitted_uses)
                   AND 'RETAIN' = ANY(p.permitted_uses)
                   AND 'DISPLAY' = ANY(p.permitted_uses)
                   AND 'NLP_TRANSFER' = ANY(p.permitted_uses)
                   AND p.updated_at <= ?
                   AND p.effective_from <= ?
                   AND p.effective_from <= CURRENT_TIMESTAMP
                   AND (p.effective_to IS NULL OR ? < p.effective_to)
                   AND (p.effective_to IS NULL OR CURRENT_TIMESTAMP < p.effective_to)
                 ORDER BY map.mapping_first_known_at DESC,
                          map.event_id, r.receipt_id
                 LIMIT ?
                """, (rs, n) -> new Evidence(
                    rs.getObject("event_id", UUID.class),
                    rs.getInt("version"),
                    rs.getObject("listing_id", UUID.class),
                    rs.getObject("receipt_id", UUID.class),
                    rs.getString("source_id"),
                    rs.getString("publisher"),
                    rs.getString("source_url"),
                    rs.getString("title"),
                    rs.getTimestamp("published_at") == null ? null
                        : rs.getTimestamp("published_at").toInstant(),
                    rs.getTimestamp("first_observed_at").toInstant(),
                    rs.getTimestamp("fetched_at").toInstant(),
                    rs.getTimestamp("mapping_first_known_at").toInstant(),
                    rs.getString("listing_identity_digest"),
                    rs.getString("evidence_kind"),
                    rs.getString("category"),
                    rs.getBoolean("retraction")),
                listingId, cutoff, cutoff, cutoff, cutoff, cutoff, cutoff, cutoff,
                cutoff, sessionDate, sessionDate, cutoff, cutoff, cutoff,
                MAX_EVIDENCE + 1);
        if (rows.size() > MAX_EVIDENCE) {
            throw new IllegalStateException("Verified news listing evidence exceeds bounded context");
        }
        return List.copyOf(rows);
    }

    public record Evidence(UUID eventId, int version, UUID listingId, UUID receiptId,
                           String sourceId, String publisher, String sourceUrl, String title,
                           Instant publishedAt, Instant firstObservedAt, Instant fetchedAt,
                           Instant mappingFirstKnownAt, String listingIdentityDigest,
                           String evidenceKind, String category, boolean retraction) {}
}
