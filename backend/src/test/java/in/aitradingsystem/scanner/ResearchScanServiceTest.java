package in.aitradingsystem.scanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.aitradingsystem.instrument.Instrument;
import in.aitradingsystem.marketdata.HistoricalCandleInterval;
import in.aitradingsystem.marketdata.MarketDataRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ResearchScanServiceTest {
    private final ResearchScanRepository scans = mock(ResearchScanRepository.class);
    private final MarketDataRepository marketData = mock(MarketDataRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-28T08:45:00Z"), ZoneOffset.UTC);

    @Test
    void rejectsUniverseAboveConfiguredBoundBeforeCreatingRun() {
        ResearchScanService service = new ResearchScanService(scans, marketData, clock, 200, 60);
        assertThrows(IllegalArgumentException.class,
                () -> service.run(HistoricalCandleInterval.FIVE_MINUTE, 201));
    }

    @Test
    void rejectsDailyIntervalForIntradayScanner() {
        ResearchScanService service = new ResearchScanService(scans, marketData, clock, 200, 60);
        assertThrows(IllegalArgumentException.class,
                () -> service.run(HistoricalCandleInterval.ONE_DAY, 10));
    }

    @Test
    void completesAnEmptyBoundedUniverseWithProvenance() {
        ResearchScanService service = new ResearchScanService(scans, marketData, clock, 200, 60);
        when(scans.activeCashEquityUniverse(10)).thenReturn(List.of());
        ResearchScanSummary expected = new ResearchScanSummary(
                UUID.randomUUID(), "FIVE_MINUTE", "SUCCEEDED", 10, 0, 0,
                "VOLUME_BREAKOUT_V1,MA_RECLAIM_V1", clock.instant(), clock.instant(), null, null);
        when(scans.requireRun(any(UUID.class))).thenReturn(expected);

        ResearchScanSummary actual = service.run(HistoricalCandleInterval.FIVE_MINUTE, 10);

        assertEquals("SUCCEEDED", actual.status());
        verify(scans).activeCashEquityUniverse(10);
        verify(scans).finishRun(any(UUID.class), eq(0), eq(0));
    }

    @Test
    void isolatesInstrumentFailureAndMarksRunPartial() {
        ResearchScanService service = new ResearchScanService(scans, marketData, clock, 200, 60);
        Instrument instrument = new Instrument(42L, "NSE_CM", "NSE", "42", "FAIL-EQ", "Fail Fixture", "EQ",
                null, null, 1, new BigDecimal("0.05"), UUID.randomUUID());
        when(scans.activeCashEquityUniverse(1)).thenReturn(List.of(instrument));
        when(marketData.recentCandles(42L, "FIVE_MINUTE", 64)).thenThrow(new IllegalStateException("provider down"));
        ResearchScanSummary expected = new ResearchScanSummary(
                UUID.randomUUID(), "FIVE_MINUTE", "PARTIAL", 1, 0, 0,
                "VOLUME_BREAKOUT_V1,MA_RECLAIM_V1", clock.instant(), clock.instant(), "PARTIAL_SCAN", "1 instrument(s) failed during scan");
        when(scans.requireRun(any(UUID.class))).thenReturn(expected);

        ResearchScanSummary actual = service.run(HistoricalCandleInterval.FIVE_MINUTE, 1);

        assertEquals("PARTIAL", actual.status());
        verify(scans).saveDiagnostic(any(UUID.class), eq(42L), eq(null), eq("ERROR"), eq("INSTRUMENT_SCAN_FAILED"),
                eq("Instrument scan failed; remaining universe continued"));
        verify(scans).partialRun(any(UUID.class), eq(0), eq(0), eq("PARTIAL_SCAN"), eq("1 instrument(s) failed during scan"));
    }
}
