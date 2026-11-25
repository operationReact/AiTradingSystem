package in.aitradingsystem.provider.angelone;

import in.aitradingsystem.instrument.Instrument;
import in.aitradingsystem.marketdata.IndianCashMarketCalendar;
import in.aitradingsystem.marketdata.MarketSessionCalendar;
import jakarta.annotation.PreDestroy;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Private multi-instrument WebSocket V2 LTP stream for the server-side application
 * feed. One socket per feed subscribes every watched canonical token (up to the
 * provider's per-subscription limit), and each token keeps its own verified tick.
 * Only the server sees JWT/feedToken. No order paths, URL token query parameters,
 * bearer propagation or public WebSocket endpoint.
 *
 * Existing JSON API edge samples this verified cache; a direct phone WebSocket
 * is NOT provided by the current API Gateway Lambda proxy.
 */
@Service
public class CustomerAngelOneLiveQuoteService {
    private static final URI BROKER = URI.create("wss://smartapisocket.angelone.in/smart-stream");
    private static final Duration FUTURE_SKEW = Duration.ofSeconds(2);
    private static final Duration RECONNECT_BACKOFF = Duration.ofSeconds(5);
    private static final Duration MAX_CONNECTION_IDLE = Duration.ofSeconds(45);
    /** WebSocket V2: 1000 LTP subscriptions per connection, up to 3 per client.
     * REST FULL quotes have a separate 50-token-per-request limit.
     */
    static final int MAX_WATCHED_PER_FEED = 1000;
    static final int MAX_WEBSOCKETS_PER_CLIENT = 3;
    private static final int FRAME_LENGTH = 51;

    private final CustomerAngelOneSessionService sessions;
    private final MarketSessionCalendar calendar;
    private final HttpClient client;
    private final Clock clock;
    private record FeedKey(UUID user, int shard) {}
    private final ConcurrentHashMap<FeedKey, Feed> feeds = new ConcurrentHashMap<>();
    private final ScheduledExecutorService heartbeats = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "minttick-private-readonly-heartbeats");
        thread.setDaemon(true);
        return thread;
    });

    private final int watchCap;
    /** Shared market-data account whose feed serves every customer's reads; null when unset. */
    private final UUID ownerUser;

    @Autowired
    public CustomerAngelOneLiveQuoteService(CustomerAngelOneSessionService sessions,
            MarketSessionCalendar calendar,
            @Value("${minttick.owner-feed.ws-token-cap:" + MAX_WATCHED_PER_FEED + "}") int watchCap,
            @Value("${minttick.owner-feed.user-id:}") String ownerUserId) {
        this(sessions, calendar, HttpClient.newHttpClient(), Clock.systemUTC(), watchCap,
                ownerUserId == null || ownerUserId.isBlank() ? null : UUID.fromString(ownerUserId.trim()));
    }

    CustomerAngelOneLiveQuoteService(CustomerAngelOneSessionService sessions,
            MarketSessionCalendar calendar, HttpClient client, Clock clock) {
        this(sessions, calendar, client, clock, MAX_WATCHED_PER_FEED, null);
    }

    CustomerAngelOneLiveQuoteService(CustomerAngelOneSessionService sessions,
            MarketSessionCalendar calendar, HttpClient client, Clock clock, int watchCap, UUID ownerUser) {
        if (watchCap < 1) throw new IllegalArgumentException("watchCap must be positive");
        this.sessions = sessions;
        this.calendar = calendar;
        this.client = client;
        this.clock = clock;
        // Enforce the per-WebSocket subscription quota, not the REST quote batch size.
        this.watchCap = boundedWatchCap(watchCap);
        this.ownerUser = ownerUser;
        heartbeats.scheduleAtFixedRate(this::heartbeat, 10, 10, TimeUnit.SECONDS);
    }

    static int boundedWatchCap(int requested) {
        if (requested < 1) throw new IllegalArgumentException("watchCap must be positive");
        return Math.min(requested, MAX_WATCHED_PER_FEED);
    }

    /** Live is the ONLY state in which a price is returned. */
    public record LiveQuote(long instrumentId, String exchange, String tradingSymbol,
            BigDecimal ltp, Instant exchangeAt, Instant receivedAt, String status, String source) {}

    public LiveQuote quote(UUID user, Instrument instrument) {
        return snapshot(user, List.of(instrument)).get(0);
    }

    /** Personal broker reads stay on their own single connection. */
    public List<LiveQuote> snapshot(UUID user, List<Instrument> instruments) {
        return snapshotShard(user, 0, instruments, true);
    }

    /**
     * Route application-owned equities by their ALREADY SUBSCRIBED token/venue,
     * not their position in the caller's request. The warmer sends a 2756-row
     * catalogue, while mobile pages and single-stock detail calls request small
     * unrelated subsets. Using request offsets would always send those subsets
     * to shard zero, dropping valid quotes held on shards one or two.
     *
     * Serialize assignment of new watches so overlapping warm/page requests
     * cannot over-allocate the three broker-limited connections.
     */
    synchronized List<LiveQuote> snapshotApplication(UUID user, List<Instrument> instruments) {
        if (instruments.isEmpty()) return List.of();
        Map<Integer, List<Instrument>> byShard = new TreeMap<>();
        Map<Integer, Integer> reserved = new HashMap<>();
        Map<Long, LiveQuote> results = new HashMap<>();
        for (Instrument instrument : instruments) {
            if (!eligible(instrument)) {
                results.put(instrument.id(), empty(instrument, "UNAVAILABLE"));
                continue;
            }
            String tokenKey = key(venue(instrument), instrument.symbolToken());
            int target = -1;
            // First reuse a subscription on ANY shard, including shard two.
            for (int shard = 0; shard < MAX_WEBSOCKETS_PER_CLIENT; shard++) {
                Feed existing = feeds.get(new FeedKey(user, shard));
                Watch subscribed = existing == null ? null : existing.watches.get(tokenKey);
                if (subscribed != null && subscribed.instrument.id() == instrument.id()) {
                    target = shard;
                    break;
                }
            }
            if (target < 0) {
                // New tokens fill free capacity without displacing existing
                // subscriptions; a full roster remains a hard unpriced state.
                for (int shard = 0; shard < MAX_WEBSOCKETS_PER_CLIENT; shard++) {
                    Feed existing = feeds.get(new FeedKey(user, shard));
                    int active = existing == null ? 0 : existing.watches.size();
                    if (active + reserved.getOrDefault(shard, 0) < watchCap) {
                        target = shard;
                        reserved.merge(shard, 1, Integer::sum);
                        break;
                    }
                }
            }
            if (target < 0) {
                results.put(instrument.id(), empty(instrument, "UNAVAILABLE"));
            } else {
                byShard.computeIfAbsent(target, ignored -> new ArrayList<>()).add(instrument);
            }
        }
        for (Map.Entry<Integer, List<Instrument>> group : byShard.entrySet()) {
            for (LiveQuote quote : snapshotShard(user, group.getKey(), group.getValue(), false)) {
                results.put(quote.instrumentId(), quote);
            }
        }
        return ordered(instruments, results);
    }

    private List<LiveQuote> snapshotShard(UUID user, int shard,
            List<Instrument> instruments, boolean allowPersonalOwnerSharing) {
        Instant now = clock.instant();
        // Cache the session boundary per request; a 3000-stock batch must
        // not issue 3000 holiday/session lookups through PostgreSQL.
        Instant[] sessionOpenMemo = new Instant[1];
        if (!calendar.isInsideCashSession(now)) {
            disconnect(user);
            return instruments.stream().map(i -> empty(i, "MARKET_CLOSED")).toList();
        }
        Map<Long, LiveQuote> results = new HashMap<>();
        List<Instrument> eligible = new ArrayList<>();
        for (Instrument instrument : instruments) {
            if (eligible(instrument)) eligible.add(instrument);
            else results.put(instrument.id(), empty(instrument, "UNAVAILABLE"));
        }
        if (eligible.isEmpty()) return ordered(instruments, results);
        Feed ownerFeed = !allowPersonalOwnerSharing || ownerUser == null || ownerUser.equals(user)
            ? null : feeds.get(new FeedKey(ownerUser, 0));
        if (ownerFeed != null && ownerFeed.state == State.OPEN) {
            for (Instrument instrument : eligible) {
                boolean watched = watch(ownerFeed, instrument);
                results.put(instrument.id(), watched ? read(ownerFeed, instrument, now, sessionOpenMemo)
                    : empty(instrument, "UNAVAILABLE"));
            }
            return ordered(instruments, results);
        }
        var credentials = sessions.credentialsForStream(user);
        if (credentials == null || !credentials.expiresAt().isAfter(now)) {
            disconnect(user);
            for (Instrument instrument : eligible) {
                results.put(instrument.id(), empty(instrument, "BROKER_CONNECTION_REQUIRED"));
            }
            return ordered(instruments, results);
        }
        Feed feed = feedFor(new FeedKey(user, shard), credentials, now, eligible);
        for (Instrument instrument : eligible) {
            boolean watched = feed != null && watch(feed, instrument);
            results.put(instrument.id(), watched ? read(feed, instrument, now, sessionOpenMemo)
                : empty(instrument, "UNAVAILABLE"));
        }
        return ordered(instruments, results);
    }

    private static List<LiveQuote> ordered(List<Instrument> instruments, Map<Long, LiveQuote> results) {
        return instruments.stream()
            .map(i -> results.getOrDefault(i.id(), empty(i, "UNAVAILABLE"))).toList();
    }

    private static LiveQuote empty(Instrument instrument, String status) {
        return new LiveQuote(instrument.id(), instrument.exchange(),
                instrument.tradingSymbol(), null, null, null, status, null);
    }

    private LiveQuote read(Feed feed, Instrument instrument, Instant now,
            Instant[] sessionOpenMemo) {
        if (feed.state == State.CLOSED) return empty(instrument, "RECONNECTING");
        Watch watch = feed.watches.get(key(venue(instrument), instrument.symbolToken()));
        Tick tick = watch == null ? null : watch.latest;
        if (feed.state != State.OPEN || tick == null) return empty(instrument, "CONNECTING");
        // SmartAPI LTP frames arrive only on trades, so a quiet stock legitimately has an
        // older last trade. Such a price stays valid for the rest of the session; a tick
        // from a previous session is not.
        Instant sessionOpen = sessionOpenMemo[0];
        if (sessionOpen == null) {
            sessionOpen = sessionOpen(now);
            sessionOpenMemo[0] = sessionOpen;
        }
        if (tick.exchangeAt.isAfter(now.plus(FUTURE_SKEW))
                || tick.exchangeAt.isBefore(sessionOpen)
                || Duration.between(feed.lastHeartbeat, now).compareTo(MAX_CONNECTION_IDLE) > 0) {
            return empty(instrument, "STALE");
        }
        return new LiveQuote(instrument.id(), instrument.exchange(),
                instrument.tradingSymbol(), tick.price, tick.exchangeAt,
                tick.receivedAt, "LIVE", "ANGEL_ONE_SMARTAPI_WS_V2");
    }

    private synchronized Feed feedFor(FeedKey key,
            CustomerAngelOneSessionService.StreamCredentials credentials,
            Instant now, List<Instrument> requested) {
        Feed feed = feeds.get(key);
        if (feed != null && !feed.credentials.jwt().equals(credentials.jwt())) {
            disconnect(key);
            feed = null;
        }
        if (feed != null && feed.state == State.CLOSED
                && Duration.between(feed.startedAt, now).compareTo(RECONNECT_BACKOFF) >= 0) {
            disconnect(key);
            feed = null;
        }
        if (feed != null) return feed;
        // Quota is per Angel One client code, not all MintTick accounts combined.
        long sameAccount = feeds.values().stream()
            .filter(f -> f.credentials.clientCode().equals(credentials.clientCode())).count();
        if (sameAccount >= MAX_WEBSOCKETS_PER_CLIENT) return null;
        Feed candidate = new Feed(credentials, now);
        // Populate the bounded shard before socket onOpen so subscriptions are
        // sent in one batched control message, not 1000 individual messages.
        for (Instrument instrument : requested) {
            if (eligible(instrument) && candidate.watches.size() < watchCap) {
                candidate.watches.putIfAbsent(
                    key(venue(instrument), instrument.symbolToken()),
                    new Watch(instrument, venue(instrument)));
            }
        }
        Feed existing = feeds.putIfAbsent(key, candidate);
        if (existing != null) return existing;
        start(key, candidate);
        return candidate;
    }

    /** Adds the token to the feed's watch set, subscribing live when the socket is open. */
    private boolean watch(Feed feed, Instrument instrument) {
        int venue = venue(instrument);
        String key = key(venue, instrument.symbolToken());
        synchronized (feed.sendLock) {
            Watch existing = feed.watches.get(key);
            if (existing != null) return existing.instrument.id() == instrument.id();
            if (feed.watches.size() >= watchCap) return false;
            Watch added = new Watch(instrument, venue);
            feed.watches.put(key, added);
            if (feed.state == State.OPEN && feed.socket != null) {
                sendControl(feed, feed.socket, subscription(1, List.of(added)));
            }
            return true;
        }
    }

    private void start(FeedKey key, Feed feed) {
        try {
            // Broker authentication is transmitted only in TLS WebSocket headers
            // from the backend, never as query params or client payload.
            client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(8))
                    .header("Authorization", feed.credentials.jwt())
                    .header("x-api-key", feed.credentials.apiKey())
                    .header("x-client-code", feed.credentials.clientCode())
                    .header("x-feed-token", feed.credentials.feedToken())
                    .buildAsync(BROKER, new Listener(key, feed))
                    .whenComplete((ws, error) -> {
                        if (error != null || feeds.get(key) != feed) {
                            closeFeed(feed);
                            if (ws != null) ws.abort();
                        } else {
                            feed.socket = ws;
                        }
                    });
        } catch (RuntimeException e) {
            closeFeed(feed);
        }
    }

    private final class Listener implements WebSocket.Listener {
        private final FeedKey key;
        private final Feed feed;
        Listener(FeedKey key, Feed feed) {
            this.key = key;
            this.feed = feed;
        }
        @Override public void onOpen(WebSocket ws) {
            if (feeds.get(key) != feed) {
                ws.abort();
                return;
            }
            synchronized (feed.sendLock) {
                feed.socket = ws;
                feed.lastHeartbeat = clock.instant();
                try {
                    if (!feed.watches.isEmpty()) {
                        ws.sendText(subscription(1, feed.watches.values()), true);
                    }
                    feed.state = State.OPEN;
                } catch (RuntimeException error) {
                    closeFeed(feed);
                    ws.abort();
                    return;
                }
            }
            ws.request(1);
        }
        @Override public CompletionStage<?> onBinary(WebSocket ws, ByteBuffer packet, boolean last) {
            if (feeds.get(key) != feed) {
                ws.abort();
                return CompletableFuture.completedFuture(null);
            }
            synchronized (feed.parts) {
                if (feed.parts.size() + packet.remaining() > FRAME_LENGTH) {
                    feed.parts.reset();
                    // Invalid provider data must never refresh a price.
                    feed.watches.values().forEach(w -> w.latest = null);
                } else {
                    byte[] buf = new byte[packet.remaining()];
                    packet.get(buf);
                    feed.parts.writeBytes(buf);
                    if (last) {
                        byte[] message = feed.parts.toByteArray();
                        feed.parts.reset();
                        accept(feed, message);
                    }
                }
            }
            ws.request(1);
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletionStage<?> onText(WebSocket ws, CharSequence message, boolean last) {
            // Text control messages are never evidence of an exchange price.
            // Fragmented provider pongs are assembled with a strict size cap.
            synchronized (feed.control) {
                if (feed.control.length() + message.length() > 2048) {
                    feed.control.setLength(0);
                } else {
                    feed.control.append(message);
                    if (last) {
                        if ("pong".contentEquals(feed.control))
                            feed.lastHeartbeat = clock.instant();
                        feed.control.setLength(0);
                    }
                }
            }
            ws.request(1);
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletionStage<?> onPong(WebSocket ws, ByteBuffer message) {
            feed.lastHeartbeat = clock.instant();
            ws.request(1);
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            closeFeed(feed);
            return CompletableFuture.completedFuture(null);
        }
        @Override public void onError(WebSocket ws, Throwable error) {
            closeFeed(feed);
            // No provider payload, JWT or exception detail is logged.
        }
    }

    private void accept(Feed feed, byte[] data) {
        Frame frame = parseFrame(data);
        if (frame == null) return;
        Watch watch = feed.watches.get(key(frame.venue(), frame.token()));
        if (watch == null) return;
        Instant received = clock.instant();
        Tick verified = verify(watch.instrument, frame, received, watch.latest, sessionOpen(received));
        if (verified != null) {
            watch.latest = verified;
            // A verified tick proves this socket is alive even if a proxy
            // drops heartbeat responses. Never change exchangeAt here.
            feed.lastHeartbeat = received;
        }
    }

    private Instant sessionOpen(Instant now) {
        return calendar.session(now.atZone(IndianCashMarketCalendar.ZONE).toLocalDate())
                .open().toInstant();
    }

    /** Pure on-host binary validator, separately unit tested with no socket. */
    static Tick parseTick(Instrument instrument, byte[] data, Instant received, Tick old,
            Instant sessionOpen) {
        Frame frame = parseFrame(data);
        return frame == null ? null : verify(instrument, frame, received, old, sessionOpen);
    }

    record Frame(int venue, String token, long sequence, long epochMillis, long cents) {}

    /** Structural parse of one LTP frame; identity and freshness are checked by {@link #verify}. */
    static Frame parseFrame(byte[] data) {
        if (data == null || data.length != FRAME_LENGTH || data[0] != 1) return null;
        int venue = Byte.toUnsignedInt(data[1]);
        int end = 2;
        while (end < 27 && data[end] != 0) end++;
        String token = new String(data, 2, end - 2, StandardCharsets.US_ASCII);
        if (!token.matches("[0-9]{1,25}")) return null;
        // Require a zero-filled token suffix; do not accept forged prefix matches.
        for (int at = end; at < 27; at++) if (data[at] != 0) return null;
        ByteBuffer fields = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        return new Frame(venue, token, fields.getLong(27), fields.getLong(35), fields.getLong(43));
    }

    static Tick verify(Instrument instrument, Frame frame, Instant received, Tick old,
            Instant sessionOpen) {
        if (venue(instrument) < 0 || frame.venue() != venue(instrument)) return null;
        if (!instrument.symbolToken().equals(frame.token())) return null;
        long sequence = frame.sequence();
        long epochMillis = frame.epochMillis();
        long cents = frame.cents();
        if (sequence < 0 || epochMillis <= 0 || cents <= 0) return null;
        Instant exchangeTime;
        try { exchangeTime = Instant.ofEpochMilli(epochMillis); }
        catch (RuntimeException ex) { return null; }
        // LTP frames arrive only on trades, so a quiet stock's last trade may be minutes old.
        // It is valid for the current session only; earlier-session or future ticks are rejected.
        if (exchangeTime.isAfter(received.plus(FUTURE_SKEW)) || exchangeTime.isBefore(sessionOpen))
            return null;
        if (old != null && (sequence <= old.sequence
                || exchangeTime.isBefore(old.exchangeAt))) return null;
        return new Tick(BigDecimal.valueOf(cents, 2), exchangeTime, received, sequence);
    }

    private static int venue(Instrument instrument) {
        return "NSE_CM".equals(instrument.exchangeSegment()) ? 1
            : "BSE_CM".equals(instrument.exchangeSegment()) ? 3 : -1;
    }

    private static boolean eligible(Instrument instrument) {
        return venue(instrument) > 0 && instrument.symbolToken() != null
            && instrument.symbolToken().matches("[0-9]{1,25}");
    }

    private static String key(int venue, String token) {
        return venue + ":" + token;
    }

    /** SmartAPI subscription/unsubscription message, grouped by exchange type. */
    static String subscription(int action, Collection<Watch> watches) {
        Map<Integer, List<String>> byVenue = new TreeMap<>();
        for (Watch watch : watches) {
            byVenue.computeIfAbsent(watch.venue, v -> new ArrayList<>())
                .add("\"" + watch.instrument.symbolToken() + "\"");
        }
        StringBuilder groups = new StringBuilder();
        for (Map.Entry<Integer, List<String>> entry : byVenue.entrySet()) {
            if (groups.length() > 0) groups.append(',');
            groups.append("{\"exchangeType\":").append(entry.getKey())
                .append(",\"tokens\":[").append(String.join(",", entry.getValue())).append("]}");
        }
        return "{\"correlationID\":\"minttick1\",\"action\":" + action
            + ",\"params\":{\"mode\":1,\"tokenList\":[" + groups + "]}}";
    }

    private void sendControl(Feed feed, WebSocket ws, String message) {
        try {
            ws.sendText(message, true).exceptionally(error -> {
                closeFeed(feed);
                return null;
            });
        } catch (RuntimeException error) {
            closeFeed(feed);
        }
    }

    /** Package-visible for deterministic socket/heartbeat contract tests. */
    void heartbeat() {
        try {
            Instant now = clock.instant();
            for (Map.Entry<FeedKey, Feed> entry : feeds.entrySet()) {
                Feed feed = entry.getValue();
                if (feed.state != State.OPEN || feed.socket == null) continue;
                var latestCredentials = sessions.credentialsForStream(entry.getKey().user());
                if (latestCredentials == null
                        || !latestCredentials.jwt().equals(feed.credentials.jwt())
                        || !feed.credentials.expiresAt().isAfter(now)) {
                    disconnect(entry.getKey());
                    continue;
                }
                // The official SDK uses ping control frames; the published
                // SmartAPI V2 protocol also documents text "ping"/"pong".
                // Support both keepalive paths without inventing any quote.
                feed.socket.sendPing(ByteBuffer.wrap("ping".getBytes(StandardCharsets.US_ASCII)))
                        .exceptionally(error -> {
                    closeFeed(feed);
                    return null;
                });
                feed.socket.sendText("ping", true)
                        .exceptionally(error -> {
                    closeFeed(feed);
                    return null;
                });
            }
        } catch (RuntimeException ignored) {
            // Individual socket failure cannot break the scheduler.
        }
    }

    private static void closeFeed(Feed feed) {
        feed.state = State.CLOSED;
        feed.watches.values().forEach(w -> w.latest = null);
    }

    private void disconnect(FeedKey key) {
        Feed old = feeds.remove(key);
        if (old != null) {
            closeFeed(old);
            if (old.socket != null) old.socket.abort();
        }
    }

    public void disconnect(UUID user) {
        feeds.keySet().stream().filter(key -> key.user().equals(user))
            .toList().forEach(this::disconnect);
    }

    @PreDestroy public void close() {
        heartbeats.shutdownNow();
        feeds.keySet().forEach(this::disconnect);
        client.close();
    }

    private enum State { CONNECTING, OPEN, CLOSED }

    record Tick(BigDecimal price, Instant exchangeAt, Instant receivedAt, long sequence) {}

    static final class Watch {
        final Instrument instrument;
        final int venue;
        volatile Tick latest;
        Watch(Instrument instrument, int venue) {
            this.instrument = instrument;
            this.venue = venue;
        }
    }

    private static final class Feed {
        final CustomerAngelOneSessionService.StreamCredentials credentials;
        final Instant startedAt;
        final Map<String, Watch> watches = new ConcurrentHashMap<>();
        final Object sendLock = new Object();
        final ByteArrayOutputStream parts = new ByteArrayOutputStream(FRAME_LENGTH);
        final StringBuilder control = new StringBuilder(32);
        volatile State state = State.CONNECTING;
        volatile Instant lastHeartbeat;
        volatile WebSocket socket;
        Feed(CustomerAngelOneSessionService.StreamCredentials credentials, Instant at) {
            this.credentials = credentials;
            this.startedAt = at;
            this.lastHeartbeat = at;
        }
    }
}
