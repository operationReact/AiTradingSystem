package in.aitradingsystem.marketdata;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Read-only D20-05B adapter for permanent V77 listing episodes.
 * Does not infer an old identity from today's ticker or broker token.
 * An unregistered listing is UNKNOWN, never auto-created.
 */
@Repository
public class GoldenJdbcHistoricalListingEvidence implements GoldenHistoricalListingEvidence {
    private final JdbcTemplate jdbc;

    public GoldenJdbcHistoricalListingEvidence(JdbcTemplate jdbc) {
        this.jdbc = java.util.Objects.requireNonNull(jdbc);
    }

    @Override
    public ListingEpisode resolve(UUID listingId) {
        if (listingId == null) throw new IllegalArgumentException("listing ID required");
        List<ListingEpisode> matching = jdbc.query("""
                SELECT listing_id, exchange, listed_from, delisted_on,
                       identity_evidence_digest
                  FROM golden_listing_identity
                 WHERE listing_id = ?
                """, (rs, i) -> new ListingEpisode(
                        rs.getObject("listing_id", UUID.class),
                        rs.getString("exchange"),
                        rs.getDate("listed_from").toLocalDate(),
                        rs.getDate("delisted_on") == null ? null :
                            rs.getDate("delisted_on").toLocalDate(),
                        rs.getString("identity_evidence_digest")), listingId);
        return matching.size() == 1 ? matching.get(0) : null;
    }
}
