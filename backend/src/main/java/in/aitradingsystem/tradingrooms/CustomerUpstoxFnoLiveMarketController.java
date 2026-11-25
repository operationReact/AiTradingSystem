package in.aitradingsystem.tradingrooms;

import in.aitradingsystem.marketdata.MarketSessionCalendar;
import in.aitradingsystem.provider.upstox.UpstoxAnalyticsSource;
import in.aitradingsystem.provider.upstox.UpstoxFnoV3LiveFeedService;
import in.aitradingsystem.user.CustomerIdentityStore;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
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
 * Read-only listed NIFTY option picker for owner-bound Upstox LIVE rooms.
 * The quote board NEVER submits orders, confirms an order or grants execution
 * rights. The independent LIVE coordinator revalidates broker entitlement,
 * listed contract, fresh exchange quote, margin, limits and confirmation.
 */
@RestController
@RequestMapping("/api/customer/trading-rooms/{roomId}/live/fno")
@ConditionalOnProperty(name="minttick.upstox-fno.v3-feed-enabled", havingValue="true")
public final class CustomerUpstoxFnoLiveMarketController {
    private static final String UNDERLYING = CustomerPaperFnoMarketController.UNDERLYING;
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final JdbcClient jdbc;
    private final CustomerIdentityStore identities;
    private final UpstoxAnalyticsSource source;
    private final UpstoxFnoV3LiveFeedService feed;
    private final MarketSessionCalendar calendar;
    private final CustomerFnoExpiryCatalog expiries;
    private final Clock clock;
    private final boolean displayAllowed;

    public CustomerUpstoxFnoLiveMarketController(JdbcClient jdbc,
            CustomerIdentityStore identities, UpstoxAnalyticsSource source,
            UpstoxFnoV3LiveFeedService feed, MarketSessionCalendar calendar,
            CustomerFnoExpiryCatalog expiries, Environment env) {
        this.jdbc = jdbc;
        this.identities = identities;
        this.source = source;
        this.feed = feed;
        this.calendar = calendar;
        this.expiries = expiries;
        this.clock = Clock.systemUTC();
        this.displayAllowed = env.getProperty(
            "minttick.upstox-fno.display-rights-verified", Boolean.class, false);
    }

    @GetMapping("/expiries")
    public ResponseEntity<CustomerPaperFnoMarketController.Expiries> expiries(
            @PathVariable UUID roomId, Authentication authentication) {
        requireAuthorizedDisplay(roomId, authentication);
        Instant now = clock.instant();
        CustomerFnoExpiryCatalog.Listing listed = expiries.listing(now);
        if (listed == null || !listed.usable()) {
            String status = listed == null
                ? CustomerFnoExpiryCatalog.Listing.BLOCKED : listed.status();
            return noStore(new CustomerPaperFnoMarketController.Expiries(
                UNDERLYING, status, null, List.of()));
        }
        var valid = CustomerPaperFnoMarketController.validExpiries(
            listed.expiries(), now.atZone(IST).toLocalDate());
        return noStore(new CustomerPaperFnoMarketController.Expiries(
            UNDERLYING, valid.isEmpty() ? "NO_LISTED_EXPIRIES" : "VERIFIED",
            listed.observedAt(), valid));
    }

    @GetMapping("/market")
    public ResponseEntity<CustomerPaperFnoMarketController.Market> market(
            @PathVariable UUID roomId,
            @RequestParam("expiry") @DateTimeFormat(iso=DateTimeFormat.ISO.DATE)
            LocalDate expiry, Authentication authentication) {
        requireAuthorizedDisplay(roomId, authentication);
        Instant now = clock.instant();
        LocalDate today = now.atZone(IST).toLocalDate();
        if (expiry == null || expiry.isBefore(today) || expiry.isAfter(today.plusDays(90))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid NFO expiry");
        }
        if (!source.enabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "F&O listing source unavailable");
        }
        // REST gives contract identity only. NEVER display REST prices as live.
        var contracts = CustomerPaperFnoMarketController.providerRead(
            () -> source.currentFnoOptionContracts(UNDERLYING, expiry));
        var chain = CustomerPaperFnoMarketController.providerRead(
            () -> source.currentFnoOptionChain(UNDERLYING, expiry));
        if (chain == null || !"UPSTOX_ANALYTICS".equals(chain.source())
                || chain.retrievedAt() == null || chain.exchangeQuoteTimeVerified()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "F&O contract provenance unavailable");
        }
        boolean session = calendar.isInsideFnoSession(now);
        // paperEligible must stay false in this LIVE-only selector.
        var strikes = CustomerPaperFnoMarketController.select(
            contracts, chain, feed, session, false);
        boolean fresh = strikes.stream().anyMatch(
            strike -> strike.call().quoteFresh() || strike.put().quoteFresh());
        String status = !session ? "MARKET_CLOSED"
            : fresh ? "FRESH_QUOTES" : "NO_FRESH_QUOTES";
        // liveOrderEnabled remains false because this *read-only* market
        // response is not a per-contract, per-order authorization.
        return noStore(CustomerPaperFnoMarketController.marketResponse(
            expiry, chain.retrievedAt(), status, true, false, strikes));
    }

    private void requireAuthorizedDisplay(UUID roomId, Authentication auth) {
        UUID owner = owner(auth);
        if (roomId == null || !jdbc.sql("""
                SELECT TRUE FROM customer_trading_room r
                JOIN customer_live_room_broker b
                  ON b.room_id=r.id AND b.user_id=r.user_id
                WHERE r.id=:room AND r.user_id=:owner
                  AND r.mode='LIVE' AND r.market_scope IN ('FNO','ALL')
                  AND r.focus IN ('INTRADAY','SWING') AND b.broker='UPSTOX'
                """)
                .param("room", roomId).param("owner", owner)
                .query(Boolean.class).optional().orElse(false)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Owner Upstox LIVE NFO room not available");
        }
        if (!displayAllowed) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "NFO customer display rights not verified");
        }
    }

    private UUID owner(Authentication auth) {
        if (!(auth instanceof JwtAuthenticationToken token)
                || token.getAuthorities().stream().noneMatch(
                    authority -> "ROLE_CUSTOMER".equals(authority.getAuthority()))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "Customer session required");
        }
        return identities.find("cognito", token.getToken().getIssuer().toString(),
                token.getToken().getSubject())
            .filter(user -> "ACTIVE".equals(user.status())
                && "CUSTOMER".equals(user.role()))
            .map(CustomerIdentityStore.CustomerAccount::id)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.UNAUTHORIZED, "Customer not linked"));
    }

    private static <T> ResponseEntity<T> noStore(T value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);
    }
}
