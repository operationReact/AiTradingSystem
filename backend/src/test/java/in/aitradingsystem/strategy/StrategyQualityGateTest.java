package in.aitradingsystem.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import in.aitradingsystem.instrument.Instrument;
import in.aitradingsystem.instrument.InstrumentMasterService;
import in.aitradingsystem.marketdata.HistoricalCandleInterval;
import in.aitradingsystem.marketdata.MarketCandle;
import in.aitradingsystem.marketdata.MarketDataQualityResult;
import in.aitradingsystem.marketdata.MarketDataQualityService;
import in.aitradingsystem.marketdata.MarketDataRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StrategyQualityGateTest {
    @Test
    void degradedStoredCandlesAreNotEligibleForIndicatorEvaluation() {
        long instrumentId = 17L;
        HistoricalCandleInterval interval = HistoricalCandleInterval.FIVE_MINUTE;
        Instant now = Instant.parse("2026-10-27T08:00:00Z");
        InstrumentMasterService instruments = mock(InstrumentMasterService.class);
        MarketDataRepository marketData = mock(MarketDataRepository.class);
        MarketDataQualityService quality = mock(MarketDataQualityService.class);
        StrategySignalRepository signals = mock(StrategySignalRepository.class);
        Instrument instrument = new Instrument(instrumentId, "NSE_EQ", "NSE", "token-17",
                "TEST-EQ", "Test equity", "EQ", null, null, 1, new BigDecimal("0.05"), UUID.randomUUID());
        List<MarketCandle> bars = bars(instrument, now.minusSeconds(30), interval.name());
        when(instruments.requireActiveInstrument(instrumentId)).thenReturn(instrument);
        when(marketData.countCandles(instrumentId, interval.name())).thenReturn(bars.size());
        when(marketData.recentCandles(instrumentId, interval.name(), 21)).thenReturn(bars);
        MarketDataQualityResult degraded = mock(MarketDataQualityResult.class);
        when(quality.evaluateBars(instrumentId, interval, bars)).thenReturn(degraded);
        when(quality.eligibleForResearch(degraded)).thenReturn(false);

        StrategyService service = new StrategyService(instruments, marketData, quality, signals,
                Clock.fixed(now, ZoneOffset.UTC), 5, 20, new BigDecimal("2"), new BigDecimal("0.5"), 1440);
        StrategyEvaluation result = service.evaluate(instrumentId, interval);

        assertThat(result.signal()).isNull();
        assertThat(result.reasonCodes()).containsExactly("DATA_QUALITY_NOT_ELIGIBLE");
        verifyNoInteractions(signals);
    }

    private static List<MarketCandle> bars(Instrument instrument, Instant latest, String interval) {
        ArrayList<MarketCandle> result = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            BigDecimal close = BigDecimal.valueOf(100 + i);
            Instant time = latest.minusSeconds(300L * (20 - i));
            result.add(new MarketCandle(i + 1L, instrument.id(), "NSE", instrument.tradingSymbol(),
                    instrument.symbolToken(), instrument.catalogueVersion(), interval, time,
                    close, close.add(BigDecimal.ONE), close.subtract(BigDecimal.ONE), close,
                    i == 20 ? 3_000L : 1_000L, "TEST_FIXTURE", time));
        }
        return List.copyOf(result);
    }
}
