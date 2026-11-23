package in.aitradingsystem.provider.upstox;

import in.aitradingsystem.marketdata.MarketSessionCalendar;
import in.aitradingsystem.tradingrooms.CustomerFnoExpiryCatalog;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Bounded, source-rights-gated, read-only NIFTY V3 subscription manager.
 *
 * Uses the existing SSM Upstox source and SAME in-memory market feed as the
 * operator, paper and customer discovery APIs. Starts at most one 64-key
 * connection after market open. Splits that fixed budget across the three
 * nearest listed expiries so other upcoming weeks do not remain all-dash.
 * Stops only its own socket on session close. No order, persistence,
 * redistribution, training or automatic flag activation is performed.
 *
 * Explicit opt-in and independently reviewed display rights are BOTH required.
 */
@Service
@ConditionalOnProperty(name = {"minttick.upstox-fno.auto-market-feed-enabled",
        "minttick.upstox-fno.v3-feed-enabled"}, havingValue = "true")
public final class UpstoxFnoAutoMarketFeed {
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Duration RETRY_DELAY = Duration.ofMinutes(3);
    private static final String MANUAL_CONTROL = "MANUAL_CONTROL";
    private static final Logger log = LoggerFactory.getLogger(UpstoxFnoAutoMarketFeed.class);

    private final UpstoxAnalyticsSource source;
    private final UpstoxFnoV3LiveFeedService feed;
    private final CustomerFnoExpiryCatalog catalog;
    private final MarketSessionCalendar calendar;
    private final Clock clock;
    private final boolean displayRightsVerified;
    private boolean owned;
    private LocalDate activeExpiry;
    private Instant sessionStartedAt;
    private Instant nextAttempt = Instant.EPOCH;
    private String state = "WAITING_FOR_SESSION";
    private String lastLoggedState;

    @Autowired
    public UpstoxFnoAutoMarketFeed(UpstoxAnalyticsSource source,
            UpstoxFnoV3LiveFeedService feed, CustomerFnoExpiryCatalog catalog,
            MarketSessionCalendar calendar,
            @Value("${minttick.upstox-fno.display-rights-verified:false}")
            boolean displayRightsVerified) {
        this(source, feed, catalog, calendar, Clock.systemUTC(),
            displayRightsVerified);
    }

    UpstoxFnoAutoMarketFeed(UpstoxAnalyticsSource source,
            UpstoxFnoV3LiveFeedService feed, CustomerFnoExpiryCatalog catalog,
            MarketSessionCalendar calendar, Clock clock,
            boolean displayRightsVerified) {
        this.source = source;
        this.feed = feed;
        this.catalog = catalog;
        this.calendar = calendar;
        this.clock = clock;
        this.displayRightsVerified = displayRightsVerified;
    }

    public record State(String status, boolean autoOwned, LocalDate expiry,
                        Instant nextRetryAt) {}

    public synchronized State status() {
        return new State(state, owned, activeExpiry, nextAttempt);
    }

    @Scheduled(fixedDelayString = "${minttick.upstox-fno.auto-market-feed-check-ms:30000}",
               initialDelayString = "${minttick.upstox-fno.auto-market-feed-initial-ms:5000}")
    public void check() {
        reconcile();
        logStateChange();
    }

    private void logStateChange() {
        if (!state.equals(lastLoggedState)) {
            lastLoggedState = state;
            log.info("Upstox F&O auto feed state={}", state);
        }
    }

    /** Safe for single-thread scheduled invocations and explicit test probes. */
    synchronized void reconcile() {
        Instant now = clock.instant();
        if (!displayRightsVerified || !source.enabled()) {
            stopOwned();
            state = "SOURCE_OR_DISPLAY_RIGHTS_UNVERIFIED";
            return;
        }
        if (!calendar.isInsideFnoSession(now)) {
            stopOwned();
            nextAttempt = Instant.EPOCH;
            state = "MARKET_CLOSED";
            return;
        }
        // Never preempt or shut down a different explicit operator session.
        var socket = feed.status();
        String socketState = socket.state();
        boolean active = List.of("CONNECTING", "SUBSCRIBING",
                "CONNECTED_AWAITING_TICKS", "CONNECTED", "RECONNECTING")
            .contains(socketState);
        if (!owned && active) {
            state = MANUAL_CONTROL;
            return;
        }
        if (owned && active && activeExpiry != null &&
                !activeExpiry.isBefore(now.atZone(IST).toLocalDate())) {
            // Watchdog: a connected-but-silent socket is not live market data.
            // Allow up to 90 seconds for initial subscribe and market bursts.
            Instant lastFrame = socket.lastFrameObservedAt();
            Instant reference = lastFrame == null ? sessionStartedAt : lastFrame;
            if (reference != null &&
                    !now.isBefore(reference.plusSeconds(90))) {
                stopOwned();
                nextAttempt = now.plus(RETRY_DELAY);
                state = "STALE_SOCKET_BACKOFF";
                return;
            }
            state = "AUTO_" + socketState;
            return;
        }
        if (now.isBefore(nextAttempt)) {
            state = "BACKOFF";
            return;
        }
        // Closed, disconnected and retry-limit states are all retried only
        // under a bounded cooldown. A failed source read cannot spin on SSM.
        nextAttempt = now.plus(RETRY_DELAY);
        if (owned) {
            stopOwned();
        }
        try {
            var listing = catalog.listing(now);
            if (listing == null || !listing.usable()) {
                state = "EXPIRY_SOURCE_UNAVAILABLE";
                return;
            }
            LocalDate today = now.atZone(IST).toLocalDate();
            LocalDate expiry = listing.expiries().stream()
                .filter(d -> d != null && !d.isBefore(today)
                        && !d.isAfter(today.plusDays(90)))
                .sorted().findFirst().orElse(null);
            if (expiry == null) {
                state = "NO_LISTED_EXPIRIES";
                return;
            }
            // Each selected expiry is independently cross-checked against real
            // Upstox listed CE/PE identities and the provider chain. The total
            // budget remains 64 keys / one AUTO-owned socket; no dynamic
            // takeover or subscription modification of an active connection.
            var keys = planUpcomingExpiryKeys(source, listing.expiries(), today);
            if (keys.isEmpty() || keys.size() > UpstoxFnoV3LiveFeedService.MAX_SUBSCRIPTIONS) {
                state = "NO_MATCHING_CONTRACTS";
                return;
            }
            feed.start(keys);
            activeExpiry = expiry;
            sessionStartedAt = now;
            owned = true;
            state = "AUTO_CONNECTING";
        } catch (RuntimeException failure) {
            // Log only the type: messages may carry upstream bodies or one-use URLs.
            log.warn("Upstox F&O auto feed start failed: {}", failure.getClass().getSimpleName());
            state = "SOURCE_OR_SOCKET_UNAVAILABLE";
        }
    }

    /**
     * The bounded AUTO feed previously spent all 64 slots on only the first
     * weekly expiry. The customer can select later expiries, but their V3
     * premiums then remain empty despite a healthy CONNECTED feed.
     *
     * Reserve complete CE/PE pairs for each of up to three currently listed
     * expiries. Future expiries are best-effort: an unavailable later chain
     * does not destroy a verified nearest-expiry subscription. A missing
     * nearest source still fails closed. There is NO socket side effect here.
     */
    static List<String> planUpcomingExpiryKeys(UpstoxAnalyticsSource source,
            List<LocalDate> listed, LocalDate today) {
        if (listed == null || today == null) return List.of();
        var expiries = listed.stream()
            .filter(d -> d != null && !d.isBefore(today)
                && !d.isAfter(today.plusDays(90)))
            .distinct().sorted().limit(3).toList();
        if (expiries.isEmpty()) return List.of();
        final int limit = UpstoxFnoV3LiveFeedService.MAX_SUBSCRIPTIONS;
        final int evenPerExpiry = (limit / expiries.size() / 2) * 2;
        final int leftoverPairs = (limit - evenPerExpiry * expiries.size()) / 2;
        var selected = new ArrayList<String>();
        for (int i = 0; i < expiries.size(); i++) {
            final int quota = evenPerExpiry + (i < leftoverPairs ? 2 : 0);
            try {
                selected.addAll(UpstoxFnoV3LiveOperatorController
                    .listedNearAtmKeys(source, expiries.get(i), quota));
            } catch (RuntimeException missingLaterSource) {
                if (i == 0) throw missingLaterSource;
                // A later-week source gap cannot authorize made-up keys.
                // The nearer verified contracts retain the source budget.
            }
        }
        if (selected.isEmpty() || selected.size() > limit ||
                Set.copyOf(selected).size() != selected.size()) {
            return List.of();
        }
        return List.copyOf(selected);
    }

    private void stopOwned() {
        if (owned) {
            try {
                feed.stop();
            } finally {
                owned = false;
                activeExpiry = null;
                sessionStartedAt = null;
            }
        }
    }
}
