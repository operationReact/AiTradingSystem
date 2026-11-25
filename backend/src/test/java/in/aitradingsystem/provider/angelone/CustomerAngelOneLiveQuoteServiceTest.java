package in.aitradingsystem.provider.angelone;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import in.aitradingsystem.instrument.Instrument;
import in.aitradingsystem.marketdata.MarketSessionCalendar;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CustomerAngelOneLiveQuoteServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-08T09:00:00Z"); // Muhurat 14:30 IST
    private static final Instrument NSE = new Instrument(42L, "NSE_CM", "NSE",
        "2885", "TEST-EQ", "Test", "EQ", null, null, 1, new BigDecimal("0.05"),
        UUID.randomUUID());
    private static final Instrument BSE = new Instrument(43L, "BSE_CM", "BSE",
        "500325", "TEST-EQ", "Test", "EQ", null, null, 1, new BigDecimal("0.05"),
        UUID.randomUUID());

    private static final Instant SESSION_OPEN = Instant.parse("2026-11-08T08:50:00Z"); // Muhurat 14:20 IST

    private static byte[] frame(int exchange, String token, long sequence, long ms, long cents) {
        byte[] payload = new byte[51];
        ByteBuffer bytes = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        bytes.put((byte) 1).put((byte) exchange);
        bytes.put(token.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        bytes.position(27);
        bytes.putLong(sequence).putLong(ms).putLong(cents);
        return payload;
    }

    @Test
    void validNseAndBseProviderPacketsKeepCanonicalIdentityAndExchangeTimestamp() {
        byte[] nse = frame(1, "2885", 8, NOW.toEpochMilli(), 125455);
        var tick = CustomerAngelOneLiveQuoteService.parseTick(NSE, nse, NOW, null, SESSION_OPEN);
        assertThat(tick).isNotNull();
        assertThat(tick.price()).isEqualByComparingTo("1254.55");
        assertThat(tick.exchangeAt()).isEqualTo(NOW);
        assertThat(tick.sequence()).isEqualTo(8);
        assertThat(CustomerAngelOneLiveQuoteService.parseTick(BSE,
            frame(3, "500325", 1, NOW.toEpochMilli(), 10555), NOW, null, SESSION_OPEN).price())
            .isEqualByComparingTo("105.55");
    }

    @Test
    void quietStockLastTradeFromCurrentSessionStaysValidButPreviousSessionIsRejected() {
        var baseline = CustomerAngelOneLiveQuoteService.parseTick(NSE,
            frame(1, "2885", 8, NOW.minusSeconds(480).toEpochMilli(), 12300), NOW, null, SESSION_OPEN);
        // Five minutes since the last trade is still this session: an LTP frame arrives only on trades.
        var quiet = frame(1, "2885", 9, NOW.minusSeconds(300).toEpochMilli(), 12400);
        assertThat(CustomerAngelOneLiveQuoteService.parseTick(NSE, quiet,
            NOW, baseline, SESSION_OPEN)).isNotNull();
        var previousSession = frame(1, "2885", 9, SESSION_OPEN.minusSeconds(60).toEpochMilli(), 12400);
        assertThat(CustomerAngelOneLiveQuoteService.parseTick(NSE, previousSession,
            NOW, null, SESSION_OPEN)).isNull();
    }

    @Test
    void rejectsCrossInstrumentUnsubscribedFutureNonpositiveReplayedTicks() {
        var baseline = CustomerAngelOneLiveQuoteService.parseTick(NSE,
            frame(1, "2885", 8, NOW.toEpochMilli(), 12300), NOW, null, SESSION_OPEN);
        assertThat(CustomerAngelOneLiveQuoteService.parseTick(NSE,
            frame(1, "other", 9, NOW.toEpochMilli(), 12300), NOW, baseline, SESSION_OPEN)).isNull();
        assertThat(CustomerAngelOneLiveQuoteService.parseTick(NSE,
            frame(3, "2885", 9, NOW.toEpochMilli(), 12300), NOW, baseline, SESSION_OPEN)).isNull();
        assertThat(CustomerAngelOneLiveQuoteService.parseTick(NSE,
            frame(1, "2885", 9, NOW.plusSeconds(3).toEpochMilli(), 12300), NOW, baseline, SESSION_OPEN)).isNull();
        assertThat(CustomerAngelOneLiveQuoteService.parseTick(NSE,
            frame(1, "2885", 9, NOW.toEpochMilli(), 0), NOW, baseline, SESSION_OPEN)).isNull();
        assertThat(CustomerAngelOneLiveQuoteService.parseTick(NSE,
            frame(1, "2885", 8, NOW.toEpochMilli(), 12300), NOW, baseline, SESSION_OPEN)).isNull();
        assertThat(CustomerAngelOneLiveQuoteService.parseTick(NSE,
            frame(1, "2885", 9, NOW.minusMillis(100).toEpochMilli(), 12300), NOW, baseline, SESSION_OPEN)).isNull();
        assertThat(CustomerAngelOneLiveQuoteService.parseTick(NSE, new byte[50], NOW, null, SESSION_OPEN)).isNull();
    }

    @Test
    void serverSideSocketSubscribesPersistsVerifiedFrameAndKeepsAliveWithBothHeartbeats()
            throws Exception {
        var sessions = mock(CustomerAngelOneSessionService.class);
        var calendar = mock(MarketSessionCalendar.class);
        var client = mock(HttpClient.class);
        var builder = mock(java.net.http.WebSocket.Builder.class);
        var socket = mock(java.net.http.WebSocket.class);
        var user = UUID.fromString("ff000000-0000-4000-8000-000000000001");
        when(calendar.isInsideCashSession(NOW)).thenReturn(true);
        var muhurat = java.time.LocalDate.of(2026, 11, 8);
        when(calendar.session(muhurat)).thenReturn(new in.aitradingsystem.marketdata.MarketSession(
            muhurat, java.time.ZonedDateTime.of(muhurat, java.time.LocalTime.of(14, 20),
            java.time.ZoneId.of("Asia/Kolkata")), java.time.ZonedDateTime.of(
            muhurat, java.time.LocalTime.of(15, 15),
            java.time.ZoneId.of("Asia/Kolkata")), true));
        when(sessions.credentialsForStream(user)).thenReturn(
            new CustomerAngelOneSessionService.StreamCredentials(
                "test-key", "TESTCODE", "test-jwt", "test-feed",
                NOW.plusSeconds(3600)));
        when(client.newWebSocketBuilder()).thenReturn(builder);
        when(builder.connectTimeout(any())).thenReturn(builder);
        when(builder.header(anyString(), anyString())).thenReturn(builder);
        var listener = org.mockito.ArgumentCaptor.forClass(java.net.http.WebSocket.Listener.class);
        when(builder.buildAsync(any(java.net.URI.class), listener.capture()))
            .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(socket));
        when(socket.sendText(anyString(), eq(true)))
            .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(socket));
        when(socket.sendPing(any(ByteBuffer.class)))
            .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(socket));

        var feed = new CustomerAngelOneLiveQuoteService(sessions, calendar,
            client, Clock.fixed(NOW, ZoneOffset.UTC));
        try {
            var initial = feed.snapshot(user, List.of(NSE));
            assertThat(initial.getFirst().status()).isEqualTo("CONNECTING");
            listener.getValue().onOpen(socket);
            verify(socket).sendText(contains("\"action\":1"), eq(true));

            listener.getValue().onBinary(socket,
                ByteBuffer.wrap(frame(1, NSE.symbolToken(), 3, NOW.toEpochMilli(), 125455)), true);
            var actual = feed.snapshot(user, List.of(NSE)).getFirst();
            assertThat(actual.status()).isEqualTo("LIVE");
            assertThat(actual.source()).isEqualTo("ANGEL_ONE_SMARTAPI_WS_V2");
            assertThat(actual.ltp()).isEqualByComparingTo("1254.55");
            assertThat(actual.exchangeAt()).isEqualTo(NOW);

            feed.heartbeat();
            verify(socket).sendPing(any(ByteBuffer.class));
            verify(socket).sendText("ping", true);
            listener.getValue().onText(socket, "po", false);
            listener.getValue().onText(socket, "ng", true);
            assertThat(feed.snapshot(user, List.of(NSE)).getFirst().status()).isEqualTo("LIVE");
        } finally {
            feed.close();
        }
    }

    @Test
    void applicationFeedShardsEntire2756SymbolUniverseAcrossThreeSockets()
            throws Exception {
        var sessions = mock(CustomerAngelOneSessionService.class);
        var calendar = mock(MarketSessionCalendar.class);
        var client = mock(HttpClient.class);
        var builder = mock(java.net.http.WebSocket.Builder.class);
        var socket = mock(java.net.http.WebSocket.class);
        var user = UUID.fromString("ff000000-0000-4000-8000-000000000001");
        when(calendar.isInsideCashSession(NOW)).thenReturn(true);
        when(sessions.credentialsForStream(user)).thenReturn(
            new CustomerAngelOneSessionService.StreamCredentials(
                "test-key", "TESTCODE", "test-jwt", "test-feed", NOW.plusSeconds(3600)));
        when(client.newWebSocketBuilder()).thenReturn(builder);
        when(builder.connectTimeout(any())).thenReturn(builder);
        when(builder.header(anyString(), anyString())).thenReturn(builder);
        when(builder.buildAsync(any(java.net.URI.class), any(java.net.http.WebSocket.Listener.class)))
            .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(socket));
        when(socket.sendText(anyString(), eq(true)))
            .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(socket));
        var catalogue = new java.util.ArrayList<Instrument>();
        var catalogueVersion = UUID.randomUUID();
        for (int i = 0; i < 2756; i++) {
            catalogue.add(new Instrument(i + 1L, "NSE_CM", "NSE",
                Integer.toString(10000 + i), "STOCK" + i + "-EQ", "Stock " + i,
                "EQ", null, null, 1, new BigDecimal("0.05"), catalogueVersion));
        }

        var service = new CustomerAngelOneLiveQuoteService(sessions, calendar,
            client, Clock.fixed(NOW, ZoneOffset.UTC), 1000, null);
        try {
            var rows = service.snapshotApplication(user, catalogue);
            assertThat(rows).hasSize(2756);
            assertThat(rows).allMatch(q -> q.ltp() == null && "CONNECTING".equals(q.status()));
            assertThat(rows.getFirst().instrumentId()).isEqualTo(1L);
            assertThat(rows.getLast().instrumentId()).isEqualTo(2756L);
            var listeners = org.mockito.ArgumentCaptor
                .forClass(java.net.http.WebSocket.Listener.class);
            verify(builder, times(3))
                .buildAsync(any(java.net.URI.class), listeners.capture());
            for (var listener : listeners.getAllValues()) listener.onOpen(socket);
            var sent = org.mockito.ArgumentCaptor.forClass(String.class);
            verify(socket, times(3)).sendText(sent.capture(), eq(true));
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var sizes = new java.util.ArrayList<Integer>();
            var tokens = new java.util.HashSet<String>();
            for (String payload : sent.getAllValues()) {
                var groups = mapper.readTree(payload).at("/params/tokenList");
                int count = 0;
                for (var group : groups) {
                    for (var token : group.get("tokens")) {
                        assertThat(tokens.add(token.asText())).isTrue();
                        count++;
                    }
                }
                sizes.add(count);
            }
            assertThat(sizes).containsExactlyInAnyOrder(1000, 1000, 756);
            assertThat(tokens).hasSize(2756);
            verify(client, times(3)).newWebSocketBuilder();
        } finally {
            service.close();
        }
    }

    @Test
    void applicationSingleStockReadsSubscribedThirdShardInsteadOfOverfullFirstShard()
            throws Exception {
        var sessions = mock(CustomerAngelOneSessionService.class);
        var calendar = mock(MarketSessionCalendar.class);
        var client = mock(HttpClient.class);
        var builder = mock(java.net.http.WebSocket.Builder.class);
        var socket = mock(java.net.http.WebSocket.class);
        var user = UUID.fromString("ff000000-0000-4000-8000-000000000001");
        when(calendar.isInsideCashSession(NOW)).thenReturn(true);
        var muhurat = java.time.LocalDate.of(2026, 11, 8);
        when(calendar.session(muhurat)).thenReturn(new in.aitradingsystem.marketdata.MarketSession(
            muhurat, java.time.ZonedDateTime.of(muhurat, java.time.LocalTime.of(14, 20),
                java.time.ZoneId.of("Asia/Kolkata")),
            java.time.ZonedDateTime.of(muhurat, java.time.LocalTime.of(15, 15),
                java.time.ZoneId.of("Asia/Kolkata")), true));
        when(sessions.credentialsForStream(user)).thenReturn(
            new CustomerAngelOneSessionService.StreamCredentials(
                "test-key", "TESTCODE", "test-jwt", "test-feed", NOW.plusSeconds(3600)));
        when(client.newWebSocketBuilder()).thenReturn(builder);
        when(builder.connectTimeout(any())).thenReturn(builder);
        when(builder.header(anyString(), anyString())).thenReturn(builder);
        var listeners = org.mockito.ArgumentCaptor.forClass(java.net.http.WebSocket.Listener.class);
        when(builder.buildAsync(any(java.net.URI.class), listeners.capture()))
            .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(socket));
        when(socket.sendText(anyString(), eq(true)))
            .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(socket));
        var equities = new java.util.ArrayList<Instrument>();
        for (int i = 0; i < 5; i++) {
            equities.add(new Instrument(i + 1L, "NSE_CM", "NSE",
                Integer.toString(10000 + i), "STOCK" + i + "-EQ", "Stock " + i,
                "EQ", null, null, 1, new BigDecimal("0.05"), UUID.randomUUID()));
        }
        var feed = new CustomerAngelOneLiveQuoteService(sessions, calendar,
            client, Clock.fixed(NOW, ZoneOffset.UTC), 2, null);
        try {
            var starting = feed.snapshotApplication(user, equities);
            assertThat(starting).hasSize(5);
            assertThat(starting).allMatch(q -> "CONNECTING".equals(q.status()));
            assertThat(listeners.getAllValues()).hasSize(3);
            for (var listener : listeners.getAllValues()) listener.onOpen(socket);
            var thirdShardListener = listeners.getAllValues().get(2);
            Instrument last = equities.get(4);
            thirdShardListener.onBinary(socket,
                ByteBuffer.wrap(frame(1, last.symbolToken(), 1, NOW.toEpochMilli(), 123456)), true);

            var single = feed.snapshotApplication(user, List.of(last)).getFirst();
            assertThat(single.instrumentId()).isEqualTo(last.id());
            assertThat(single.status()).isEqualTo("LIVE");
            assertThat(single.ltp()).isEqualByComparingTo("1234.56");
            assertThat(single.exchangeAt()).isEqualTo(NOW);

            // Two active stocks in one shard must share one cash-session
            // calendar read; checking every tick separately scales poorly.
            var firstShard = listeners.getAllValues().get(0);
            for (int i = 0; i < 2; i++) {
                firstShard.onBinary(socket, ByteBuffer.wrap(frame(1,
                    equities.get(i).symbolToken(), 1, NOW.toEpochMilli(), 123456)), true);
            }
            clearInvocations(calendar);
            var firstTwo = feed.snapshotApplication(user, equities.subList(0, 2));
            assertThat(firstTwo).allMatch(q -> "LIVE".equals(q.status()));
            verify(calendar, times(1)).session(muhurat);

            verify(builder, times(3))
                .buildAsync(any(java.net.URI.class), any(java.net.http.WebSocket.Listener.class));
        } finally {
            feed.close();
        }
    }

    @Test
    void closedMarketCannotOpenSocketOrReturnInventedLiveQuote() {
        var sessions = mock(CustomerAngelOneSessionService.class);
        var calendar = mock(MarketSessionCalendar.class);
        var client = mock(HttpClient.class);
        var feed = new CustomerAngelOneLiveQuoteService(sessions, calendar,
            client, Clock.fixed(NOW, ZoneOffset.UTC));
        try {
            var quote = feed.quote(UUID.randomUUID(), NSE);
            assertThat(quote.status()).isEqualTo("MARKET_CLOSED");
            assertThat(quote.ltp()).isNull();
            assertThat(quote.source()).isNull();
            verifyNoInteractions(client, sessions);
        } finally {
            feed.close();
        }
    }

    @Test
    void subscriptionGroupsTokensByExchangeTypeForOneSharedSocket() {
        var nse = new CustomerAngelOneLiveQuoteService.Watch(NSE, 1);
        var bse = new CustomerAngelOneLiveQuoteService.Watch(BSE, 3);
        var json = CustomerAngelOneLiveQuoteService.subscription(1, List.of(bse, nse));
        assertThat(json).contains("\"action\":1", "\"mode\":1",
            "{\"exchangeType\":1,\"tokens\":[\"2885\"]}",
            "{\"exchangeType\":3,\"tokens\":[\"500325\"]}");
    }

    @Test
    void oneFrameIsVerifiedOnlyAgainstItsOwnInstrument() {
        var nseFrame = CustomerAngelOneLiveQuoteService.parseFrame(
            frame(1, "2885", 8, NOW.toEpochMilli(), 12300));
        var bseFrame = CustomerAngelOneLiveQuoteService.parseFrame(
            frame(3, "500325", 1, NOW.toEpochMilli(), 10555));
        assertThat(CustomerAngelOneLiveQuoteService.verify(NSE, nseFrame, NOW, null, SESSION_OPEN)).isNotNull();
        assertThat(CustomerAngelOneLiveQuoteService.verify(BSE, bseFrame, NOW, null, SESSION_OPEN)).isNotNull();
        assertThat(CustomerAngelOneLiveQuoteService.verify(NSE, bseFrame, NOW, null, SESSION_OPEN)).isNull();
        assertThat(CustomerAngelOneLiveQuoteService.verify(BSE, nseFrame, NOW, null, SESSION_OPEN)).isNull();
    }

    @Test
    void batchSnapshotOutsideSessionReturnsOneClosedRowPerInstrumentInOrder() {
        var sessions = mock(CustomerAngelOneSessionService.class);
        var calendar = mock(MarketSessionCalendar.class);
        var client = mock(HttpClient.class);
        var feed = new CustomerAngelOneLiveQuoteService(sessions, calendar,
            client, Clock.fixed(NOW, ZoneOffset.UTC));
        try {
            var rows = feed.snapshot(UUID.randomUUID(), List.of(NSE, BSE));
            assertThat(rows).extracting(q -> q.instrumentId()).containsExactly(42L, 43L);
            assertThat(rows).extracting(q -> q.status())
                .containsOnly("MARKET_CLOSED");
            assertThat(rows).allMatch(q -> q.ltp() == null);
            verifyNoInteractions(client, sessions);
        } finally {
            feed.close();
        }
    }

    @Test
    void missingServerSideFeedTokenNeverStartsSocketOrReturnsPrice() {
        var sessions = mock(CustomerAngelOneSessionService.class);
        var calendar = mock(MarketSessionCalendar.class);
        when(calendar.isInsideCashSession(NOW)).thenReturn(true);
        var client = mock(HttpClient.class);
        var feed = new CustomerAngelOneLiveQuoteService(sessions, calendar,
            client, Clock.fixed(NOW, ZoneOffset.UTC));
        try {
            var result = feed.quote(UUID.randomUUID(), NSE);
            assertThat(result.status()).isEqualTo("BROKER_CONNECTION_REQUIRED");
            assertThat(result.ltp()).isNull();
            verify(client, never()).newWebSocketBuilder();
        } finally {
            feed.close();
        }
    }
}
