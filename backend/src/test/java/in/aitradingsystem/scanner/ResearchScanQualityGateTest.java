package in.aitradingsystem.scanner;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.aitradingsystem.instrument.Instrument;
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

class ResearchScanQualityGateTest {
    @Test
    void degradedInstrumentIsCountedButProducesNoCandidate() {
        ResearchScanRepository scans = mock(ResearchScanRepository.class);
        MarketDataRepository marketData = mock(MarketDataRepository.class);
        MarketDataQualityService quality = mock(MarketDataQualityService.class);
        HistoricalCandleInterval interval = HistoricalCandleInterval.FIVE_MINUTE;
        Instant now = Instant.parse("2026-10-28T08:45:00Z");
        Instrument instrument = new Instrument(17L, "NSE_EQ", "NSE", "token-17", "TEST-EQ",
                "Test equity", "EQ", null, null, 1, new BigDecimal("0.05"), UUID.randomUUID());
        List<MarketCandle> bars = bars(instrument, now.minusSeconds(60), interval.name());
        MarketDataQualityResult degraded = mock(MarketDataQualityResult.class);
        when(scans.activeCashEquityUniverse(1)).thenReturn(List.of(instrument));
        when(marketData.recentCandles(instrument.id(), interval.name(), 64)).thenReturn(bars);
        when(quality.evaluateBars(instrument.id(), interval, bars)).thenReturn(degraded);
        when(quality.eligibleForResearch(degraded)).thenReturn(false);
        ResearchScanSummary expected = new ResearchScanSummary(UUID.randomUUID(), interval.name(), "SUCCEEDED",
                1, 1, 0, "TEST", now, now, null, null);
        when(scans.requireRun(any(UUID.class))).thenReturn(expected);

        ResearchScanService service = new ResearchScanService(scans, marketData, quality,
                Clock.fixed(now, ZoneOffset.UTC), 200, 60);
        service.run(interval, 1);

        verify(scans).finishRun(any(UUID.class), eq(1), eq(0));
        verify(scans, never()).saveCandidate(any(UUID.class), any(ScanCandidate.class));
    }

    private static List<MarketCandle> bars(Instrument instrument, Instant latest, String interval) {
        ArrayList<MarketCandle> result = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            BigDecimal close = BigDecimal.valueOf(100 + i);
            Instant time = latest.minusSeconds(300L * (63 - i));
            result.add(new MarketCandle(i + 1L, instrument.id(), "NSE", instrument.tradingSymbol(),
                    instrument.symbolToken(), instrument.catalogueVersion(), interval, time,
                    close, close.add(BigDecimal.ONE), close.subtract(BigDecimal.ONE), close,
                    1_000L, "TEST_FIXTURE", time));
        }
        return List.copyOf(result);
    }
}
