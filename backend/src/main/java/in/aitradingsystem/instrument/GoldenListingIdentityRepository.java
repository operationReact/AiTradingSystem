package in.aitradingsystem.instrument;

import java.sql.Date;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * D20-02 historical research identity resolver; never an order routing service.
 *
 * A vendor token is not a durable security identifier. The alias must be
 * independently verified and recorded with its valid-time period and the time
 * its source information first became knowable. No guessing from today's
 * instrument_master, company names, ISINs or ticker similarity is allowed.
 *
 * Ambiguous aliases fail closed. The current broker feed and market_candle
 * primary key are deliberately untouched until a reviewed data migration.
 */
@Repository
public class GoldenListingIdentityRepository {
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private final JdbcTemplate jdbc;

    public GoldenListingIdentityRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Resolve a provider token as it was valid AND first available at the
     * historical decision instant. A delisted security can still be resolved
     * for an earlier date, but never for dates outside its listing episode.
     *
     * @throws IllegalStateException when conflicting source evidence identifies
     *         more than one distinct listing for the same provider alias.
     */
    public Optional<UUID> resolveAsOf(
            String provider, String exchange, String providerToken, Instant decisionAt) {
        if (provider == null || provider.isBlank()
                || providerToken == null || providerToken.isBlank()
                || !("NSE".equals(exchange) || "BSE".equals(exchange))
                || decisionAt == null) {
            throw new IllegalArgumentException("provider, exchange, token and decision time required");
        }
        var timestamp = OffsetDateTime.ofInstant(decisionAt, ZoneOffset.UTC);
        var sessionDate = Date.valueOf(decisionAt.atZone(IST).toLocalDate());
        List<UUID> matches = jdbc.query("""
                SELECT DISTINCT a.listing_id
                  FROM golden_provider_symbol_alias a
                  JOIN golden_listing_identity l ON l.listing_id = a.listing_id
                 WHERE a.provider = ?
                   AND a.exchange = ?
                   AND l.exchange = a.exchange
                   AND a.provider_token = ?
                   AND a.valid_from <= ?
                   AND (a.valid_to IS NULL OR a.valid_to > ?)
                   AND a.first_available_at <= ?
                   AND l.listed_from <= ?
                   AND (l.delisted_on IS NULL OR l.delisted_on > ?)
                 ORDER BY a.listing_id
                 LIMIT 2
                """,
                (row, ignored) -> row.getObject("listing_id", UUID.class),
                provider, exchange, providerToken, timestamp, timestamp,
                timestamp, sessionDate, sessionDate);
        if (matches.size() > 1) {
            throw new IllegalStateException("ambiguous historical provider listing identity");
        }
        return matches.isEmpty() ? Optional.empty() : Optional.of(matches.get(0));
    }
}
