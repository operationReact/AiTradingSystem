package in.aitradingsystem.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.aitradingsystem.instrument.Instrument;
import in.aitradingsystem.instrument.InstrumentMasterService;
import in.aitradingsystem.marketdata.HistoricalCandleInterval;
import in.aitradingsystem.marketdata.MarketCandle;
import in.aitradingsystem.marketdata.MarketDataRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StrategyServiceTest {
    private static final long INSTRUMENT_ID = 17L;
    private static final String INTERVAL = HistoricalCandleInterval.FIVE_MINUTE.name();
    private final InstrumentMasterService instruments = mock(InstrumentMasterService.class);
    private final MarketDataRepository marketData = mock(MarketDataRepository.class);
    private final StrategySignalRepository signals = mock(StrategySignalRepository.class);
    private final Instant now = Instant.parse("2026-10-27T08:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    private final Instrument instrument = new Instrument(INSTRUMENT_ID, "NSE_EQ", "NSE", "token-17",
            "TEST-EQ", "Test equity", "EQ", null, null, 1, new BigDecimal("0.05"), UUID.randomUUID());

    @Test
    void emitsAndPersistsExplainableBuyForRisingTrendWithVolumeConfirmation() {
        List<MarketCandle> bars = risingBars(now.minusSeconds(30));
        when(instruments.requireActiveInstrument(INSTRUMENT_ID)).thenReturn(instrument);
        when(marketData.countCandles(INSTRUMENT_ID, INTERVAL)).thenReturn(bars.size());
        when(marketData.recentCandles(INSTRUMENT_ID, INTERVAL, 21)).thenReturn(bars);

        StrategyService service = service();
        StrategyEvaluation result = service.evaluate(INSTRUMENT_ID, HistoricalCandleInterval.FIVE_MINUTE);

        assertThat(result.availableCandles()).isEqualTo(21);
        assertThat(result.signal()).isNotNull();
        assertThat(result.signal().action()).isEqualTo("BUY");
        assertThat(result.signal().referencePrice()).isEqualByComparingTo("120");
        assertThat(result.signal().stopLoss()).isLessThan(result.signal().referencePrice());
        assertThat(result.signal().target()).isGreaterThan(result.signal().referencePrice());
        assertThat(result.signal().reasonCodes()).contains("FAST_SMA_ABOVE_SLOW_SMA", "CLOSE_ABOVE_SLOW_SMA", "VOLUME_CONFIRMED");
        assertThat(result.signal().status()).isEqualTo("CANDIDATE_NOT_EXECUTABLE");
        verify(signals).save(result.signal(), "token-17");
    }

    @Test
    void returnsInsufficientHistoryWithoutCreatingASignal() {
        when(instruments.requireActiveInstrument(INSTRUMENT_ID)).thenReturn(instrument);
        when(marketData.countCandles(INSTRUMENT_ID, INTERVAL)).thenReturn(5);
        when(marketData.recentCandles(INSTRUMENT_ID, INTERVAL, 21)).thenReturn(risingBars(now).subList(0, 5));

        StrategyEvaluation result = service().evaluate(INSTRUMENT_ID, HistoricalCandleInterval.FIVE_MINUTE);

        assertThat(result.availableCandles()).isEqualTo(5);
        assertThat(result.signal()).isNull();
        assertThat(result.reasonCodes()).containsExactly("INSUFFICIENT_HISTORY");
        org.mockito.Mockito.verifyNoInteractions(signals);
    }

    @Test
    void staleCandlesNeverEmitBuy() {
        List<MarketCandle> bars = risingBars(now.minus(DurationSeconds.TWO_DAYS));
        when(instruments.requireActiveInstrument(INSTRUMENT_ID)).thenReturn(instrument);
        when(marketData.countCandles(INSTRUMENT_ID, INTERVAL)).thenReturn(bars.size());
        when(marketData.recentCandles(INSTRUMENT_ID, INTERVAL, 21)).thenReturn(bars);

        StrategyEvaluation result = service().evaluate(INSTRUMENT_ID, HistoricalCandleInterval.FIVE_MINUTE);

        assertThat(result.signal()).isNotNull();
        assertThat(result.signal().action()).isEqualTo("WATCH");
        assertThat(result.signal().reasonCodes()).contains("STALE_OR_FUTURE_CANDLE");
    }

    private StrategyService service() {
        return new StrategyService(instruments, marketData, signals, clock, 5, 20,
                new BigDecimal("2"), new BigDecimal("0.5"), 1440);
    }

    private List<MarketCandle> risingBars(Instant latest) {
        UUID catalogue = instrument.catalogueVersion();
        java.util.ArrayList<MarketCandle> bars = new java.util.ArrayList<>();
        for (int i = 0; i < 21; i++) {
            BigDecimal close = BigDecimal.valueOf(100 + i);
            Instant time = latest.minusSeconds(60L * (20 - i));
            long volume = i == 20 ? 3_000L : 1_000L;
            bars.add(new MarketCandle(i + 1L, INSTRUMENT_ID, "NSE", "TEST-EQ", "token-17", catalogue,
                    INTERVAL, time, close, close.add(BigDecimal.ONE), close.subtract(BigDecimal.ONE),
                    close, volume, "TEST_FIXTURE", time));
        }
        return List.copyOf(bars);
    }

    private static final class DurationSeconds {
        static final long TWO_DAYS = 2L * 24 * 60 * 60;
    }
}
