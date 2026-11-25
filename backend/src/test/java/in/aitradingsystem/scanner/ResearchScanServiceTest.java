package in.aitradingsystem.scanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.aitradingsystem.instrument.Instrument;
import in.aitradingsystem.mind.MindSourceSnapshot;
import in.aitradingsystem.marketdata.HistoricalCandleInterval;
import in.aitradingsystem.marketdata.MarketDataRepository;
import in.aitradingsystem.marketdata.MarketCandle;
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
    void acceptsFullEquityUniverseAboveOldFiveHundredInstrumentCeiling() {
        ResearchScanService service = new ResearchScanService(scans, marketData, clock, 3500, 60);
        when(scans.activeCashEquityUniverse(2756)).thenReturn(List.of());
        when(scans.requireRun(any(UUID.class))).thenReturn(
            new ResearchScanSummary(UUID.randomUUID(), "FIVE_MINUTE", "SUCCEEDED",
                2756, 0, 0, "VOLUME_BREAKOUT_V1,MA_RECLAIM_V1",
                clock.instant(), clock.instant(), null, null));
        service.run(HistoricalCandleInterval.FIVE_MINUTE, 2756);
        verify(scans).activeCashEquityUniverse(2756);
    }

    @Test
    void emptyCandleHistoryIsReportedAsIncompleteCoverageNotAFullScan() {
        ResearchScanService service = new ResearchScanService(scans, marketData, clock, 3500, 60);
        Instrument stock = new Instrument(42L, "NSE_CM", "NSE", "42",
            "TEST-EQ", "Test equity", "EQ", null, null, 1,
            new BigDecimal("0.05"), UUID.randomUUID());
        when(scans.activeCashEquityUniverse(2756)).thenReturn(List.of(stock));
        when(marketData.recentCandles(42L, "FIVE_MINUTE", 64)).thenReturn(List.of());
        ResearchScanSummary expected = new ResearchScanSummary(
            UUID.randomUUID(), "FIVE_MINUTE", "PARTIAL", 2756, 1, 0,
            "VOLUME_BREAKOUT_V1,MA_RECLAIM_V1", clock.instant(),
            clock.instant(), "INCOMPLETE_HISTORY_COVERAGE",
            "1 instrument(s) lack the 64-candle research window");
        when(scans.requireRun(any(UUID.class))).thenReturn(expected);

        ResearchScanSummary actual = service.run(HistoricalCandleInterval.FIVE_MINUTE, 2756);

        assertEquals("PARTIAL", actual.status());
        verify(scans).saveDiagnostic(any(UUID.class), eq(42L), eq(null),
            eq("WARN"), eq("INSUFFICIENT_CANDLE_HISTORY"),
            eq("Fewer than 64 stored candles; full strategy coverage unavailable"));
        verify(scans).partialRun(any(UUID.class), eq(1), eq(0),
            eq("INCOMPLETE_HISTORY_COVERAGE"),
            eq("1 instrument(s) lack the 64-candle research window"));
        verify(scans, never()).finishRun(any(UUID.class), eq(1), eq(0));
    }

    @Test
    void freezesOpeningRangeCandidateInputsForMindReplay() {
        Instant evaluatedAt = Instant.parse("2026-10-28T09:45:00Z");
        Clock scanClock = Clock.fixed(evaluatedAt, ZoneOffset.UTC);
        ResearchScanService service = new ResearchScanService(scans, marketData, scanClock, 10, 60);
        UUID catalogue = UUID.randomUUID();
        Instrument stock = new Instrument(42L, "NSE_CM", "NSE", "42",
            "TEST-EQ", "Test equity", "EQ", null, null, 1,
            new BigDecimal("0.05"), catalogue);
        when(scans.activeCashEquityUniverse(1)).thenReturn(List.of(stock));
        List<MarketCandle> bars = new java.util.ArrayList<>();
        Instant first = Instant.parse("2026-10-28T03:45:00Z");
        for (int i = 0; i < 64; i++) {
            Instant candleTime = first.plusSeconds(i * 300L);
            boolean breakout = i == 63;
            BigDecimal open = breakout ? new BigDecimal("101.5") : new BigDecimal("100");
            BigDecimal high = breakout ? new BigDecimal("104") : new BigDecimal("101");
            BigDecimal low = breakout ? new BigDecimal("101.2") : new BigDecimal("99");
            BigDecimal close = breakout ? new BigDecimal("103") : new BigDecimal("100");
            bars.add(new MarketCandle(i + 1L, 42L, "NSE", "TEST-EQ", "42", catalogue,
                "FIVE_MINUTE", candleTime, open, high, low, close, breakout ? 2000L : 1000L,
                "ANGEL_ONE_SMARTAPI", candleTime.plusSeconds(30)));
        }
        when(marketData.recentCandles(42L, "FIVE_MINUTE", 64)).thenReturn(List.copyOf(bars));
        when(scans.saveCandidate(any(UUID.class), any(ScanCandidate.class), any(), any(), any(), any()))
            .thenReturn(true);
        when(scans.requireRun(any(UUID.class))).thenReturn(new ResearchScanSummary(
            UUID.randomUUID(), "FIVE_MINUTE", "SUCCEEDED", 1, 1, 0,
            OpeningRangeBreakoutStrategy.NAME, evaluatedAt, evaluatedAt, null, null));

        service.run(HistoricalCandleInterval.FIVE_MINUTE, 1);

        verify(scans).recordOpeningRangeMindInputCustody(
            any(UUID.class),
            org.mockito.ArgumentMatchers.argThat(c ->
                OpeningRangeBreakoutStrategy.NAME.equals(c.strategy())),
            eq("1"),
            org.mockito.ArgumentMatchers.argThat(snapshot ->
                snapshot.candleCount() == MindSourceSnapshot.OPENING_RANGE_BARS &&
                snapshot.lastCandleAt().equals(bars.getLast().candleTime())));
        assertEquals(MindSourceSnapshot.OPENING_RANGE_BARS, bars.size());
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
