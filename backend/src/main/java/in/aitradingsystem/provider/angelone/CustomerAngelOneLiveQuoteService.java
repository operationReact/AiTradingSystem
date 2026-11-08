package in.aitradingsystem.provider.angelone;

import in.aitradingsystem.instrument.Instrument;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Private single-instrument WebSocket V2 live quote pilot for manually connected
 * customer broker accounts. Only the server sees JWT/feedToken. No order paths,
 * URL token query parameters, bearer propagation or public WebSocket endpoint.
 *
 * Existing JSON API edge samples this verified cache; a direct phone WebSocket
 * is NOT provided by the current API Gateway Lambda proxy.
 */
@Service
public class CustomerAngelOneLiveQuoteService {
    private static final URI BROKER = URI.create("wss://smartapisocket.angelone.in/smart-stream");
    private static final Duration MAX_TICK_AGE = Duration.ofSeconds(10);
    private static final Duration FUTURE_SKEW = Duration.ofSeconds(2);
    private static final Duration RECONNECT_BACKOFF = Duration.ofSeconds(5);
    private static final Duration MAX_CONNECTION_IDLE = Duration.ofSeconds(45);
    private static final int MAX_CUSTOMER_CONNECTIONS = 3;

    private final CustomerAngelOneSessionService sessions;
    private final MarketSessionCalendar calendar;
    private final HttpClient client;
    private final Clock clock;
    private final ConcurrentHashMap<UUID, Feed> feeds = new ConcurrentHashMap<>();
    private final ScheduledExecutorService heartbeats = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "minttick-private-readonly-heartbeats");
        thread.setDaemon(true);
        return thread;
    });

    @Autowired
    public CustomerAngelOneLiveQuoteService(CustomerAngelOneSessionService sessions,
            MarketSessionCalendar calendar) {
        this(sessions, calendar, HttpClient.newHttpClient(), Clock.systemUTC());
    }

    CustomerAngelOneLiveQuoteService(CustomerAngelOneSessionService sessions,
            MarketSessionCalendar calendar, HttpClient client, Clock clock) {
        this.sessions = sessions;
        this.calendar = calendar;
        this.client = client;
        this.clock = clock;
        heartbeats.scheduleAtFixedRate(this::heartbeat, 10, 10, TimeUnit.SECONDS);
    }

    /** Live is the ONLY state in which a price is returned. */
    public record LiveQuote(long instrumentId, String exchange, String tradingSymbol,
            BigDecimal ltp, Instant exchangeAt, Instant receivedAt, String status, String source) {}

    public LiveQuote quote(UUID user, Instrument instrument) {
        Instant now = clock.instant();
        if (!calendar.isInsideCashSession(now)) {
            disconnect(user);
            return empty(instrument, "MARKET_CLOSED");
        }
        if (!"NSE_CM".equals(instrument.exchangeSegment())
                && !"BSE_CM".equals(instrument.exchangeSegment())) {
            return empty(instrument, "UNAVAILABLE");
        }
        if (!instrument.symbolToken().matches("[0-9]{1,25}"))
            return empty(instrument, "UNAVAILABLE");
        var credentials = sessions.credentialsForStream(user);
        if (credentials == null || !credentials.expiresAt().isAfter(now)) {
            disconnect(user);
            return empty(instrument, "BROKER_CONNECTION_REQUIRED");
        }
        Feed feed = feeds.get(user);
        if (feed != null && (feed.instrument.id() != instrument.id()
                || !feed.credentials.jwt().equals(credentials.jwt()))) {
            disconnect(user);
            feed = null;
        }
        if (feed == null) {
            if (feeds.size() >= MAX_CUSTOMER_CONNECTIONS) return empty(instrument, "UNAVAILABLE");
            Feed candidate = new Feed(instrument, credentials, now);
            Feed existing = feeds.putIfAbsent(user, candidate);
            feed = existing == null ? candidate : existing;
            if (existing != null && existing.instrument.id() != instrument.id())
                return empty(instrument, "RECONNECTING");
            if (existing == null) start(user, candidate);
        }
        if (feed.state == State.CLOSED) {
            if (Duration.between(feed.startedAt, now).compareTo(RECONNECT_BACKOFF) >= 0) {
                disconnect(user);
            }
            return empty(instrument, "RECONNECTING");
        }
        Tick tick = feed.latest;
        if (feed.state != State.OPEN || tick == null)
            return empty(instrument, "CONNECTING");
        if (tick.exchangeAt.isAfter(now.plus(FUTURE_SKEW))
                || Duration.between(tick.exchangeAt, now).compareTo(MAX_TICK_AGE) > 0
                || Duration.between(feed.lastHeartbeat, now).compareTo(MAX_CONNECTION_IDLE) > 0) {
            return empty(instrument, "STALE");
        }
        return new LiveQuote(instrument.id(), instrument.exchange(),
                instrument.tradingSymbol(), tick.price, tick.exchangeAt,
                tick.receivedAt, "LIVE", "ANGEL_ONE_SMARTAPI_WS_V2");
    }

    private static LiveQuote empty(Instrument instrument, String status) {
        return new LiveQuote(instrument.id(), instrument.exchange(),
                instrument.tradingSymbol(), null, null, null, status, null);
    }

    private enum State { CONNECTING, OPEN, CLOSED }

    record Tick(BigDecimal price, Instant exchangeAt, Instant receivedAt, long sequence) {}

    private static final class Feed {
        final Instrument instrument;
        final CustomerAngelOneSessionService.StreamCredentials credentials;
        final Instant startedAt;
        volatile State state = State.CONNECTING;
        volatile Instant lastHeartbeat;
        volatile Tick latest;
        volatile WebSocket socket;
        final ByteArrayOutputStream parts = new ByteArrayOutputStream(51);
        Feed(Instrument instrument, CustomerAngelOneSessionService.StreamCredentials credentials, Instant at) {
            this.instrument = instrument;
            this.credentials = credentials;
            this.startedAt = at;
            this.lastHeartbeat = at;
        }
    }

    private void start(UUID user, Feed feed) {
        try {
            // Broker authentication is transmitted only in TLS WebSocket headers
            // from the backend, never as query params or client payload.
            client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(8))
                    .header("Authorization", feed.credentials.jwt())
                    .header("x-api-key", feed.credentials.apiKey())
                    .header("x-client-code", feed.credentials.clientCode())
                    .header("x-feed-token", feed.credentials.feedToken())
                    .buildAsync(BROKER, new Listener(user, feed))
                    .whenComplete((ws, error) -> {
                        if (error != null || feeds.get(user) != feed) {
                            feed.state = State.CLOSED;
                            feed.latest = null;
                            if (ws != null) ws.abort();
                        } else {
                            feed.socket = ws;
                        }
                    });
        } catch (RuntimeException e) {
            feed.state = State.CLOSED;
            feed.latest = null;
        }
    }

    private final class Listener implements WebSocket.Listener {
        private final UUID user;
        private final Feed feed;
        Listener(UUID user, Feed feed) {
            this.user = user;
            this.feed = feed;
        }
        @Override public void onOpen(WebSocket ws) {
            if (feeds.get(user) != feed) {
                ws.abort();
                return;
            }
            feed.socket = ws;
            feed.state = State.OPEN;
            feed.lastHeartbeat = clock.instant();
            int venue = "NSE_CM".equals(feed.instrument.exchangeSegment()) ? 1 : 3;
            // Canonical catalogue token is server-owned, one LTP subscription.
            String subscribe = "{\"correlationID\":\"minttick1\",\"action\":1,"
                + "\"params\":{\"mode\":1,\"tokenList\":[{\"exchangeType\":" + venue
                + ",\"tokens\":[\"" + feed.instrument.symbolToken() + "\"]}]}}";
            ws.sendText(subscribe, true);
            ws.request(1);
        }
        @Override public CompletionStage<?> onBinary(WebSocket ws, ByteBuffer packet, boolean last) {
            if (feeds.get(user) != feed) {
                ws.abort();
                return CompletableFuture.completedFuture(null);
            }
            synchronized (feed.parts) {
                if (feed.parts.size() + packet.remaining() > 51) {
                    feed.parts.reset();
                    // Invalid provider data must never refresh a price.
                    feed.latest = null;
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
            // Text control messages are not prices or evidence of fresh ticks.
            if ("pong".contentEquals(message)) feed.lastHeartbeat = clock.instant();
            ws.request(1);
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletionStage<?> onPong(WebSocket ws, ByteBuffer message) {
            feed.lastHeartbeat = clock.instant();
            ws.request(1);
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            feed.state = State.CLOSED;
            feed.latest = null;
            return CompletableFuture.completedFuture(null);
        }
        @Override public void onError(WebSocket ws, Throwable error) {
            feed.state = State.CLOSED;
            feed.latest = null;
            // No provider payload, JWT or exception detail is logged.
        }
    }

    private void accept(Feed feed, byte[] data) {
        Tick verified = parseTick(feed.instrument, data, clock.instant(), feed.latest);
        if (verified != null) feed.latest = verified;
    }

    /** Pure on-host binary validator, separately unit tested with no socket. */
    static Tick parseTick(Instrument instrument, byte[] data, Instant received, Tick old) {
        if (data == null || data.length != 51 || data[0] != 1) return null;
        int venue = "NSE_CM".equals(instrument.exchangeSegment()) ? 1
                : "BSE_CM".equals(instrument.exchangeSegment()) ? 3 : -1;
        if (Byte.toUnsignedInt(data[1]) != venue) return null;
        int end = 2;
        while (end < 27 && data[end] != 0) end++;
        String token = new String(data, 2, end - 2, StandardCharsets.US_ASCII);
        if (!token.matches("[0-9]{1,25}") || !instrument.symbolToken().equals(token))
            return null;
        // Require a zero-filled token suffix; do not accept forged prefix matches.
        for (int at = end; at < 27; at++) if (data[at] != 0) return null;
        ByteBuffer fields = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        long sequence = fields.getLong(27);
        long epochMillis = fields.getLong(35);
        long cents = fields.getLong(43);
        if (sequence < 0 || epochMillis <= 0 || cents <= 0) return null;
        Instant exchangeTime;
        try { exchangeTime = Instant.ofEpochMilli(epochMillis); }
        catch (RuntimeException ex) { return null; }
        if (exchangeTime.isAfter(received.plus(FUTURE_SKEW))
                || Duration.between(exchangeTime, received).compareTo(MAX_TICK_AGE) > 0)
            return null;
        if (old != null && (sequence <= old.sequence
                || exchangeTime.isBefore(old.exchangeAt))) return null;
        return new Tick(BigDecimal.valueOf(cents, 2), exchangeTime, received, sequence);
    }

    private void heartbeat() {
        try {
            Instant now = clock.instant();
            for (Map.Entry<UUID, Feed> entry : feeds.entrySet()) {
                Feed feed = entry.getValue();
                if (feed.state != State.OPEN || feed.socket == null) continue;
                var latestCredentials = sessions.credentialsForStream(entry.getKey());
                if (latestCredentials == null
                        || !latestCredentials.jwt().equals(feed.credentials.jwt())
                        || !feed.credentials.expiresAt().isAfter(now)) {
                    disconnect(entry.getKey());
                    continue;
                }
                // Official Python SDK uses websocket ping *control frames*
                // with payload "ping", not an application text message.
                // Heartbeats never refresh exchange tick age.
                feed.socket.sendPing(ByteBuffer.wrap("ping".getBytes(StandardCharsets.US_ASCII)))
                        .exceptionally(error -> {
                    feed.state = State.CLOSED;
                    feed.latest = null;
                    return null;
                });
            }
        } catch (RuntimeException ignored) {
            // Individual socket failure cannot break the scheduler.
        }
    }

    public void disconnect(UUID user) {
        Feed old = feeds.remove(user);
        if (old != null) {
            old.latest = null;
            old.state = State.CLOSED;
            if (old.socket != null) old.socket.abort();
        }
    }

    @PreDestroy public void close() {
        heartbeats.shutdownNow();
        feeds.keySet().forEach(this::disconnect);
        client.close();
    }
}
