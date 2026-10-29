package in.aitradingsystem.scanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.aitradingsystem.instrument.Instrument;
import in.aitradingsystem.marketdata.HistoricalCandleInterval;
import in.aitradingsystem.marketdata.MarketDataQualityResult;
import in.aitradingsystem.marketdata.MarketDataQualityService;
import in.aitradingsystem.marketdata.MarketDataRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ResearchScanRuntimeTest {
    private final Instant now = Instant.parse("2026-10-29T07:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);

    @Test
    void instrumentFailureIsDiagnosedAndRemainingUniverseContinuesAsPartial() {
        ResearchScanRepository scans = mock(ResearchScanRepository.class);
        MarketDataRepository marketData = mock(MarketDataRepository.class);
        MarketDataQualityService quality = mock(MarketDataQualityService.class);
        HistoricalCandleInterval interval = HistoricalCandleInterval.FIVE_MINUTE;
        Instrument failed = instrument(11L, "FAIL-EQ");
        Instrument continued = instrument(12L, "CONTINUE-EQ");
        MarketDataQualityResult degraded = mock(MarketDataQualityResult.class);

        when(scans.activeCashEquityUniverse(2)).thenReturn(List.of(failed, continued));
        when(marketData.recentCandles(failed.id(), interval.name(), 64))
                .thenThrow(new IllegalStateException("provider fixture failure"));
        when(marketData.recentCandles(continued.id(), interval.name(), 64)).thenReturn(List.of());
        when(scans.requireRun(any(UUID.class))).thenReturn(new ResearchScanSummary(
                UUID.randomUUID(), interval.name(), "PARTIAL", 2, 1, 0, "TEST", now, now,
                "PARTIAL_SCAN", "1 instrument(s) failed during scan"));

        ResearchScanService service = new ResearchScanService(scans, marketData, quality, clock, 200, 60);
        ResearchScanSummary result = service.run(interval, 2);

        assertEquals("PARTIAL", result.status());
        verify(marketData).recentCandles(continued.id(), interval.name(), 64);
        verify(scans).saveDiagnostic(any(UUID.class), eq(failed.id()), eq(null), eq("ERROR"),
                eq("INSTRUMENT_SCAN_FAILED"), eq("Instrument scan failed; remaining universe continued"));
        verify(scans).partialRun(any(UUID.class), eq(1), eq(0), eq("PARTIAL_SCAN"),
                eq("1 instrument(s) failed during scan"));
    }

    @Test
    void universeFailureMarksWholeRunFailedAndPersistsDiagnostic() {
        ResearchScanRepository scans = mock(ResearchScanRepository.class);
        MarketDataRepository marketData = mock(MarketDataRepository.class);
        when(scans.activeCashEquityUniverse(5)).thenThrow(new IllegalStateException("database unavailable"));
        when(scans.requireRun(any(UUID.class))).thenReturn(new ResearchScanSummary(
                UUID.randomUUID(), HistoricalCandleInterval.FIVE_MINUTE.name(), "FAILED", 5, 0, 0,
                "TEST", now, now, "SCAN_FAILED", "Research scan failed before completion"));

        ResearchScanService service = new ResearchScanService(scans, marketData, clock, 200, 60);
        ResearchScanSummary result = service.run(HistoricalCandleInterval.FIVE_MINUTE, 5);

        assertEquals("FAILED", result.status());
        verify(scans).saveDiagnostic(any(UUID.class), eq(null), eq(null), eq("ERROR"), eq("SCAN_FAILED"),
                eq("Research scan failed before the universe could be completed"));
        verify(scans).failRun(any(UUID.class), eq(0), eq(0), eq("SCAN_FAILED"),
                eq("Research scan failed before completion"));
    }

    private static Instrument instrument(long id, String symbol) {
        return new Instrument(id, "NSE_EQ", "NSE", "token-" + id, symbol,
                symbol, "EQ", null, null, 1, new BigDecimal("0.05"), UUID.randomUUID());
    }
}
