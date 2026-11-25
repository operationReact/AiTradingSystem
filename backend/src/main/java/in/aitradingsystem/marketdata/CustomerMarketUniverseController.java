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
import org.springframework.web.bind.annotation.RequestParam;
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

    /** Catalogue identity and dated NSE index membership, not inferred financial fundamentals. */
    public record CompanyMembership(String indexCode, String source, LocalDate sourceAsOf) {}
    public record CompanyIdentity(long instrumentId, String tradingSymbol, String companyName,
                                  String exchange, String instrumentType) {}
    public record CompanyProfile(String contractVersion, long instrumentId, String tradingSymbol,
                                 String companyName, String exchange, String securityType,
                                 String industry, String sizeSegment,
                                 List<CompanyMembership> indexMemberships) {}
    record IndustryProof(String indexCode, String industry, String source, LocalDate asOf) {}

    @GetMapping("/company-profile")
    public ResponseEntity<CompanyProfile> companyProfile(Authentication authentication,
            @RequestParam long instrumentId) {
        requireCustomer(authentication);
        if (instrumentId < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid instrument ID");
        }
        List<CompanyIdentity> listings = jdbc.query("""
            SELECT i.instrument_id, i.trading_symbol, i.instrument_name,
                   i.exchange, i.instrument_type
              FROM instrument_master i
              JOIN instrument_catalogue_state s
                ON s.singleton_id = 1 AND s.active_version = i.catalogue_version
             WHERE i.active = TRUE AND i.instrument_id = ?
               AND i.exchange IN ('NSE','BSE')
            """ + InstrumentMasterRepository.CASH_EQUITY_PREDICATE + """
             LIMIT 1
            """, (rs, row) -> new CompanyIdentity(
                rs.getLong("instrument_id"), rs.getString("trading_symbol"),
                rs.getString("instrument_name"), rs.getString("exchange"),
                rs.getString("instrument_type")), instrumentId);
        if (listings.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Active equity not found");
        }
        CompanyIdentity listing = listings.get(0);
        List<IndustryProof> proofs = "NSE".equals(listing.exchange()) ? jdbc.query("""
            SELECT universe_code, sector, source, as_of
              FROM market_universe_member
             WHERE trading_symbol = ?
               AND source IS NOT NULL AND TRIM(source) <> ''
               AND as_of IS NOT NULL
             ORDER BY universe_code
            """, (rs, row) -> new IndustryProof(
                rs.getString("universe_code"), rs.getString("sector"),
                rs.getString("source"), rs.getObject("as_of", LocalDate.class)),
                listing.tradingSymbol()) : List.of();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(profileFromProofs(listing, proofs));
    }

    /** Conflicting industry classifications remain absent rather than silently choosing a source. */
    static CompanyProfile profileFromProofs(CompanyIdentity listing, List<IndustryProof> proofs) {
        List<IndustryProof> eligible = proofs.stream()
            .filter(p -> p.indexCode() != null && !p.indexCode().isBlank()
                && p.source() != null && !p.source().isBlank()
                && p.asOf() != null && !p.asOf().isAfter(LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"))))
            .toList();
        java.util.Set<String> industryValues = eligible.stream()
            .map(IndustryProof::industry)
            .filter(v -> v != null && !v.isBlank())
            .map(v -> v.trim().toUpperCase(java.util.Locale.ROOT))
            .collect(java.util.stream.Collectors.toSet());
        String industry = industryValues.size() == 1
            ? eligible.stream().map(IndustryProof::industry)
                .filter(v -> v != null && !v.isBlank()).findFirst().orElse(null)
            : null;
        java.util.Set<String> codes = eligible.stream()
            .map(IndustryProof::indexCode).collect(java.util.stream.Collectors.toSet());
        String size = codes.contains("NIFTY100") || codes.contains("NIFTY50") ? "LARGE"
            : codes.contains("MIDCAP150") ? "MID"
            : codes.contains("SMALLCAP250") ? "SMALL"
            : codes.contains("MICROCAP250") ? "MICRO" : null;
        return new CompanyProfile("customer.market.company-profile.v1", listing.instrumentId(),
            listing.tradingSymbol(), listing.companyName(), listing.exchange(),
            listing.instrumentType(), industry, size,
            eligible.stream().map(p -> new CompanyMembership(
                p.indexCode(), p.source(), p.asOf())).toList());
    }

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

        // Classifications become AVAILABLE only once the official Nifty lists that define them
        // have been ingested (see MarketUniverseMembershipStore). Nothing is inferred from price.
        java.util.Set<String> loaded = new java.util.HashSet<>();
        for (IndexFacet index : indices) if (index.officialMembers() > 0) loaded.add(index.universeCode());
        boolean caps = loaded.containsAll(List.of("NIFTY100", "MIDCAP150", "SMALLCAP250"));
        List<ClassificationState> state = List.of(
            new ClassificationState("ALL_CASH_EQUITIES",
                catalogue.version() == null ? "CATALOGUE_NOT_ACTIVE" : "AVAILABLE",
                "Every active NSE/BSE -EQ listing, no index size limit"),
            new ClassificationState("INDEX_MEMBERSHIP", "PARTIAL",
                "Only index universes with verified imported membership appear"),
            new ClassificationState("NSE_SECTOR_INDUSTRY",
                loaded.contains("NIFTY500") || caps ? "PARTIAL" : "NOT_FULLY_INGESTED",
                "Official Nifty list industries classify index members, not every listing"),
            new ClassificationState("MARKET_CAP", caps ? "AVAILABLE" : "NOT_FULLY_INGESTED",
                "Nifty 100 / Midcap 150 / Smallcap 250 official lists define large, mid and small cap"),
            new ClassificationState("MICRO_NANO_CAP",
                loaded.contains("MICROCAP250") ? "AVAILABLE" : "DEFINITION_REQUIRED",
                "Nifty Microcap 250 (ranks 501-750) defines micro cap; nano cap is not official"));

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
