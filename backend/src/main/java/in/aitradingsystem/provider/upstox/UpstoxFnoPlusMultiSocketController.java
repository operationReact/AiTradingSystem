package in.aitradingsystem.provider.upstox;

import in.aitradingsystem.marketdata.MarketSessionCalendar;
import in.aitradingsystem.tradingrooms.FnoFuturesContractRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Protected operator-only source-verified opt-in PLUS socket expansion.
 * No public/customer-facing subscribe, execution or permissions endpoint.
 */
@RestController
@RequestMapping("/api/admin/market-data/upstox-analytics/fno/plus")
@ConditionalOnProperty(name = {
    "minttick.upstox-fno.v3-feed-enabled",
    "minttick.upstox-fno.plus-full-capacity-verified",
    "minttick.upstox-fno.plus-multi-socket-enabled"
}, havingValue = "true")
public final class UpstoxFnoPlusMultiSocketController {
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private final UpstoxFnoPlusMultiSocketPool pool;
    private final UpstoxAnalyticsSource source;
    private final FnoFuturesContractRegistry registry;
    private final MarketSessionCalendar calendar;
    private final boolean displayRights;
    private final Clock clock;

    @Autowired
    public UpstoxFnoPlusMultiSocketController(
            UpstoxFnoPlusMultiSocketPool pool,
            UpstoxAnalyticsSource source,
            FnoFuturesContractRegistry registry,
            MarketSessionCalendar calendar,
            @Value("${minttick.upstox-fno.display-rights-verified:false}")
            boolean displayRights) {
        this(pool, source, registry, calendar, displayRights, Clock.systemUTC());
    }

    UpstoxFnoPlusMultiSocketController(UpstoxFnoPlusMultiSocketPool pool,
            UpstoxAnalyticsSource source,
            FnoFuturesContractRegistry registry,
            MarketSessionCalendar calendar,
            boolean displayRights, Clock clock) {
        this.pool = pool;
        this.source = source;
        this.registry = registry;
        this.calendar = calendar;
        this.displayRights = displayRights;
        this.clock = clock;
    }

    @GetMapping("/status")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    public ResponseEntity<UpstoxFnoPlusMultiSocketPool.PoolStatus> status() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(pool.status());
    }

    @PostMapping("/expand")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    public ResponseEntity<UpstoxFnoPlusMultiSocketPool.PoolStatus> expand(
            @RequestParam("underlyingKey") String underlying,
            @RequestParam("expiry")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate expiry) {
        Instant now = clock.instant();
        LocalDate today = now.atZone(IST).toLocalDate();
        if (underlying == null ||
                !underlying.matches("(?:NSE_EQ\\|[A-Za-z0-9._-]{1,64}|NSE_INDEX\\|[A-Za-z0-9 ._-]{1,64})")
                || expiry == null || expiry.isBefore(today)
                || expiry.isAfter(today.plusDays(90))) {
            throw new IllegalArgumentException("UPSTOX_PLUS_INVALID_UNDERLYING_EXPIRY");
        }
        if (!displayRights || !source.enabled() ||
                !calendar.isInsideFnoSession(now)) {
            throw new IllegalStateException("UPSTOX_PLUS_SOURCE_RIGHTS_OR_SESSION_UNVERIFIED");
        }
        var catalog = registry.universeStatus();
        if (!"LOADED".equals(catalog.state()) || catalog.sha256() == null
                || catalog.fetchedAt() == null
                || now.isBefore(catalog.fetchedAt())
                || now.isAfter(catalog.fetchedAt().plus(Duration.ofHours(36)))) {
            throw new IllegalStateException("UPSTOX_PLUS_BOD_NOT_VERIFIED");
        }
        var candidates = registry.livePlanningCandidates(underlying);
        if (candidates.isEmpty()) {
            throw new IllegalStateException("UPSTOX_PLUS_UNDERLYING_NOT_LISTED");
        }
        // Chain is for ATM ordering and listed identity matching only.
        // It never validates a live bid/ask or trading rights.
        var chain = source.currentFnoOptionChain(underlying, expiry);
        var listed = UpstoxFnoListedSubscriptionPlanner.plan(
            candidates, chain, underlying, expiry, today,
            UpstoxFnoV3LiveFeedService.MAX_INCREMENTAL_KEYS);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .cacheControl(CacheControl.noStore())
            .body(pool.subscribeVerified(listed));
    }
}
