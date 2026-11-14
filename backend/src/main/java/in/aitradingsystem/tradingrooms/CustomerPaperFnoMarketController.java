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
@ConditionalOnProperty(
    name={"minttick.upstox-fno.paper-enabled", "minttick.upstox-fno.v3-feed-enabled"},
    havingValue="true")
public class CustomerPaperFnoMarketController {
    static final String UNDERLYING = "NSE_INDEX|Nifty 50";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    public record Leg(String instrumentKey, String tradingSymbol, String side,
                      int lotSize, BigDecimal tickSize, BigDecimal bid, BigDecimal ask,
                      Instant exchangeAt, Instant observedAt,
                      boolean quoteFresh, boolean paperEligible) {}
    public record Strike(BigDecimal strikePrice, Leg call, Leg put) {}
    public record Market(String underlyingKey, LocalDate expiry, String source,
                         String status, Instant snapshotAt, boolean exchangeSpotTimeVerified,
                         boolean displayRightsVerified, boolean trainingRightsVerified,
                         boolean liveOrderEnabled, List<Strike> strikes) {}

    private final JdbcClient jdbc;
    private final CustomerIdentityStore identities;
    private final UpstoxAnalyticsSource source;
    private final UpstoxFnoV3LiveFeedService live;
    private final MarketSessionCalendar calendar;
    private final Clock clock;
    private final boolean displayAllowed;
    private final boolean paperAllowed;

    @Autowired
    public CustomerPaperFnoMarketController(JdbcClient jdbc,
            CustomerIdentityStore identities, UpstoxAnalyticsSource source,
            UpstoxFnoV3LiveFeedService live, MarketSessionCalendar calendar,
            Environment env) {
        this(jdbc, identities, source, live, calendar, Clock.systemUTC(),
            env.getProperty("minttick.upstox-fno.display-rights-verified", Boolean.class, false),
            env.getProperty("minttick.upstox-fno.paper-usage-rights-verified", Boolean.class, false));
    }

    CustomerPaperFnoMarketController(JdbcClient jdbc,
            CustomerIdentityStore identities, UpstoxAnalyticsSource source,
            UpstoxFnoV3LiveFeedService live, MarketSessionCalendar calendar,
            Clock clock, boolean displayAllowed, boolean paperAllowed) {
        this.jdbc=jdbc; this.identities=identities; this.source=source;
        this.live=live; this.calendar=calendar; this.clock=clock;
        this.displayAllowed=displayAllowed; this.paperAllowed=paperAllowed;
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
        var contracts=source.currentFnoOptionContracts(UNDERLYING,expiry);
        var chain=source.currentFnoOptionChain(UNDERLYING,expiry);
        if (chain==null || !"UPSTOX_ANALYTICS".equals(chain.source())
                || chain.retrievedAt()==null || chain.exchangeQuoteTimeVerified())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Upstox F&O provenance not verified");
        boolean marketOpen=calendar.isInsideCashSession(now);
        List<Strike> strikes=select(contracts,chain,live,marketOpen,paperAllowed);
        boolean hasFresh=strikes.stream().anyMatch(s ->
                s.call().quoteFresh()||s.put().quoteFresh());
        String status=!marketOpen?"MARKET_CLOSED":hasFresh?"FRESH_QUOTES":"NO_FRESH_QUOTES";
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new Market(UNDERLYING,expiry,"UPSTOX_ANALYTICS",status,
                chain.retrievedAt(),false,true,false,false,strikes));
    }

    static List<Strike> select(List<UpstoxAnalyticsSource.FnoOptionContract> contracts,
            UpstoxAnalyticsSource.OptionChainSnapshot chain,
            UpstoxFnoV3LiveFeedService live, boolean marketOpen, boolean paperAllowed) {
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
            if (rows.size()>=24) break;
            var call=byKey.get(strike.call().instrumentKey());
            var put=byKey.get(strike.put().instrumentKey());
            if (call==null || put==null || !"CE".equals(call.side())
                    || !"PE".equals(put.side())
                    || call.strikePrice().compareTo(strike.strikePrice())!=0
                    || put.strikePrice().compareTo(strike.strikePrice())!=0)
                continue;
            rows.add(new Strike(strike.strikePrice(),
                leg(call,live,marketOpen,paperAllowed),
                leg(put,live,marketOpen,paperAllowed)));
        }
        rows.sort(Comparator.comparing(Strike::strikePrice));
        return List.copyOf(rows);
    }

    private static Leg leg(UpstoxAnalyticsSource.FnoOptionContract c,
            UpstoxFnoV3LiveFeedService live,boolean marketOpen,boolean paperAllowed) {
        var quote=marketOpen ? live.eligiblePaperQuote(c.instrumentKey())
            : java.util.Optional.<UpstoxFnoV3LiveFeedService.PaperQuote>empty();
        return new Leg(c.instrumentKey(),c.tradingSymbol(),c.side(),
                c.lotSize(),c.tickSize(),
                quote.map(UpstoxFnoV3LiveFeedService.PaperQuote::bid).orElse(null),
                quote.map(UpstoxFnoV3LiveFeedService.PaperQuote::ask).orElse(null),
                quote.map(UpstoxFnoV3LiveFeedService.PaperQuote::exchangeAt).orElse(null),
                quote.map(UpstoxFnoV3LiveFeedService.PaperQuote::observedAt).orElse(null),
                quote.isPresent(),quote.isPresent()&&paperAllowed);
    }

    private boolean ownedPaperRoom(UUID owner,UUID room) {
        return jdbc.sql("""
            SELECT TRUE FROM customer_trading_room
            WHERE id=:room AND user_id=:owner AND mode='PAPER' AND status='PAPER_FUNDED'
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
