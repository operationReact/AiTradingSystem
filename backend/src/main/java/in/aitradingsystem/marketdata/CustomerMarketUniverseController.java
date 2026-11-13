package in.aitradingsystem.marketdata;

import in.aitradingsystem.instrument.InstrumentMasterRepository;
import in.aitradingsystem.user.CustomerIdentityStore;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * First read-only, authenticated Markets category contract: the ACTIVE cash
 * equity catalogue is the universe, never a fixed Nifty 50/500 subset.
 *
 * Membership and cap/industry classifications are source-versioned data and
 * cannot be invented from the Angel One scrip master. Missing mappings are
 * reported explicitly rather than silently reducing the searchable universe.
 */
@RestController
@RequestMapping("/api/customer/market-data")
public final class CustomerMarketUniverseController {
    private final JdbcTemplate jdbc;
    private final CustomerIdentityStore identities;

    public CustomerMarketUniverseController(JdbcTemplate jdbc,
            CustomerIdentityStore identities) {
        this.jdbc = jdbc;
        this.identities = identities;
    }

    public record ExchangeFacet(String exchange, long activeListings) {}
    public record IndexFacet(String universeCode, long officialMembers,
                             long matchedActiveNseStocks, LocalDate sourceAsOf,
                             String membershipSource) {}
    public record ClassificationState(String family, String state,
                                      String reason) {}
    public record Facets(String contractVersion, Instant generatedAt,
                         UUID catalogueVersion, Instant catalogueActivatedAt,
                         long totalActiveCashEquityListings,
                         List<ExchangeFacet> exchanges,
                         List<IndexFacet> verifiedMemberships,
                         List<ClassificationState> classificationStates) {}

    public record ExchangeQuoteCoverage(String exchange, long activeListings,
                                        long storedFullQuotes, long withProviderPreviousClose,
                                        Instant newestExchangeAt) {}
    public record FullQuoteCoverage(String contractVersion, Instant generatedAt,
                                    long totalActiveListings, long storedFullQuotes,
                                    long withProviderPreviousClose, long activeBseCmRecords,
                                    List<ExchangeQuoteCoverage> exchanges) {}

    /**
     * All active NSE/BSE cash equities, NOT an index-selected subset.
     * Only source-verified, persisted FULL quote snapshots are counted.
     * These are last recorded broker observations, never marketed as live.
     */
    @GetMapping("/full-quote-coverage")
    public ResponseEntity<FullQuoteCoverage> fullQuoteCoverage(Authentication authentication) {
        requireCustomer(authentication);
        List<ExchangeQuoteCoverage> actual = jdbc.query("""
            WITH eligible AS (
                SELECT i.instrument_id, i.exchange, i.trading_symbol
                  FROM instrument_master i
                  JOIN instrument_catalogue_state s
                    ON s.singleton_id = 1 AND s.active_version = i.catalogue_version
                 WHERE i.active = TRUE AND i.exchange IN ('NSE','BSE')
            """ + InstrumentMasterRepository.CASH_EQUITY_PREDICATE + """
            ), verified AS (
                SELECT i.exchange,
                       CASE WHEN d.payload->>'source' = 'ANGEL_ONE_SMARTAPI_FULL_APP'
                            AND d.payload->>'exchange' = i.exchange
                            AND d.payload->>'tradingSymbol' = i.trading_symbol
                            AND (d.payload->>'instrumentId')::bigint = i.instrument_id
                            AND (d.payload->>'ltp')::numeric > 0
                            AND (d.payload->>'exchangeAt')::timestamptz <= now()
                         THEN (d.payload->>'exchangeAt')::timestamptz END AS exchange_at,
                       CASE WHEN d.payload->>'source' = 'ANGEL_ONE_SMARTAPI_FULL_APP'
                            AND d.payload->>'exchange' = i.exchange
                            AND d.payload->>'tradingSymbol' = i.trading_symbol
                            AND (d.payload->>'instrumentId')::bigint = i.instrument_id
                            AND (d.payload->>'ltp')::numeric > 0
                            AND (d.payload->>'exchangeAt')::timestamptz <= now()
                         THEN (d.payload->>'previousClose')::numeric END AS previous_close
                  FROM eligible i
                  LEFT JOIN market_detail_snapshot d ON d.instrument_id = i.instrument_id
            )
            SELECT exchange, COUNT(*) AS listed, COUNT(exchange_at) AS stored_quotes,
                   COUNT(*) FILTER (WHERE exchange_at IS NOT NULL
                           AND previous_close > 0) AS comparable,
                   MAX(exchange_at) AS latest_exchange
              FROM verified GROUP BY exchange ORDER BY exchange
            """, (rs, row) -> new ExchangeQuoteCoverage(
                rs.getString("exchange"), rs.getLong("listed"),
                rs.getLong("stored_quotes"), rs.getLong("comparable"),
                rs.getTimestamp("latest_exchange") == null ? null
                    : rs.getTimestamp("latest_exchange").toInstant()));

        Long bseMaster = jdbc.queryForObject("""
            SELECT COUNT(*) FROM instrument_master i
              JOIN instrument_catalogue_state s
                ON s.singleton_id = 1 AND s.active_version = i.catalogue_version
             WHERE i.active = TRUE AND i.exchange_segment = 'BSE_CM'
            """, Long.class);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(summarizeFullQuotes(actual, bseMaster == null ? 0 : bseMaster));
    }

    static FullQuoteCoverage summarizeFullQuotes(List<ExchangeQuoteCoverage> found, long bseMaster) {
        if (bseMaster < 0) throw new IllegalArgumentException("Invalid BSE catalogue count");
        java.util.Map<String, ExchangeQuoteCoverage> byExchange = new java.util.HashMap<>();
        for (ExchangeQuoteCoverage row : found) {
            if (!"NSE".equals(row.exchange()) && !"BSE".equals(row.exchange())
                    || byExchange.putIfAbsent(row.exchange(), row) != null
                    || row.activeListings() < 0 || row.storedFullQuotes() < 0
                    || row.withProviderPreviousClose() < 0
                    || row.storedFullQuotes() > row.activeListings()
                    || row.withProviderPreviousClose() > row.storedFullQuotes()) {
                throw new IllegalArgumentException("Invalid verified full quote coverage");
            }
        }
        List<ExchangeQuoteCoverage> ordered = List.of(
            byExchange.getOrDefault("NSE", new ExchangeQuoteCoverage("NSE", 0, 0, 0, null)),
            byExchange.getOrDefault("BSE", new ExchangeQuoteCoverage("BSE", 0, 0, 0, null)));
        return new FullQuoteCoverage("customer.market.full-quote-coverage.v1", Instant.now(),
            ordered.stream().mapToLong(ExchangeQuoteCoverage::activeListings).sum(),
            ordered.stream().mapToLong(ExchangeQuoteCoverage::storedFullQuotes).sum(),
            ordered.stream().mapToLong(ExchangeQuoteCoverage::withProviderPreviousClose).sum(),
            bseMaster, ordered);
    }

    @GetMapping("/universe-facets")
    public ResponseEntity<Facets> universeFacets(Authentication authentication) {
        requireCustomer(authentication);
        var catalogue = jdbc.query("""
            SELECT active_version, activated_at
              FROM instrument_catalogue_state WHERE singleton_id = 1
            """, (rs, row) -> new Catalogue(
                rs.getObject("active_version", UUID.class),
                rs.getTimestamp("activated_at") == null ? null
                    : rs.getTimestamp("activated_at").toInstant()))
            .stream().findFirst().orElse(new Catalogue(null, null));

        // Same eligibility predicate used by the existing customer EQ search.
        // The totals are exchange listings, not falsely deduplicated issuers.
        List<ExchangeFacet> exchanges = jdbc.query("""
            SELECT i.exchange, COUNT(*) AS listing_count
              FROM instrument_master i
              JOIN instrument_catalogue_state s
                ON s.singleton_id = 1 AND s.active_version = i.catalogue_version
             WHERE i.active = TRUE AND i.exchange IN ('NSE','BSE')
            """ + InstrumentMasterRepository.CASH_EQUITY_PREDICATE + """
             GROUP BY i.exchange ORDER BY i.exchange
            """, (rs, row) -> new ExchangeFacet(
                rs.getString("exchange"), rs.getLong("listing_count")));
        long total = countActiveListings(exchanges);

        // Only constituents with sourced reference rows appear. A zero or
        // missing view is not treated as proof that the index has no stocks.
        List<IndexFacet> indices = jdbc.query("""
            SELECT m.universe_code, COUNT(*) AS official_members,
                   COUNT(DISTINCT i.trading_symbol) AS matched,
                   MAX(m.as_of) AS as_of, MIN(m.source) AS member_source
              FROM market_universe_member m
              LEFT JOIN instrument_master i
                ON i.exchange = 'NSE' AND i.exchange_segment = 'NSE_CM'
               AND i.active = TRUE AND i.trading_symbol = m.trading_symbol
               AND i.catalogue_version = (
                   SELECT active_version FROM instrument_catalogue_state
                    WHERE singleton_id = 1)
             GROUP BY m.universe_code ORDER BY m.universe_code
            """, (rs, row) -> new IndexFacet(
                rs.getString("universe_code"), rs.getLong("official_members"),
                rs.getLong("matched"), rs.getObject("as_of", LocalDate.class),
                rs.getString("member_source")));

        // Do not label the 50 Nifty constituent sectors as market-wide
        // classifications. A separate complete, sourced import is required.
        List<ClassificationState> state = List.of(
            new ClassificationState("ALL_CASH_EQUITIES",
                catalogue.version() == null ? "CATALOGUE_NOT_ACTIVE" : "AVAILABLE",
                "Every active NSE/BSE -EQ listing, no index size limit"),
            new ClassificationState("INDEX_MEMBERSHIP", "PARTIAL",
                "Only index universes with verified imported membership appear"),
            new ClassificationState("NSE_SECTOR_INDUSTRY", "NOT_FULLY_INGESTED",
                "Nifty 50 constituent sectors do not classify the full catalogue"),
            new ClassificationState("MARKET_CAP", "NOT_FULLY_INGESTED",
                "AMFI/official market-cap rankings must be imported with as-of dates"),
            new ClassificationState("MICRO_NANO_CAP", "DEFINITION_REQUIRED",
                "Index membership and custom capitalization bands are distinct"));

        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new Facets("customer.market.universe.v1", Instant.now(),
                catalogue.version(), catalogue.activatedAt(), total,
                List.copyOf(exchanges), List.copyOf(indices), state));
    }

    static long countActiveListings(List<ExchangeFacet> exchanges) {
        return exchanges.stream().mapToLong(ExchangeFacet::activeListings).sum();
    }

    private void requireCustomer(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken token)
                || token.getAuthorities().stream().noneMatch(a ->
                    "ROLE_CUSTOMER".equals(a.getAuthority()))) {
            throw new ResponseStatusException(
                HttpStatus.UNAUTHORIZED, "Customer authentication required");
        }
        identities.find("cognito", token.getToken().getIssuer().toString(),
                token.getToken().getSubject())
            .filter(account -> "ACTIVE".equals(account.status())
                && "CUSTOMER".equals(account.role()))
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.UNAUTHORIZED, "Customer identity not linked"));
    }

    private record Catalogue(UUID version, Instant activatedAt) {}
}
