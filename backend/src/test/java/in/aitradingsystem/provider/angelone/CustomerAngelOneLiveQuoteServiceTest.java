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
        var tick = CustomerAngelOneLiveQuoteService.parseTick(NSE, nse, NOW, null);
        assertThat(tick).isNotNull();
        assertThat(tick.price()).isEqualByComparingTo("1254.55");
        assertThat(tick.exchangeAt()).isEqualTo(NOW);
        assertThat(tick.sequence()).isEqualTo(8);
        assertThat(CustomerAngelOneLiveQuoteService.parseTick(BSE,
            frame(3, "500325", 1, NOW.toEpochMilli(), 10555), NOW, null).price())
            .isEqualByComparingTo("105.55");
    }

    @Test
    void rejectsCrossInstrumentUnsubscribedStaleFutureNonpositiveReplayedTicks() {
        var baseline = CustomerAngelOneLiveQuoteService.parseTick(NSE,
            frame(1, "2885", 8, NOW.toEpochMilli(), 12300), NOW, null);
        assertThat(CustomerAngelOneLiveQuoteService.parseTick(NSE,
            frame(1, "other", 9, NOW.toEpochMilli(), 12300), NOW, baseline)).isNull();
        assertThat(CustomerAngelOneLiveQuoteService.parseTick(NSE,
            frame(3, "2885", 9, NOW.toEpochMilli(), 12300), NOW, baseline)).isNull();
        assertThat(CustomerAngelOneLiveQuoteService.parseTick(NSE,
            frame(1, "2885", 9, NOW.minusSeconds(11).toEpochMilli(), 12300), NOW, baseline)).isNull();
        assertThat(CustomerAngelOneLiveQuoteService.parseTick(NSE,
            frame(1, "2885", 9, NOW.plusSeconds(3).toEpochMilli(), 12300), NOW, baseline)).isNull();
        assertThat(CustomerAngelOneLiveQuoteService.parseTick(NSE,
            frame(1, "2885", 9, NOW.toEpochMilli(), 0), NOW, baseline)).isNull();
        assertThat(CustomerAngelOneLiveQuoteService.parseTick(NSE,
            frame(1, "2885", 8, NOW.toEpochMilli(), 12300), NOW, baseline)).isNull();
        assertThat(CustomerAngelOneLiveQuoteService.parseTick(NSE,
            frame(1, "2885", 9, NOW.minusMillis(100).toEpochMilli(), 12300), NOW, baseline)).isNull();
        assertThat(CustomerAngelOneLiveQuoteService.parseTick(NSE, new byte[50], NOW, null)).isNull();
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
