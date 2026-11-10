package in.aitradingsystem.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.aitradingsystem.instrument.Instrument;
import in.aitradingsystem.instrument.InstrumentMasterService;
import in.aitradingsystem.provider.angelone.AngelOneException;
import in.aitradingsystem.provider.angelone.AppAngelOneMarketFeedService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OwnerHistorySourceProbeControllerTest {
    private static final Instant NOW = Instant.parse("2026-11-10T09:00:00Z");
    private static final LocalDate MONDAY = LocalDate.of(2026, 11, 9);

    private final InstrumentMasterService instruments = mock(InstrumentMasterService.class);
    private final MarketSessionCalendar calendar = mock(MarketSessionCalendar.class);
    private final AppAngelOneMarketFeedService feed = mock(AppAngelOneMarketFeedService.class);
    private final HistoricalCandleParser parser = new HistoricalCandleParser();

    private OwnerHistorySourceProbeController controller() {
        return new OwnerHistorySourceProbeController(instruments, calendar, feed, parser,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void available() {
        when(feed.available()).thenReturn(true);
        when(calendar.isTradingDay(MONDAY)).thenReturn(true);
        when(instruments.activeInstrumentBySymbol("NSE", "RELIANCE-EQ"))
                .thenReturn(Optional.of(new Instrument(42L, "NSE_CM", "NSE", "2885",
                        "RELIANCE-EQ", "RELIANCE", "EQ", null, null,
                        1, new BigDecimal("0.05"), UUID.randomUUID())));
    }

    @Test
    void sourceValidatesOneCompletedSessionWithoutStoringOrEnablingTrading() throws Exception {
        available();
        var raw = new ObjectMapper().readTree("""
                [["2026-11-09T09:15:00+05:30",100,102,99,101,12345]]
                """);
        when(feed.historicalCandles("NSE", "2885", "FIVE_MINUTE",
                "2026-11-09 09:15", "2026-11-09 15:30")).thenReturn(raw);
        var probe = controller();
        var result = probe.probe().getBody();

        assertThat(result).isNotNull();
        assertThat(result.status()).isEqualTo("VALID_CANDLES");
        assertThat(result.validatedRows()).isEqualTo(1);
        assertThat(result.stored()).isFalse();
        assertThat(result.redistributionRightsVerified()).isFalse();
        assertThat(result.tradingEnabled()).isFalse();
        assertThat(probe.probe().getBody().status()).isEqualTo("COOLDOWN");
        verify(feed, times(1)).historicalCandles("NSE", "2885", "FIVE_MINUTE",
                "2026-11-09 09:15", "2026-11-09 15:30");
    }

    @Test
    void unauthorizedUnavailableFeedDoesNotIssueProviderRequest() {
        var result = controller().probe().getBody();
        assertThat(result.status()).isEqualTo("OWNER_FEED_UNAVAILABLE");
        verifyNoInteractions(instruments);
        verify(feed, never()).historicalCandles(any(), any(), any(), any(), any());
    }

    @Test
    void providerRejectIsSanitizedAndDoesNotLeakResponse() {
        available();
        when(feed.historicalCandles(any(), any(), any(), any(), any()))
                .thenThrow(new AngelOneException(AngelOneException.Kind.REJECTED,
                        "RAW_PROVIDER_ERROR", "Do not expose this broker payload"));
        var result = controller().probe().getBody();
        assertThat(result.status()).isEqualTo("PROVIDER_REJECTED");
        assertThat(result.validatedRows()).isZero();
        assertThat(result.redistributionRightsVerified()).isFalse();
        assertThat(result.toString()).doesNotContain("broker payload", "RAW_PROVIDER_ERROR");
    }

    @Test
    void rejectsWrongSessionDayWithoutPersistingProviderCandles() throws Exception {
        available();
        var wrong = new ObjectMapper().readTree("""
                [["2026-11-06T09:15:00+05:30",100,102,99,101,12345]]
                """);
        when(feed.historicalCandles(any(), any(), any(), any(), any())).thenReturn(wrong);
        var result = controller().probe().getBody();
        assertThat(result.status()).isEqualTo("INVALID_PROVIDER_RESPONSE");
        assertThat(result.validatedRows()).isZero();
        assertThat(result.stored()).isFalse();
    }
}
