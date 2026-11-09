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
