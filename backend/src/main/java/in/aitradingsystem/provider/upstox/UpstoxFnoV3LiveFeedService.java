package in.aitradingsystem.provider.upstox;

import jakarta.annotation.PreDestroy;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * One opt-in, read-only Upstox F&O V3 websocket pilot. Starts only after an
 * authenticated operator action and never connects during application startup.
 * No executions, persistence, public prices, training use or user OAuth.
 */
@Service
@ConditionalOnProperty(name = "minttick.upstox-fno.v3-feed-enabled", havingValue = "true")
public class UpstoxFnoV3LiveFeedService {
    static final int MAX_SUBSCRIPTIONS = 64; // pilot cap, below standard Upstox V3 full limits
    static final int MAX_FRAGMENT_BYTES = 1024 * 1024;
    private static final int MAX_RECONNECTS = 5;

    interface Dialer {
        CompletableFuture<WebSocket> connect(URI authorizedOneUseUri, WebSocket.Listener listener);
    }

    public record Status(String state, int subscriptions, int observedInstruments,
                         long decodedFrames, long discardedFrames,
                         Instant lastFrameObservedAt, Instant latestExchangeTradeAt,
                         int reconnectAttempt, boolean providerTradeTimeAvailable,
                         boolean displayRightsVerified, boolean trainingRightsVerified,
                         boolean liveTradingEnabled) {}

    /** Internal-only snapshot; never a broker-authoritative order quote. */
    record Observation(UpstoxFnoV3FeedCodec.Tick tick, Instant observedAt) {}

    private final UpstoxAnalyticsSource source;
    private final Clock clock;
    private final Dialer dialer;
    private final ScheduledExecutorService reconnects;
    private final Map<String, Observation> latest = new ConcurrentHashMap<>();

    private volatile String state = "STOPPED";
    private volatile Set<String> subscribed = Set.of();
    private volatile WebSocket socket;
    private volatile long decodedFrames;
    private volatile long discardedFrames;
    private volatile Instant lastFrameAt;
    private volatile Instant lastTradeAt;
    private volatile int retryCount;
    private volatile boolean wanted;
    private volatile boolean destroyed;

    @Autowired
    public UpstoxFnoV3LiveFeedService(UpstoxAnalyticsSource source) {
        this(source, Clock.systemUTC(),
                (uri, listener) -> HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .build().newWebSocketBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .buildAsync(uri, listener),
                Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "minttick-upstox-fno-stream-reconnect");
                    t.setDaemon(true);
                    return t;
                }));
    }

    UpstoxFnoV3LiveFeedService(UpstoxAnalyticsSource source, Clock clock,
                                Dialer dialer, ScheduledExecutorService reconnects) {
        this.source = source;
        this.clock = clock;
        this.dialer = dialer;
        this.reconnects = reconnects;
    }

    /**
     * The admin controller must first verify these contract keys against the
     * provider's active expiry; no arbitrary untrusted instrument is subscribed.
     */
    public synchronized Status start(List<String> keys) {
        if (destroyed || !source.enabled()
                || keys == null || keys.isEmpty() || keys.size() > MAX_SUBSCRIPTIONS
                || keys.stream().anyMatch(k -> !UpstoxFnoV3FeedCodec.validKey(k))
                || keys.stream().distinct().count() != keys.size()) {
            throw new IllegalArgumentException("UPSTOX_FNO_STREAM_INVALID_START");
        }
        if (wanted) {
            // No second connection; force an explicit stop before switching expiry.
            throw new IllegalStateException("UPSTOX_FNO_STREAM_ALREADY_STARTED");
        }
        subscribed = Set.copyOf(keys);
        wanted = true;
        retryCount = 0;
        decodedFrames = 0;
        discardedFrames = 0;
        lastFrameAt = null;
        lastTradeAt = null;
        latest.clear();
        state = "CONNECTING";
        connectOnce();
        return status();
    }

    private void connectOnce() {
        if (!wanted || destroyed) return;
        final URI authorized;
        try {
            // Always request a FRESH one-use wss URL after every reconnect.
            authorized = source.authorizeFnoMarketFeedV3();
        } catch (RuntimeException ignored) {
            scheduleReconnect();
            return;
        }
        try {
            dialer.connect(authorized, new FeedListener()).whenComplete((ws, error) -> {
                if (error != null || ws == null) {
                    scheduleReconnect();
                } else if (!wanted || destroyed) {
                    ws.sendClose(WebSocket.NORMAL_CLOSURE, "stopped");
                }
            });
        } catch (RuntimeException ignored) {
            // Never include URL/query or provider exception in logs/status.
            scheduleReconnect();
        }
    }

    private synchronized void scheduleReconnect() {
        if (!wanted || destroyed) return;
        socket = null;
        if (retryCount >= MAX_RECONNECTS) {
            wanted = false;
            state = "FAILED_RECONNECT_LIMIT";
            return;
        }
        retryCount++;
        state = "RECONNECTING";
        long seconds = Math.min(60, 5L << (retryCount - 1));
        reconnects.schedule(() -> {
            synchronized (UpstoxFnoV3LiveFeedService.this) {
                if (!wanted || destroyed) return;
                connectOnce();
            }
        }, seconds, TimeUnit.SECONDS);
    }

    public synchronized Status stop() {
        wanted = false;
        state = "STOPPED";
        subscribed = Set.of();
        WebSocket ws = socket;
        socket = null;
        if (ws != null) ws.sendClose(WebSocket.NORMAL_CLOSURE, "operator stop");
        return status();
    }

    public Status status() {
        Instant mostRecentTrade = lastTradeAt;
        return new Status(state, subscribed.size(), latest.size(), decodedFrames,
                discardedFrames, lastFrameAt, mostRecentTrade, retryCount,
                mostRecentTrade != null, false, false, false);
    }

    /** A frame is observation evidence only; never a trade or permission grant. */
    private void consume(byte[] frame) {
        try {
            UpstoxFnoV3FeedCodec.FeedFrame data = UpstoxFnoV3FeedCodec.decode(frame);
            Instant now = clock.instant();
            for (var tick : data.ticks()) {
                if (!subscribed.contains(tick.instrumentKey())) continue;
                // Monotone exchange trade timestamp per instrument; reject future
                // and superseded trades, but keep unknown trade times non-executable.
                if (tick.tradedAt() != null && tick.tradedAt().isAfter(now.plusSeconds(5)))
                    continue;
                latest.compute(tick.instrumentKey(), (key, previous) -> {
                    if (previous != null && previous.tick().tradedAt() != null
                            && (tick.tradedAt() == null ||
                            tick.tradedAt().isBefore(previous.tick().tradedAt())))
                        return previous;
                    return new Observation(tick, now);
                });
                Instant at = tick.tradedAt();
                if (at != null && (lastTradeAt == null || at.isAfter(lastTradeAt))) lastTradeAt = at;
            }
            lastFrameAt = now;
            decodedFrames++;
        } catch (RuntimeException ignored) {
            discardedFrames++;
        }
    }

    private final class FeedListener implements WebSocket.Listener {
        private final ByteArrayOutputStream fragments = new ByteArrayOutputStream();

        @Override
        public void onOpen(WebSocket ws) {
            synchronized (UpstoxFnoV3LiveFeedService.this) {
                if (!wanted || destroyed) {
                    ws.sendClose(WebSocket.NORMAL_CLOSURE, "stopped");
                    return;
                }
                socket = ws;
                state = "SUBSCRIBING";
            }
            ws.sendBinary(ByteBuffer.wrap(UpstoxFnoV3FeedCodec.subscription(
                    "sub", "full", List.copyOf(subscribed))), true)
                    .whenComplete((ignored, err) -> {
                        if (err != null) {
                            scheduleReconnect();
                            ws.abort();
                        } else if (wanted && !destroyed) {
                            state = "CONNECTED_AWAITING_TICKS";
                            retryCount = 0;
                        }
                    });
            ws.request(1);
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket ws, ByteBuffer message, boolean last) {
            try {
                if (message.remaining() > MAX_FRAGMENT_BYTES - fragments.size()) {
                    fragments.reset();
                    discardedFrames++;
                    ws.abort();
                    scheduleReconnect();
                } else {
                    byte[] bytes = new byte[message.remaining()];
                    message.get(bytes);
                    fragments.writeBytes(bytes);
                    if (last) {
                        consume(fragments.toByteArray());
                        fragments.reset();
                        if (wanted) state = "CONNECTED";
                    }
                }
            } catch (RuntimeException ignored) {
                fragments.reset();
                discardedFrames++;
                ws.abort();
                scheduleReconnect();
            }
            ws.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence text, boolean last) {
            // Upstox V3 market prices are protobuf binary; never parse text as ticks.
            discardedFrames++;
            ws.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            if (wanted && !destroyed) scheduleReconnect();
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            if (wanted && !destroyed) scheduleReconnect();
        }
    }

    @PreDestroy
    public void shutdown() {
        synchronized (this) {
            destroyed = true;
            wanted = false;
            state = "STOPPED";
            WebSocket ws = socket;
            socket = null;
            if (ws != null) ws.abort();
        }
        reconnects.shutdownNow();
    }
}
