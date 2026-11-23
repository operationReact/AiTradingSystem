package in.aitradingsystem.tradingrooms;

import in.aitradingsystem.marketdata.MarketSessionCalendar;
import in.aitradingsystem.provider.upstox.UpstoxAnalyticsSource;
import in.aitradingsystem.provider.upstox.UpstoxFnoV3LiveFeedService;
import in.aitradingsystem.user.CustomerIdentityStore;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Rights-gated selector for NIFTY CE/PE PAPER long-option trading.
 * Provider REST gives identity ONLY; in-memory V3 gives fresh bid/ask ONLY.
 * No REST chain price is ever presented as an executable quote.
 * No data persisted, no broker order API, no public/admin data route.
 */
@RestController
@RequestMapping("/api/customer/trading-rooms/{roomId}/paper/fno")
// Read-only real listed expiries and exchange-LTT V3 quotes are separate from
// PAPER trading; display permission is checked on every customer request.
// This controller offers no order placement and cannot enable LIVE brokerage.
@ConditionalOnProperty(name="minttick.upstox-fno.v3-feed-enabled", havingValue="true")
public class CustomerPaperFnoMarketController {
    static final String UNDERLYING = "NSE_INDEX|Nifty 50";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    public record Leg(String instrumentKey, String tradingSymbol, String side,
                      int lotSize, BigDecimal tickSize, BigDecimal bid, BigDecimal ask,
                      Instant exchangeAt, Instant observedAt,
                      boolean quoteFresh, boolean paperEligible,
                      BigDecimal ltp, BigDecimal previousClose, Long openInterest,
                      BigDecimal iv, BigDecimal delta, BigDecimal theta,
                      BigDecimal gamma, BigDecimal vega,
                      Long volume, Long bidQuantity, Long askQuantity,
                      Long lastTradedQuantity) {
        // Existing option-leg construction stays compatible; unspecified V3
        // quantities remain NULL, never confused with traded volume or OI.
        public Leg(String instrumentKey, String tradingSymbol, String side,
                int lotSize, BigDecimal tickSize, BigDecimal bid, BigDecimal ask,
                Instant exchangeAt, Instant observedAt,
                boolean quoteFresh, boolean paperEligible,
                BigDecimal ltp, BigDecimal previousClose, Long openInterest,
                BigDecimal iv, BigDecimal delta, BigDecimal theta,
                BigDecimal gamma, BigDecimal vega) {
            this(instrumentKey, tradingSymbol, side, lotSize, tickSize,
                bid, ask, exchangeAt, observedAt, quoteFresh, paperEligible,
                ltp, previousClose, openInterest, iv, delta, theta, gamma, vega,
                null, null, null, null);
        }
    }
    public record Strike(BigDecimal strikePrice, Leg call, Leg put) {}
    /** Real Upstox V2 option-chain snapshot, NOT an exchange-timed trade or order quote. */
    public record ChainSnapshotIndicator(BigDecimal ltp, BigDecimal bid,
            BigDecimal ask, Long openInterest, Long volume,
            Long previousOpenInterest, BigDecimal iv, BigDecimal delta,
            BigDecimal theta, BigDecimal gamma, BigDecimal vega) {
        /** Older Java construction sites do not invent missing provider counts. */
        public ChainSnapshotIndicator(BigDecimal ltp, BigDecimal bid,
                BigDecimal ask, Long openInterest, BigDecimal iv,
                BigDecimal delta, BigDecimal theta,
                BigDecimal gamma, BigDecimal vega) {
            this(ltp, bid, ask, openInterest, null, null,
                    iv, delta, theta, gamma, vega);
        }
    }
    public record Market(String underlyingKey, LocalDate expiry, String source,
                         String status, Instant snapshotAt, boolean exchangeSpotTimeVerified,
                         boolean displayRightsVerified, boolean trainingRightsVerified,
                         boolean liveOrderEnabled, List<Strike> strikes,
                         BigDecimal underlyingReferenceSpot,
                         Map<String, ChainSnapshotIndicator> chainSnapshotIndicators) {
        public Market(String underlyingKey, LocalDate expiry, String source,
                String status, Instant snapshotAt, boolean exchangeSpotTimeVerified,
                boolean displayRightsVerified, boolean trainingRightsVerified,
                boolean liveOrderEnabled, List<Strike> strikes,
                BigDecimal underlyingReferenceSpot) {
            this(underlyingKey, expiry, source, status, snapshotAt,
                    exchangeSpotTimeVerified, displayRightsVerified,
                    trainingRightsVerified, liveOrderEnabled, strikes,
                    underlyingReferenceSpot, Map.of());
        }
        public Market(String underlyingKey, LocalDate expiry, String source,
                String status, Instant snapshotAt, boolean exchangeSpotTimeVerified,
                boolean displayRightsVerified, boolean trainingRightsVerified,
                boolean liveOrderEnabled, List<Strike> strikes) {
            this(underlyingKey, expiry, source, status, snapshotAt,
                    exchangeSpotTimeVerified, displayRightsVerified,
                    trainingRightsVerified, liveOrderEnabled, strikes, null);
        }
    }

    private final JdbcClient jdbc;
    private final CustomerIdentityStore identities;
    private final UpstoxAnalyticsSource source;
    private final UpstoxFnoV3LiveFeedService live;
    private final CustomerFnoExpiryCatalog expiryCatalog;
    private final MarketSessionCalendar calendar;
    private final Clock clock;
    private final boolean displayAllowed;
    private final boolean paperAllowed;

    @Autowired
    public CustomerPaperFnoMarketController(JdbcClient jdbc,
            CustomerIdentityStore identities, UpstoxAnalyticsSource source,
            UpstoxFnoV3LiveFeedService live, MarketSessionCalendar calendar,
            CustomerFnoExpiryCatalog expiryCatalog, Environment env) {
        this(jdbc, identities, source, live, calendar, expiryCatalog, Clock.systemUTC(),
            env.getProperty("minttick.upstox-fno.display-rights-verified", Boolean.class, false),
            // Receiving permitted real quotes never implies PAPER eligibility.
            // Keep paperEligible false when the PAPER engine is disabled,
            // even if PAPER-use rights were separately verified.
            env.getProperty("minttick.upstox-fno.paper-enabled", Boolean.class, false)
                && env.getProperty("minttick.upstox-fno.paper-usage-rights-verified",
                    Boolean.class, false));
    }

    CustomerPaperFnoMarketController(JdbcClient jdbc,
            CustomerIdentityStore identities, UpstoxAnalyticsSource source,
            UpstoxFnoV3LiveFeedService live, MarketSessionCalendar calendar,
            Clock clock, boolean displayAllowed, boolean paperAllowed) {
        this(jdbc, identities, source, live, calendar,
            new UnboundCustomerFnoExpiryCatalog(), clock, displayAllowed, paperAllowed);
    }

    CustomerPaperFnoMarketController(JdbcClient jdbc,
            CustomerIdentityStore identities, UpstoxAnalyticsSource source,
            UpstoxFnoV3LiveFeedService live, MarketSessionCalendar calendar,
            CustomerFnoExpiryCatalog expiryCatalog, Clock clock,
            boolean displayAllowed, boolean paperAllowed) {
        this.jdbc=jdbc; this.identities=identities; this.source=source;
        this.live=live; this.calendar=calendar; this.clock=clock;
        this.expiryCatalog=expiryCatalog;
        this.displayAllowed=displayAllowed; this.paperAllowed=paperAllowed;
    }

    /** Server-verified, currently listed expiries. Dates are never inferred or hard-coded. */
    public record Expiries(String underlyingKey, String status, Instant observedAt,
                           List<LocalDate> expiries) {}

    @GetMapping("/expiries")
    public ResponseEntity<Expiries> expiries(@PathVariable UUID roomId, Authentication authentication) {
        UUID owner=owner(authentication);
        if (roomId==null || !ownedPaperRoom(owner,roomId))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "PAPER room unavailable");
        if (!displayAllowed)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Upstox F&O customer display rights not verified");
        Instant now=clock.instant();
        LocalDate today=now.atZone(IST).toLocalDate();
        CustomerFnoExpiryCatalog.Listing listing=expiryCatalog.listing(now);
        if (listing==null || !listing.usable()) {
            String status=listing==null ? CustomerFnoExpiryCatalog.Listing.BLOCKED : listing.status();
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new Expiries(UNDERLYING,status,null,List.of()));
        }
        List<LocalDate> valid=validExpiries(listing.expiries(), today);
        String status=valid.isEmpty() ? "NO_LISTED_EXPIRIES" : CustomerFnoExpiryCatalog.Listing.VERIFIED;
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new Expiries(UNDERLYING,status,listing.observedAt(),valid));
    }

    @GetMapping("/market")
    public ResponseEntity<Market> market(@PathVariable UUID roomId,
            @RequestParam("expiry") @DateTimeFormat(iso=DateTimeFormat.ISO.DATE)
            LocalDate expiry, Authentication authentication) {
        UUID owner=owner(authentication);
        if (roomId==null || !ownedPaperRoom(owner,roomId))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "PAPER room unavailable");
        if (!displayAllowed)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Upstox F&O customer display rights not verified");
        Instant now=clock.instant();
        LocalDate today=now.atZone(IST).toLocalDate();
        if (expiry==null || expiry.isBefore(today) || expiry.isAfter(today.plusDays(90)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid NIFTY expiry");
        if (!source.enabled())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Upstox F&O source disabled");

        // Bound metadata to the requested near-term date. REST chain values
        // have NO verified exchange last-traded timestamp and are NEVER quoted.
        var contracts=providerRead(() -> source.currentFnoOptionContracts(UNDERLYING,expiry));
        var chain=providerRead(() -> source.currentFnoOptionChain(UNDERLYING,expiry));
        if (chain==null || !"UPSTOX_ANALYTICS".equals(chain.source())
                || chain.retrievedAt()==null || chain.exchangeQuoteTimeVerified())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Upstox F&O provenance not verified");
        boolean marketOpen=calendar.isInsideFnoSession(now);
        List<Strike> strikes=select(contracts,chain,live,marketOpen,paperAllowed);
        boolean hasFresh=strikes.stream().anyMatch(s ->
                s.call().quoteFresh()||s.put().quoteFresh());
        String status=!marketOpen?"MARKET_CLOSED":hasFresh?"FRESH_QUOTES":"NO_FRESH_QUOTES";
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(marketResponse(expiry, chain.retrievedAt(), status,
                displayAllowed, paperAllowed, strikes));
    }

    /**
     * Surface the independently verified customer permission state, not a
     * hard-coded false that hides available PAPER trading once rights and
     * server-side execution have actually been enabled. This never grants
     * permission: the real order controller and service still enforce their
     * own feature, ownership, quote freshness and risk gates.
     */
    static Market marketResponse(LocalDate expiry, Instant snapshotAt,
            String status, boolean displayAllowed, boolean paperAllowed,
            List<Strike> strikes) {
        return marketResponse(expiry, snapshotAt, status, displayAllowed,
            paperAllowed, strikes, null);
    }

    static Market marketResponse(LocalDate expiry, Instant snapshotAt,
            String status, boolean displayAllowed, boolean paperAllowed,
            List<Strike> strikes, BigDecimal referenceSpot) {
        return new Market(UNDERLYING, expiry, "UPSTOX_ANALYTICS",
            status, snapshotAt, false, displayAllowed, paperAllowed, false,
            List.copyOf(strikes),
            referenceSpot != null && referenceSpot.signum() > 0
                ? referenceSpot : null);
    }

    /**
     * Isolated upstream source/parser failures are service unavailability,
     * not internal 500s. Never leak raw provider details, tokens or URLs.
     * Identity, owner, segment and display-rights checks remain outside.
     */
    static <T> T providerRead(java.util.function.Supplier<T> read) {
        try {
            return read.get();
        } catch (IllegalArgumentException | IllegalStateException unavailable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "F&O provider contracts or chain unavailable");
        }
    }

    /** Future expiries within the 90-day window the market endpoint accepts; deduped and sorted. */
    static List<LocalDate> validExpiries(List<LocalDate> listed, LocalDate today) {
        return listed.stream()
            .filter(Objects::nonNull)
            .filter(d -> !d.isBefore(today) && !d.isAfter(today.plusDays(90)))
            .distinct().sorted().toList();
    }

    static List<Strike> select(List<UpstoxAnalyticsSource.FnoOptionContract> contracts,
            UpstoxAnalyticsSource.OptionChainSnapshot chain,
            UpstoxFnoV3LiveFeedService live, boolean marketOpen, boolean paperAllowed) {
        // Trade terminal stays deliberately bounded at 24 near-ATM strikes.
        return selectBounded(contracts, chain, live, marketOpen, paperAllowed, 24, false);
    }

    /** Read-only discovery may display all listed rows in the current NIFTY
     * pilot (up to 256), without expanding subscriptions or order authority.
     * Non-subscribed strikes keep missing prices rather than using REST quotes.
     */
    static List<Strike> selectDiscovery(
            List<UpstoxAnalyticsSource.FnoOptionContract> contracts,
            UpstoxAnalyticsSource.OptionChainSnapshot chain,
            UpstoxFnoV3LiveFeedService live, boolean marketOpen) {
        return selectBounded(contracts, chain, live, marketOpen, false, 256, true);
    }

    private static List<Strike> selectBounded(
            List<UpstoxAnalyticsSource.FnoOptionContract> contracts,
            UpstoxAnalyticsSource.OptionChainSnapshot chain,
            UpstoxFnoV3LiveFeedService live, boolean marketOpen,
            boolean paperAllowed, int maximumStrikes, boolean displayOnly) {
        if (contracts==null || chain==null || chain.strikes()==null
                || chain.strikes().isEmpty() || chain.strikes().size()>1024
                || contracts.size()>1024) return List.of();
        Map<String, UpstoxAnalyticsSource.FnoOptionContract> byKey=new HashMap<>();
        LocalDate expiry=chain.strikes().getFirst().expiry();
        for (var c:contracts) {
            if (c!=null && UNDERLYING.equals(c.underlyingKey())
                    && expiry.equals(c.expiry())
                    && ("CE".equals(c.side())||"PE".equals(c.side()))
                    && c.instrumentKey()!=null
                    && c.instrumentKey().matches("NSE_FO\\|[A-Za-z0-9._-]{1,64}")
                    && c.lotSize()>0 && c.lotSize()<=10000
                    && c.tickSize()!=null && c.tickSize().signum()>0
                    && c.strikePrice()!=null && c.strikePrice().signum()>0)
                byKey.put(c.instrumentKey(),c);
        }
        BigDecimal spot=chain.strikes().getFirst().underlyingSpotPrice();
        if (spot==null || spot.signum()<=0) return List.of();
        List<UpstoxAnalyticsSource.FnoOptionStrike> nearest=new ArrayList<>(chain.strikes());
        if (nearest.stream().anyMatch(s->s==null || s.strikePrice()==null
                || s.strikePrice().signum()<=0 || s.call()==null || s.put()==null
                || !UNDERLYING.equals(s.underlyingKey()) || !expiry.equals(s.expiry())))
            return List.of();
        nearest.sort(Comparator.comparing(s->s.strikePrice().subtract(spot).abs()));
        List<Strike> rows=new ArrayList<>();
        for (var strike:nearest) {
            if (rows.size() >= maximumStrikes) break;
            var call=byKey.get(strike.call().instrumentKey());
            var put=byKey.get(strike.put().instrumentKey());
            if (call==null || put==null || !"CE".equals(call.side())
                    || !"PE".equals(put.side())
                    || call.strikePrice().compareTo(strike.strikePrice())!=0
                    || put.strikePrice().compareTo(strike.strikePrice())!=0)
                continue;
            rows.add(new Strike(strike.strikePrice(),
                leg(call,live,marketOpen,paperAllowed,displayOnly),
                leg(put,live,marketOpen,paperAllowed,displayOnly)));
        }
        rows.sort(Comparator.comparing(Strike::strikePrice));
        return List.copyOf(rows);
    }

    private static Leg leg(UpstoxAnalyticsSource.FnoOptionContract c,
            UpstoxFnoV3LiveFeedService live, boolean marketOpen,
            boolean paperAllowed, boolean displayOnly) {
        // Display-only discoveries may use a genuine fresh V3 trade even if
        // executable two-sided order depth is unavailable. No paper eligibility.
        if (displayOnly) {
            var details = marketOpen
                ? live.eligibleDisplayQuote(c.instrumentKey())
                : java.util.Optional.<UpstoxFnoV3LiveFeedService.DisplayQuote>empty();
            var d = details.orElse(null);
            return new Leg(c.instrumentKey(), c.tradingSymbol(), c.side(),
                c.lotSize(), c.tickSize(),
                null, null,
                d == null ? null : d.exchangeAt(),
                d == null ? null : d.observedAt(),
                d != null, false,
                d == null ? null : d.ltp(),
                d == null ? null : d.previousClose(),
                d == null ? null : d.openInterest(),
                d == null ? null : d.iv(),
                d == null ? null : d.delta(),
                d == null ? null : d.theta(),
                d == null ? null : d.gamma(),
                d == null ? null : d.vega(),
                d == null ? null : d.volume(),
                d == null ? null : d.bidQuantity(),
                d == null ? null : d.askQuantity(),
                d == null ? null : d.lastTradedQuantity());
        }
        // Execution-eligible quotes still require strictly validated bid/ask.
        var quote = marketOpen ? live.eligiblePaperQuote(c.instrumentKey())
                : java.util.Optional.<UpstoxFnoV3LiveFeedService.PaperQuote>empty();
        var details = quote.isPresent()
                ? live.eligibleDisplayQuote(c.instrumentKey())
                : java.util.Optional.<UpstoxFnoV3LiveFeedService.DisplayQuote>empty();
        if (quote.isPresent() && details.isPresent()
                && (!quote.get().exchangeAt().equals(details.get().exchangeAt())
                    || !quote.get().observedAt().equals(details.get().observedAt())
                    || details.get().bid() == null || details.get().ask() == null
                    || quote.get().bid().compareTo(details.get().bid()) != 0
                    || quote.get().ask().compareTo(details.get().ask()) != 0)) {
            details = java.util.Optional.empty();
        }
        var d = details.orElse(null);
        return new Leg(c.instrumentKey(), c.tradingSymbol(), c.side(),
                c.lotSize(), c.tickSize(),
                quote.map(UpstoxFnoV3LiveFeedService.PaperQuote::bid).orElse(null),
                quote.map(UpstoxFnoV3LiveFeedService.PaperQuote::ask).orElse(null),
                quote.map(UpstoxFnoV3LiveFeedService.PaperQuote::exchangeAt).orElse(null),
                quote.map(UpstoxFnoV3LiveFeedService.PaperQuote::observedAt).orElse(null),
                quote.isPresent(), quote.isPresent() && paperAllowed,
                d == null ? null : d.ltp(),
                d == null ? null : d.previousClose(),
                d == null ? null : d.openInterest(),
                d == null ? null : d.iv(),
                d == null ? null : d.delta(),
                d == null ? null : d.theta(),
                d == null ? null : d.gamma(),
                d == null ? null : d.vega(),
                d == null ? null : d.volume(),
                d == null ? null : d.bidQuantity(),
                d == null ? null : d.askQuantity(),
                d == null ? null : d.lastTradedQuantity());
    }

    private boolean ownedPaperRoom(UUID owner,UUID room) {
        return jdbc.sql("""
            SELECT TRUE FROM customer_trading_room
            WHERE id=:room AND user_id=:owner AND mode='PAPER' AND status='PAPER_FUNDED'
              AND market_scope IN ('FNO','ALL')
            """).param("room",room).param("owner",owner)
            .query(Boolean.class).optional().orElse(false);
    }

    private UUID owner(Authentication auth) {
        if (!(auth instanceof JwtAuthenticationToken token)
                || token.getAuthorities().stream().noneMatch(a ->
                    "ROLE_CUSTOMER".equals(a.getAuthority())))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Customer required");
        return identities.find("cognito",token.getToken().getIssuer().toString(),
                    token.getToken().getSubject())
            .filter(x->"ACTIVE".equals(x.status()) && "CUSTOMER".equals(x.role()))
            .map(CustomerIdentityStore.CustomerAccount::id)
            .orElseThrow(()->new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "Customer not linked"));
    }
}
