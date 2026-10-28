package in.aitradingsystem.scanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.aitradingsystem.marketdata.HistoricalCandleInterval;
import in.aitradingsystem.marketdata.MarketDataRepository;
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
}
