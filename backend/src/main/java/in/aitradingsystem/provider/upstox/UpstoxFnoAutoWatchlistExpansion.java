package in.aitradingsystem.provider.upstox;

import in.aitradingsystem.marketdata.MarketSessionCalendar;
import in.aitradingsystem.tradingrooms.FnoFuturesContractRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Optional additive-only, source-proven watchlist expansion into the EXISTING
 * one-account Upstox V3 socket. The initial 64-key NIFTY stream starts through
 * its own existing AUTO policy; this service may add up to three further
 * bounded 64-key rounds, without starting/stopping/rotating an active socket.
 *
 * This is a bridge for approved watchlist underlyings, not a surrogate for
 * Mintick Mind's original independently validated model scores or positions.
 * Disabled by default. Requires independent explicit operator configuration,
 * verified BOD/catalog and display rights. Never places broker orders.
 */
@Service
@ConditionalOnProperty(name = {
    "minttick.upstox-fno.v3-feed-enabled",
    "minttick.upstox-fno.auto-market-feed-enabled",
    "minttick.upstox-fno.auto-expand-watchlist-enabled"
}, havingValue = "true")
public final class UpstoxFnoAutoWatchlistExpansion {
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Duration COOLDOWN = Duration.ofMinutes(1);
    private static final Duration FAILURE_BACKOFF = Duration.ofMinutes(3);
    private final UpstoxAnalyticsSource source;
    private final FnoFuturesContractRegistry catalog;
    private final UpstoxFnoV3LiveFeedService feed;
    private final MarketSessionCalendar calendar;
    private final Clock clock;
    private final boolean displayRights;
    private final List<String> configured;
    private final boolean configValid;
    private int nextTarget;
    private Instant nextAttempt = Instant.EPOCH;
    private String lastState = "WAITING_FOR_CONNECTED_SOCKET";

    public record State(String state, int configuredUnderlyings,
            int nextUnderlyingIndex, Instant nextAttemptAt,
            boolean liveTradingEnabled) {}

    @Autowired
    public UpstoxFnoAutoWatchlistExpansion(
            UpstoxAnalyticsSource source,
            FnoFuturesContractRegistry catalog,
            UpstoxFnoV3LiveFeedService feed,
            MarketSessionCalendar calendar,
            @Value("${minttick.upstox-fno.display-rights-verified:false}")
            boolean displayRights,
            @Value("${minttick.upstox-fno.auto-expand-underlyings:}")
            String targetKeys) {
        this(source, catalog, feed, calendar, Clock.systemUTC(),
            displayRights, targetKeys);
    }

    UpstoxFnoAutoWatchlistExpansion(UpstoxAnalyticsSource source,
            FnoFuturesContractRegistry catalog,
            UpstoxFnoV3LiveFeedService feed,
            MarketSessionCalendar calendar, Clock clock,
            boolean displayRights, String targetKeys) {
        this.source = source;
        this.catalog = catalog;
        this.feed = feed;
        this.calendar = calendar;
        this.clock = clock;
        this.displayRights = displayRights;
        List<String> cleaned = new ArrayList<>();
        boolean valid = targetKeys != null && !targetKeys.isBlank();
        if (valid) {
            var seen = new HashSet<String>();
            String[] targets = targetKeys.split(";", -1);
            valid = targets.length <= 8;
            for (String raw : targets) {
                String key = raw.strip();
                if (!key.matches(
                        "(?:NSE_EQ\\|[A-Za-z0-9._-]{1,64}|NSE_INDEX\\|[A-Za-z0-9 ._-]{1,64})") ||
                        !seen.add(key)) {
                    valid = false;
                }
                cleaned.add(key);
            }
        }
        this.configValid = valid;
        this.configured = valid ? List.copyOf(cleaned) : List.of();
    }

    public synchronized State status() {
        return new State(lastState, configured.size(), nextTarget,
            nextAttempt, false);
    }

    @Scheduled(
        fixedDelayString = "${minttick.upstox-fno.auto-expand-check-ms:60000}",
        initialDelayString = "${minttick.upstox-fno.auto-expand-initial-ms:20000}")
    public void tick() { reconcile(); }

    /**
     * Best effort and backpressured. A source or socket error retains existing
     * subscriptions; no retry loop can change broker order permissions.
     */
    synchronized void reconcile() {
        Instant now = clock.instant();
        if (!configValid) {
            lastState = "WATCHLIST_NOT_CONFIGURED_OR_INVALID";
            return;
        }
        if (!displayRights || !source.enabled()) {
            lastState = "DISPLAY_OR_SOURCE_NOT_VERIFIED";
            return;
        }
        if (!calendar.isInsideFnoSession(now)) {
            lastState = "MARKET_CLOSED";
            return;
        }
        if (now.isBefore(nextAttempt)) {
            lastState = "COOLDOWN";
            return;
        }
        var status = feed.status();
        if (!"CONNECTED".equals(status.state())) {
            lastState = "WAITING_FOR_CONNECTED_SOCKET";
            return;
        }
        if (status.subscriptions() >= UpstoxFnoV3LiveFeedService.MAX_SUBSCRIPTIONS) {
            lastState = "CAPACITY_REACHED";
            return;
        }
        var master = catalog.universeStatus();
        if (!"LOADED".equals(master.state()) || master.sha256() == null ||
                master.fetchedAt() == null || now.isBefore(master.fetchedAt()) ||
                now.isAfter(master.fetchedAt().plus(Duration.ofHours(36)))) {
            lastState = "BOD_CATALOG_UNVERIFIED";
            return;
        }

        LocalDate today = now.atZone(IST).toLocalDate();
        String underlying = configured.get(nextTarget % configured.size());
        nextAttempt = now.plus(COOLDOWN);
        try {
            var rows = catalog.livePlanningCandidates(underlying);
            if (rows.isEmpty()) {
                lastState = "UNDERLYING_NOT_LISTED";
                nextAttempt = now.plus(FAILURE_BACKOFF);
                return;
            }
            var expiry = rows.stream()
                .filter(c -> ("CE".equals(c.type()) || "PE".equals(c.type()))
                    && !c.expiry().isBefore(today)
                    && !c.expiry().isAfter(today.plusDays(90)))
                .map(UpstoxFnoInstrumentUniverse.Contract::expiry)
                .distinct().sorted().findFirst().orElse(null);
            if (expiry == null) {
                lastState = "NO_VERIFIED_LISTED_OPTION_EXPIRY";
                nextAttempt = now.plus(FAILURE_BACKOFF);
                return;
            }
            var snapshot = source.currentFnoOptionChain(underlying, expiry);
            var keys = UpstoxFnoListedSubscriptionPlanner.plan(
                rows, snapshot, underlying, expiry, today,
                Math.min(UpstoxFnoV3LiveFeedService.MAX_INCREMENTAL_KEYS,
                    UpstoxFnoV3LiveFeedService.MAX_SUBSCRIPTIONS -
                        status.subscriptions()));
            if (keys.isEmpty()) {
                lastState = "NO_MATCHED_CURRENT_CONTRACTS";
                nextAttempt = now.plus(FAILURE_BACKOFF);
                return;
            }
            // Same connection, exact provider-listed keys. The socket tracks
            // idempotence and never marks any key FRESH until an exchange tick.
            feed.subscribeAdditionalListed(keys);
            nextTarget++;
            lastState = "ADD_SENT_AWAITING_EXCHANGE_TICKS";
        } catch (RuntimeException unavailable) {
            nextAttempt = now.plus(FAILURE_BACKOFF);
            // Stable codes only: no provider messages, credentials or URLs.
            lastState = "PROVIDER_OR_SOCKET_UNAVAILABLE";
        }
    }
}
