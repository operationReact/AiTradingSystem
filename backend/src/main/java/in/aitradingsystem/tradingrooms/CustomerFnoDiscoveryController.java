package in.aitradingsystem.tradingrooms;

import in.aitradingsystem.marketdata.MarketSessionCalendar;
import in.aitradingsystem.provider.upstox.UpstoxAnalyticsSource;
import in.aitradingsystem.provider.upstox.UpstoxFnoV3LiveFeedService;
import in.aitradingsystem.user.CustomerIdentityStore;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Customer read-only F&O Markets discovery, independent of broker connection,
 * room creation, paper funds or LIVE execution consent. The app-wide SSM
 * source provides listed identities and rights-gated live V3 observations.
 * No public/anonymous/analytics-token order route exists in this controller.
 */
@RestController
@RequestMapping("/api/customer/markets/fno")
public final class CustomerFnoDiscoveryController {
    static final String UNDERLYING = CustomerPaperFnoMarketController.UNDERLYING;
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final CustomerIdentityStore identities;
    private final UpstoxAnalyticsSource source;
    private final ObjectProvider<UpstoxFnoV3LiveFeedService> feed;
    private final CustomerFnoExpiryCatalog expiryCatalog;
    private final MarketSessionCalendar sessionCalendar;
    private final Clock clock;
    private final boolean feedConfigured;
    private final boolean displayRightsVerified;
    private final boolean contractDiscoveryVerified;
    private final boolean restLastPriceRightsVerified;
    private volatile RestPriceCache restPriceCache;
    private record RestPriceCache(LocalDate expiry, Instant obtainedAt,
        java.util.Map<String, CustomerFnoLastTradeSnapshotStore.LastTrade> values) {}
    private static final Duration CONTRACT_CACHE_TTL = Duration.ofSeconds(60);
    private volatile ContractMetadata cachedMetadata;
    private volatile CustomerFnoLastTradeSnapshotStore lastTrades;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setLastTrades(CustomerFnoLastTradeSnapshotStore snapshotStore) {
        this.lastTrades = snapshotStore;
    }

    private record ContractMetadata(LocalDate expiry, Instant receivedAt,
        List<UpstoxAnalyticsSource.FnoOptionContract> contracts,
        UpstoxAnalyticsSource.OptionChainSnapshot chain) {}

    public CustomerFnoDiscoveryController(CustomerIdentityStore identities,
            UpstoxAnalyticsSource source,
            ObjectProvider<UpstoxFnoV3LiveFeedService> feed,
            CustomerFnoExpiryCatalog expiryCatalog,
            MarketSessionCalendar sessionCalendar, Environment env) {
        this.identities = identities;
        this.source = source;
        this.feed = feed;
        this.expiryCatalog = expiryCatalog;
        this.sessionCalendar = sessionCalendar;
        this.clock = Clock.systemUTC();
        this.feedConfigured = env.getProperty(
            "minttick.upstox-fno.v3-feed-enabled", Boolean.class, false);
        this.displayRightsVerified = env.getProperty(
            "minttick.upstox-fno.display-rights-verified", Boolean.class, false);
        // Expiry/contract-identity listing has its own permission. Display rights
        // imply it; prices, OI, IV and Greeks still require displayRightsVerified.
        this.contractDiscoveryVerified = displayRightsVerified || env.getProperty(
            "minttick.upstox-fno.contract-discovery-verified", Boolean.class, false);
        this.restLastPriceRightsVerified = displayRightsVerified && env.getProperty(
            "minttick.upstox-fno.rest-last-price-display-rights-verified",
            Boolean.class, false);
    }

    public record Capability(boolean displayRightsVerified, boolean feedConfigured,
                             boolean sourceReady, boolean marketOpen,
                             String status, String streamState,
                             int freshExchangeTimedQuotes, Instant latestFreshExchangeTradeAt,
                             boolean contractDiscoveryVerified) {}

    @GetMapping("/capability")
    public ResponseEntity<Capability> capability(Authentication auth) {
        requireCustomer(auth);
        return noStore(internalCapability());
    }

    Capability internalCapability() {
        boolean sourceReady = source.enabled();
        boolean open = sessionCalendar.isInsideFnoSession(clock.instant());
        var stream = feed.getIfAvailable();
        var liveStatus = feedConfigured && stream != null ? stream.status() : null;
        int fresh = open && liveStatus != null ? liveStatus.freshExchangeTimedQuotes() : 0;
        String status = !displayRightsVerified
                ? (contractDiscoveryVerified
                    ? "CONTRACTS_LISTED_PRICES_NOT_AUTHORIZED"
                    : "DISPLAY_RIGHTS_NOT_VERIFIED")
            : !feedConfigured || stream == null ? "FNO_V3_FEED_UNAVAILABLE"
            : !sourceReady ? "FNO_CONTRACT_SOURCE_UNAVAILABLE"
            : !open ? "MARKET_CLOSED"
            : fresh > 0 ? "LIVE_QUOTES"
            : "CONNECTED".equals(liveStatus.state())
                 || "CONNECTED_AWAITING_TICKS".equals(liveStatus.state())
                    ? "WAITING_FOR_QUOTES" : "FEED_DISCONNECTED";
        return new Capability(displayRightsVerified, feedConfigured,
            sourceReady, open, status,
            liveStatus == null ? "UNAVAILABLE" : liveStatus.state(),
            displayRightsVerified ? fresh : 0,
            displayRightsVerified && open && fresh > 0
                ? liveStatus.latestFreshExchangeTradeAt() : null,
            contractDiscoveryVerified);
    }

    @GetMapping("/expiries")
    public ResponseEntity<CustomerPaperFnoMarketController.Expiries> expiries(
            Authentication auth) {
        // Expiry dates only: no prices, OI, IV or Greeks, so discovery permission suffices.
        requireDiscovery(auth);
        return noStore(internalExpiries());
    }

    CustomerPaperFnoMarketController.Expiries internalExpiries() {
        if (!contractDiscoveryVerified) throw new ResponseStatusException(HttpStatus.CONFLICT,"F&O discovery permission unverified");
        CustomerFnoExpiryCatalog.Listing listing =
            expiryCatalog.listing(clock.instant());
        if (listing == null || !listing.usable()) {
            String status = listing == null ?
                CustomerFnoExpiryCatalog.Listing.BLOCKED : listing.status();
            return new CustomerPaperFnoMarketController.Expiries(
                UNDERLYING, status, null, List.of());
        }
        var valid = CustomerPaperFnoMarketController.validExpiries(
            listing.expiries(), clock.instant().atZone(IST).toLocalDate());
        return new CustomerPaperFnoMarketController.Expiries(
            UNDERLYING, valid.isEmpty() ? "NO_LISTED_EXPIRIES" : "VERIFIED",
            listing.observedAt(), valid);
    }

    @GetMapping("/market")
    public ResponseEntity<CustomerPaperFnoMarketController.Market> market(
            @RequestParam("expiry") @DateTimeFormat(iso=DateTimeFormat.ISO.DATE)
            LocalDate expiry, Authentication auth) {
        requireDisplay(auth);
        return noStore(internalMarket(expiry));
    }

    CustomerPaperFnoMarketController.Market internalMarket(LocalDate expiry) {
        if (!displayRightsVerified) throw new ResponseStatusException(HttpStatus.CONFLICT,"F&O quote rights unverified");
        Instant now = clock.instant();
        LocalDate today = now.atZone(IST).toLocalDate();
        if (expiry == null || expiry.isBefore(today) ||
                expiry.isAfter(today.plusDays(90))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Invalid listed NIFTY option expiry");
        }
        if (!source.enabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "F&O contract source unavailable");
        }
        var stream = feed.getIfAvailable();
        if (!feedConfigured || stream == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "F&O exchange feed unavailable");
        }
        // REST chain is a contract-identity source only; NEVER use its
        // prices, timestamps, Greeks or OI as executable V3 observations.
        // Repeated 5-second customer polling only reads fresh V3 observations.
        // Contract identity is held in memory for <=60 seconds, never prices.
        var metadata = metadataFor(expiry, now);
        var contracts = metadata.contracts();
        var chain = metadata.chain();
        if (chain == null || !"UPSTOX_ANALYTICS".equals(chain.source()) ||
                chain.retrievedAt() == null || chain.exchangeQuoteTimeVerified()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "F&O source provenance unavailable");
        }
        boolean open = sessionCalendar.isInsideFnoSession(now);
        var liveStrikes = CustomerPaperFnoMarketController.selectDiscovery(
            contracts, chain, stream, open);
        boolean fresh = liveStrikes.stream().anyMatch(s ->
            s.call().quoteFresh() || s.put().quoteFresh());
        // Order eligibility is never restored by retained snapshots.
        var strikes = withLastVerifiedTrades(liveStrikes, expiry, now, !open);
        String status = !open ? "MARKET_CLOSED" :
            fresh ? "FRESH_QUOTES" : "NO_FRESH_QUOTES";
        // This market board has NO execution capability and NO paper rights.
        return CustomerPaperFnoMarketController.marketResponse(
            expiry, chain.retrievedAt(), status, true, false, strikes,
            chain.strikes().isEmpty() ? null
                : chain.strikes().getFirst().underlyingSpotPrice());
    }

    private List<CustomerPaperFnoMarketController.Strike> withLastVerifiedTrades(
            List<CustomerPaperFnoMarketController.Strike> current,
            LocalDate expiry, Instant now, boolean marketClosed) {
        if (current.isEmpty()) return current;
        var keys = current.stream()
            .flatMap(row -> java.util.stream.Stream.of(row.call(), row.put()))
            .filter(java.util.Objects::nonNull)
            .filter(leg -> !leg.quoteFresh())
            .map(CustomerPaperFnoMarketController.Leg::instrumentKey)
            .distinct().toList();
        var lastByKey = new java.util.HashMap<String,
            CustomerFnoLastTradeSnapshotStore.LastTrade>();
        var store = lastTrades;
        if (store != null) lastByKey.putAll(store.latestAll(keys));

        // REST full quotes contain actual last-trade times, including previous
        // session values. Independent permission is mandatory, and REST data
        // NEVER becomes a fill/order bid, ask or quoteFresh=true.
        if (marketClosed && restLastPriceRightsVerified) {
            var cached = restPriceCache;
            java.util.Map<String, CustomerFnoLastTradeSnapshotStore.LastTrade> rest;
            if (cached != null && cached.expiry().equals(expiry)
                    && !now.isBefore(cached.obtainedAt())
                    && now.isBefore(cached.obtainedAt().plusSeconds(60))) {
                rest = cached.values();
            } else {
                try {
                    var requestKeys = keys.stream()
                        .filter(key -> !lastByKey.containsKey(key))
                        .limit(64).toList();
                    var last = requestKeys.isEmpty()
                        ? java.util.Map.<String, UpstoxAnalyticsSource.ListedLastTrade>of()
                        : source.listedFnoLastTrades(requestKeys);
                    var converted = new java.util.HashMap<String,
                        CustomerFnoLastTradeSnapshotStore.LastTrade>();
                    last.forEach((key, quote) -> converted.put(key,
                        new CustomerFnoLastTradeSnapshotStore.LastTrade(
                            quote.ltp(), quote.previousClose(), quote.openInterest(),
                            quote.exchangeAt(), quote.observedAt())));
                    rest = java.util.Map.copyOf(converted);
                    restPriceCache = new RestPriceCache(expiry, now, rest);
                } catch (RuntimeException notAvailable) {
                    // No synthetic fallback and no upstream secrets in UI.
                    rest = java.util.Map.of();
                }
            }
            rest.forEach(lastByKey::putIfAbsent);
        }
        if (lastByKey.isEmpty()) return current;
        return current.stream().map(row ->
            new CustomerPaperFnoMarketController.Strike(row.strikePrice(),
                lastVerifiedLeg(row.call(), lastByKey),
                lastVerifiedLeg(row.put(), lastByKey))).toList();
    }

    private static CustomerPaperFnoMarketController.Leg lastVerifiedLeg(
            CustomerPaperFnoMarketController.Leg leg,
            java.util.Map<String, CustomerFnoLastTradeSnapshotStore.LastTrade> lastByKey) {
        if (leg == null || leg.quoteFresh()) return leg;
        var last = lastByKey.get(leg.instrumentKey());
        if (last == null) return leg;
        return new CustomerPaperFnoMarketController.Leg(
            leg.instrumentKey(), leg.tradingSymbol(), leg.side(), leg.lotSize(),
            leg.tickSize(), null, null,
            last.exchangeAt(), last.observedAt(), false, false,
            last.ltp(), last.previousClose(), last.openInterest(),
            null, null, null, null, null);
    }

    /**
     * Provider REST identity cache prevents unbounded option-contract API
     * calls under customer polling. Immutable metadata only, no quote/rights
     * cache and never any return of stale verified market prices.
     */
    private synchronized ContractMetadata metadataFor(LocalDate expiry, Instant now) {
        var existing = cachedMetadata;
        if (existing != null && existing.expiry().equals(expiry) &&
                !now.isBefore(existing.receivedAt()) &&
                now.isBefore(existing.receivedAt().plus(CONTRACT_CACHE_TTL))) {
            return existing;
        }
        cachedMetadata = null;
        var contracts = CustomerPaperFnoMarketController.providerRead(
            () -> source.currentFnoOptionContracts(UNDERLYING, expiry));
        var chain = CustomerPaperFnoMarketController.providerRead(
            () -> source.currentFnoOptionChain(UNDERLYING, expiry));
        if (contracts == null || contracts.isEmpty() || contracts.size() > 1024 ||
                chain == null || !"UPSTOX_ANALYTICS".equals(chain.source()) ||
                chain.retrievedAt() == null || chain.exchangeQuoteTimeVerified() ||
                chain.strikes() == null || chain.strikes().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Verified listed F&O contract metadata unavailable");
        }
        var result = new ContractMetadata(expiry, now,
            List.copyOf(contracts), chain);
        cachedMetadata = result;
        return result;
    }

    private void requireDisplay(Authentication auth) {
        requireCustomer(auth);
        if (!displayRightsVerified) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Customer F&O display rights not verified");
        }
    }

    private void requireDiscovery(Authentication auth) {
        requireCustomer(auth);
        if (!contractDiscoveryVerified) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Customer F&O contract discovery not verified");
        }
    }

    private UUID requireCustomer(Authentication auth) {
        if (!(auth instanceof JwtAuthenticationToken jwt) ||
                jwt.getAuthorities().stream().noneMatch(
                    a -> "ROLE_CUSTOMER".equals(a.getAuthority()))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "Customer login required");
        }
        return identities.find("cognito",
                jwt.getToken().getIssuer().toString(),
                jwt.getToken().getSubject())
            .filter(a -> "ACTIVE".equals(a.status()) &&
                "CUSTOMER".equals(a.role()))
            .map(CustomerIdentityStore.CustomerAccount::id)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.UNAUTHORIZED, "Customer account not linked"));
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
