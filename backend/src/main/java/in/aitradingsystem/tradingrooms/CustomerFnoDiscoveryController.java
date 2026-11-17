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
    }

    public record Capability(boolean displayRightsVerified, boolean feedConfigured,
                             boolean sourceReady, boolean marketOpen,
                             String status) {}

    @GetMapping("/capability")
    public ResponseEntity<Capability> capability(Authentication auth) {
        requireCustomer(auth);
        boolean sourceReady = source.enabled();
        boolean open = sessionCalendar.isInsideFnoSession(clock.instant());
        String status = !displayRightsVerified ? "DISPLAY_RIGHTS_NOT_VERIFIED"
            : !feedConfigured || feed.getIfAvailable() == null
                ? "FNO_V3_FEED_UNAVAILABLE"
            : !sourceReady ? "FNO_CONTRACT_SOURCE_UNAVAILABLE"
            : !open ? "MARKET_CLOSED" : "SOURCE_CONFIGURED";
        return noStore(new Capability(displayRightsVerified, feedConfigured,
            sourceReady, open, status));
    }

    @GetMapping("/expiries")
    public ResponseEntity<CustomerPaperFnoMarketController.Expiries> expiries(
            Authentication auth) {
        requireDisplay(auth);
        CustomerFnoExpiryCatalog.Listing listing =
            expiryCatalog.listing(clock.instant());
        if (listing == null || !listing.usable()) {
            String status = listing == null ?
                CustomerFnoExpiryCatalog.Listing.BLOCKED : listing.status();
            return noStore(new CustomerPaperFnoMarketController.Expiries(
                UNDERLYING, status, null, List.of()));
        }
        var valid = CustomerPaperFnoMarketController.validExpiries(
            listing.expiries(), clock.instant().atZone(IST).toLocalDate());
        return noStore(new CustomerPaperFnoMarketController.Expiries(
            UNDERLYING, valid.isEmpty() ? "NO_LISTED_EXPIRIES" : "VERIFIED",
            listing.observedAt(), valid));
    }

    @GetMapping("/market")
    public ResponseEntity<CustomerPaperFnoMarketController.Market> market(
            @RequestParam("expiry") @DateTimeFormat(iso=DateTimeFormat.ISO.DATE)
            LocalDate expiry, Authentication auth) {
        requireDisplay(auth);
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
        var contracts = CustomerPaperFnoMarketController.providerRead(
            () -> source.currentFnoOptionContracts(UNDERLYING, expiry));
        var chain = CustomerPaperFnoMarketController.providerRead(
            () -> source.currentFnoOptionChain(UNDERLYING, expiry));
        if (chain == null || !"UPSTOX_ANALYTICS".equals(chain.source()) ||
                chain.retrievedAt() == null || chain.exchangeQuoteTimeVerified()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "F&O source provenance unavailable");
        }
        boolean open = sessionCalendar.isInsideFnoSession(now);
        var strikes = CustomerPaperFnoMarketController.select(
            contracts, chain, stream, open, false);
        boolean fresh = strikes.stream().anyMatch(s ->
            s.call().quoteFresh() || s.put().quoteFresh());
        String status = !open ? "MARKET_CLOSED" :
            fresh ? "FRESH_QUOTES" : "NO_FRESH_QUOTES";
        // This market board has NO execution capability and NO paper rights.
        return noStore(CustomerPaperFnoMarketController.marketResponse(
            expiry, chain.retrievedAt(), status, true, false, strikes));
    }

    private void requireDisplay(Authentication auth) {
        requireCustomer(auth);
        if (!displayRightsVerified) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Customer F&O display rights not verified");
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
