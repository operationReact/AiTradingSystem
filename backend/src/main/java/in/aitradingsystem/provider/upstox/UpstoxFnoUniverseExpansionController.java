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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operator-only, explicitly authorized BOD+chain expansion of the CURRENT
 * read-only Upstox V3 socket. Never reconnects, preempts the existing AUTO
 * session, sends LIVE orders or switches provider account permissions.
 */
@RestController
@RequestMapping("/api/admin/market-data/upstox-analytics/fno/live")
@ConditionalOnProperty(name = "minttick.upstox-fno.v3-feed-enabled", havingValue = "true")
public class UpstoxFnoUniverseExpansionController {
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private final FnoFuturesContractRegistry registry;
    private final UpstoxAnalyticsSource source;
    private final UpstoxFnoV3LiveFeedService socket;
    private final MarketSessionCalendar calendar;
    private final Clock clock;
    private final boolean displayRightsVerified;
    private Instant lastExpansionAt = Instant.EPOCH;

    public record Result(String state, String provenance, String instrumentMasterSha,
            int requestedListedKeys, int activeSubscriptions,
            int observedInstruments, int freshDepthQualifiedQuotes,
            boolean liveOrdersEnabled) {}

    @Autowired
    public UpstoxFnoUniverseExpansionController(FnoFuturesContractRegistry registry,
            UpstoxAnalyticsSource source, UpstoxFnoV3LiveFeedService socket,
            MarketSessionCalendar calendar,
            @Value("${minttick.upstox-fno.display-rights-verified:false}")
            boolean displayRightsVerified) {
        this(registry, source, socket, calendar, Clock.systemUTC(),
            displayRightsVerified);
    }

    UpstoxFnoUniverseExpansionController(FnoFuturesContractRegistry registry,
            UpstoxAnalyticsSource source, UpstoxFnoV3LiveFeedService socket,
            MarketSessionCalendar calendar, Clock clock,
            boolean displayRightsVerified) {
        this.registry = registry;
        this.source = source;
        this.socket = socket;
        this.calendar = calendar;
        this.clock = clock;
        this.displayRightsVerified = displayRightsVerified;
    }

    @PostMapping("/expand-universe")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    public synchronized ResponseEntity<Result> expand(
            @RequestParam("underlyingKey") String underlyingKey,
            @RequestParam("expiry") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate expiry) {
        Instant now = clock.instant();
        LocalDate today = now.atZone(IST).toLocalDate();
        if (underlyingKey == null || !underlyingKey.matches(
                "(?:NSE_EQ\\|[A-Za-z0-9._-]{1,64}|NSE_INDEX\\|[A-Za-z0-9 ._-]{1,64})") ||
                expiry == null || expiry.isBefore(today) ||
                expiry.isAfter(today.plusDays(90))) {
            throw new IllegalArgumentException("FNO_UNIVERSE_EXPAND_INVALID_INPUT");
        }
        if (!displayRightsVerified || !source.enabled())
            throw new IllegalStateException("FNO_EXPAND_SOURCE_RIGHTS_NOT_VERIFIED");
        if (!calendar.isInsideFnoSession(now))
            throw new IllegalStateException("FNO_EXPAND_MARKET_CLOSED");
        if (!"CONNECTED".equals(socket.status().state()))
            throw new IllegalStateException("FNO_EXPAND_SOCKET_NOT_CONNECTED");
        if (now.isBefore(lastExpansionAt.plus(Duration.ofSeconds(5))))
            throw new IllegalStateException("FNO_EXPAND_COOLDOWN");
        var catalog = registry.universeStatus();
        if (!"LOADED".equals(catalog.state()) || catalog.sha256() == null ||
                catalog.fetchedAt() == null ||
                now.isBefore(catalog.fetchedAt()) ||
                now.isAfter(catalog.fetchedAt().plus(Duration.ofHours(36)))) {
            throw new IllegalStateException("FNO_EXPAND_CATALOG_NOT_VERIFIED");
        }
        var candidates = registry.livePlanningCandidates(underlyingKey);
        if (candidates.isEmpty()) {
            throw new IllegalStateException("FNO_EXPAND_UNDERLYING_UNLISTED");
        }
        // The HTTP source is used for identity/ATM ordering ONLY. Its chain
        // snapshot cannot make broker orders or PAPER bid/ask depth eligible.
        var chain = source.currentFnoOptionChain(underlyingKey, expiry);
        var listed = UpstoxFnoListedSubscriptionPlanner.plan(
            candidates, chain, underlyingKey, expiry, today,
            UpstoxFnoV3LiveFeedService.MAX_INCREMENTAL_KEYS);
        var outcome = socket.subscribeAdditionalListed(listed);
        lastExpansionAt = now;
        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .cacheControl(CacheControl.noStore())
            .body(new Result("V3_LISTED_EXPANSION_REQUEST_SENT",
                "UPSTOX_BOD_AND_OPTION_CHAIN",
                catalog.sha256(), listed.size(), outcome.subscriptions(),
                outcome.observedInstruments(),
                outcome.freshExchangeTimedQuotes(), false));
    }
}
