package in.aitradingsystem.provider.upstox;

import in.aitradingsystem.marketdata.MarketSessionCalendar;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Explicit, verified-Plus-only read-only WebSocket pool.
 *
 * One existing AUTO/operator-owned V3 socket remains entirely untouched and
 * four optional independently-owned sockets are reserved as supplementary
 * capacity. Five slots are a documented MAX per user, not a guaranteed number
 * of idle slots (other apps may share the same account). The extra sockets
 * are NEVER opened automatically on startup or reconnect of the primary.
 *
 * Full-mode aggregate is restricted to 1500 total local subscriptions until
 * real upstream mixed-mode/account quota receipts are established. This
 * deliberately does NOT multiply the 1500 limit by five.
 */
@Service
@ConditionalOnProperty(name = {
    "minttick.upstox-fno.v3-feed-enabled",
    "minttick.upstox-fno.plus-full-capacity-verified",
    "minttick.upstox-fno.plus-multi-socket-enabled"
}, havingValue = "true")
public final class UpstoxFnoPlusMultiSocketPool {
    static final int TOTAL_UPSTOX_PLUS_CONNECTION_SLOTS = 5;
    static final int EXTRA_SOCKETS = 4;
    static final int MAX_LOCAL_FULL_SUBSCRIPTIONS = 1500;
    static final int PER_EXTRA_SOCKET_BUDGET = 300;

    private final UpstoxAnalyticsSource source;
    private final UpstoxFnoV3LiveFeedService primary;
    private final MarketSessionCalendar calendar;
    private final Clock clock;
    private final boolean displayRights;
    private final List<UpstoxFnoV3LiveFeedService> extra;
    private final Set<String> reserved = new HashSet<>();

    public record Shard(int index, String connectionState,
            int subscriptions, int freshLtp, int freshDepth) {}
    public record PoolStatus(int maximumConnectionsPerUser,
            int locallyManagedConnections, int maximumLocalFullKeys,
            int locallyTrackedSubscriptions,
            List<Shard> shards, boolean liveOrdersEnabled) {}

    @Autowired
    public UpstoxFnoPlusMultiSocketPool(UpstoxAnalyticsSource source,
            UpstoxFnoV3LiveFeedService primary, MarketSessionCalendar calendar,
            @Value("${minttick.upstox-fno.display-rights-verified:false}")
            boolean displayRights) {
        this(source, primary, calendar, Clock.systemUTC(), displayRights,
            java.util.stream.IntStream.range(0, EXTRA_SOCKETS)
                .mapToObj(i -> new UpstoxFnoV3LiveFeedService(source, true))
                .toList());
    }

    UpstoxFnoPlusMultiSocketPool(UpstoxAnalyticsSource source,
            UpstoxFnoV3LiveFeedService primary, MarketSessionCalendar calendar,
            Clock clock, boolean displayRights,
            List<UpstoxFnoV3LiveFeedService> extra) {
        if (extra == null || extra.size() != EXTRA_SOCKETS ||
                Set.copyOf(extra).size() != EXTRA_SOCKETS ||
                extra.stream().anyMatch(s -> s == null || s == primary)) {
            throw new IllegalArgumentException("UPSTOX_PLUS_INVALID_SHARD_CONFIGURATION");
        }
        this.source = source;
        this.primary = primary;
        this.calendar = calendar;
        this.clock = clock;
        this.displayRights = displayRights;
        this.extra = List.copyOf(extra);
    }

    public synchronized PoolStatus status() {
        var shards = new ArrayList<Shard>();
        var primaryStatus = primary.status();
        var primaryQuotes = primary.localStreamingCoverage();
        shards.add(new Shard(0, primaryStatus.state(),
            primaryStatus.subscriptions(),
            primaryQuotes.freshExchangeLtp(),
            primaryQuotes.freshTwoSidedDepth()));
        for (int i = 0; i < extra.size(); i++) {
            var shard = extra.get(i);
            var status = shard.status();
            var quotes = shard.streamingCoverage();
            shards.add(new Shard(i + 1, status.state(),
                status.subscriptions(), quotes.freshExchangeLtp(),
                quotes.freshTwoSidedDepth()));
        }
        int total = shards.stream().mapToInt(Shard::subscriptions).sum();
        return new PoolStatus(TOTAL_UPSTOX_PLUS_CONNECTION_SLOTS,
            TOTAL_UPSTOX_PLUS_CONNECTION_SLOTS,
            MAX_LOCAL_FULL_SUBSCRIPTIONS, total,
            List.copyOf(shards), false);
    }

    /**
     * Package-scoped: only the authenticated operator controller may pass
     * identities it has independently matched to fresh BOD and V2 option
     * chain. Operator requests never edit or stop the primary AUTO socket.
     */
    synchronized PoolStatus subscribeVerified(List<String> listedKeys) {
        Instant now = clock.instant();
        if (!displayRights || !source.enabled() ||
                !calendar.isInsideFnoSession(now)) {
            throw new IllegalStateException("UPSTOX_PLUS_RIGHTS_OR_SESSION_UNVERIFIED");
        }
        if (listedKeys == null || listedKeys.isEmpty()
                || listedKeys.size() > UpstoxFnoV3LiveFeedService.MAX_INCREMENTAL_KEYS
                || listedKeys.stream().anyMatch(k -> k == null ||
                    !k.matches("NSE_FO\\|[A-Za-z0-9._-]{1,64}"))
                || Set.copyOf(listedKeys).size() != listedKeys.size()) {
            throw new IllegalArgumentException("UPSTOX_PLUS_INVALID_LISTED_KEYS");
        }
        if (!"CONNECTED".equals(primary.status().state())) {
            throw new IllegalStateException("UPSTOX_PLUS_PRIMARY_SOCKET_NOT_CONNECTED");
        }
        var existing = status();
        if (existing.locallyTrackedSubscriptions() + listedKeys.size() >
                MAX_LOCAL_FULL_SUBSCRIPTIONS) {
            throw new IllegalStateException("UPSTOX_PLUS_LOCAL_FULL_LIMIT");
        }
        for (String key : listedKeys) {
            if (primary.hasActiveFnoSubscription(key) || reserved.contains(key)) {
                throw new IllegalStateException("UPSTOX_PLUS_DUPLICATE_SUBSCRIPTION");
            }
        }

        // Fill a currently connected supplementary shard before spending
        // another account connection slot. Maximum 300 keys per extra socket.
        UpstoxFnoV3LiveFeedService chosen = null;
        for (var shard : extra) {
            var status = shard.status();
            if ("CONNECTED".equals(status.state()) &&
                    status.subscriptions() + listedKeys.size() <=
                        PER_EXTRA_SOCKET_BUDGET) {
                chosen = shard;
                break;
            }
        }
        if (chosen == null) {
            for (var shard : extra) {
                if ("STOPPED".equals(shard.status().state())) {
                    chosen = shard;
                    break;
                }
            }
        }
        if (chosen == null) {
            throw new IllegalStateException("UPSTOX_PLUS_NO_SAFE_SHARD_AVAILABLE");
        }
        if ("STOPPED".equals(chosen.status().state())) {
            // First 64-key initial batch; creates exactly one additional
            // WebSocket using an independently authorized one-use URI.
            chosen.start(listedKeys);
        } else {
            chosen.subscribeAdditionalListed(listedKeys);
        }
        // Subscription send completion does not prove fresh exchange-timed
        // data; keep identity reservations to prevent cross-shard duplicates.
        reserved.addAll(listedKeys);
        return status();
    }

    /** Supplementary read-only quote access; not an order entitlement. */
    public java.util.Optional<UpstoxFnoV3LiveFeedService.DisplayQuote>
            eligibleSupplementaryQuote(String key) {
        for (var shard : extra) {
            var quote = shard.eligibleDisplayQuote(key);
            if (quote.isPresent()) return quote;
        }
        return java.util.Optional.empty();
    }

    /** Only source-fresh quotes from already-listed supplementary subscriptions. */
    public java.util.Optional<UpstoxFnoV3LiveFeedService.PaperQuote>
            eligibleSupplementaryPaperQuote(String key) {
        for (var shard : extra) {
            var quote = shard.eligiblePaperQuote(key);
            if (quote.isPresent()) return quote;
        }
        return java.util.Optional.empty();
    }

    public boolean hasSupplementarySubscription(String key) {
        return extra.stream().anyMatch(s -> s.hasActiveFnoSubscription(key));
    }

    /** Actual exchange-time completeness, never just websocket send ACK. */
    public UpstoxFnoV3LiveFeedService.StreamingCounts supplementaryCoverage() {
        int subscribed=0, fresh=0, depth=0, absent=0;
        int oi=0, volume=0, greeks=0;
        for (var shard: extra) {
            var c=shard.streamingCoverage();
            subscribed+=c.subscribedNseFno();
            fresh+=c.freshExchangeLtp();
            depth+=c.freshTwoSidedDepth();
            absent+=c.withoutFreshLtp();
            oi+=c.withOi();
            volume+=c.withVolume();
            greeks+=c.withGreeks();
        }
        return new UpstoxFnoV3LiveFeedService.StreamingCounts(
            "SUPPLEMENTARY",subscribed,fresh,depth,absent,
            oi,volume,greeks,clock.instant(),false);
    }

    public List<UpstoxFnoV3LiveFeedService.DisplayQuote> supplementarySnapshot() {
        var out=new ArrayList<UpstoxFnoV3LiveFeedService.DisplayQuote>();
        for (var shard:extra) out.addAll(shard.eligibleDisplaySnapshot());
        return List.copyOf(out);
    }

    @Scheduled(fixedDelayString =
        "${minttick.upstox-fno.plus-pool-check-ms:30000}")
    public synchronized void stopAfterSession() {
        if (displayRights && source.enabled() &&
                calendar.isInsideFnoSession(clock.instant())) return;
        for (var shard : extra) {
            if (!"STOPPED".equals(shard.status().state())) shard.stop();
        }
        reserved.clear();
    }

    @PreDestroy
    public synchronized void shutdown() {
        for (var shard : extra) shard.shutdown();
        reserved.clear();
    }
}
