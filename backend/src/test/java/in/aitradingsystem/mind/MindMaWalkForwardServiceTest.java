package in.aitradingsystem.mind;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import in.aitradingsystem.marketdata.ExpectedCandleSlot;
import in.aitradingsystem.marketdata.HistoricalCandleInterval;
import in.aitradingsystem.marketdata.MarketCandle;
import in.aitradingsystem.marketdata.MarketDataRepository;
import in.aitradingsystem.marketdata.MarketSessionCalendar;
import in.aitradingsystem.scanner.MovingAverageReclaimStrategy;
import in.aitradingsystem.scanner.ResearchScanCandidateView;
import in.aitradingsystem.scanner.ResearchScanRepository;
import in.aitradingsystem.scanner.ResearchScanSummary;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MindMaWalkForwardServiceTest {
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Instant NOW = Instant.parse("2026-11-09T12:00:00Z");
    private static final UUID RUN = UUID.randomUUID();
    private static final UUID CATALOGUE = UUID.randomUUID();
    private static final String FINGERPRINT = "a".repeat(64);
    private final ResearchScanRepository scans = mock(ResearchScanRepository.class);
    private final MarketDataRepository market = mock(MarketDataRepository.class);
    private final MarketSessionCalendar calendar = mock(MarketSessionCalendar.class);
    private final MindMaWalkForwardEvaluator evaluator = mock(MindMaWalkForwardEvaluator.class);
    private final MindMaWalkForwardService service = new MindMaWalkForwardService(
        scans, market, calendar, evaluator, Clock.fixed(NOW, ZoneOffset.UTC));

    private void setupReference() {
        when(evaluator.assumptions()).thenReturn(
            new MindMaWalkForwardEvaluator.Assumptions(
                new BigDecimal("100000"), new BigDecimal("0.001"),
                new BigDecimal("0.0005"), new BigDecimal("0.5"),
                new BigDecimal("20"), "ILLUSTRATIVE_COMPOSITE_COSTS_NOT_ACTUAL_BROKER_OR_STATUTORY_CHARGES"));
        var run = new ResearchScanSummary(RUN, "FIVE_MINUTE",
            "SUCCEEDED", 100, 80, 1, MovingAverageReclaimStrategy.NAME,
            NOW.minusSeconds(600), NOW.minusSeconds(300), null, null);
        var candidate = new ResearchScanCandidateView(
            RUN, 42L, "INFY-EQ", "NSE", "FIVE_MINUTE",
            MovingAverageReclaimStrategy.NAME, 0.8, new BigDecimal("110"),
            new BigDecimal("94"), new BigDecimal("142"), NOW.minusSeconds(900),
            List.of("RESEARCH_ONLY"), FINGERPRINT, 1, CATALOGUE,
            NOW.minusSeconds(900), 7L);
        when(scans.latestCompletedRun()).thenReturn(Optional.of(run));
        when(scans.rankedCandidates(RUN, 200)).thenReturn(List.of(candidate));
        when(scans.findMindInputCustody(RUN, FINGERPRINT)).thenReturn(
            Optional.of(new ResearchScanRepository.MindInputCustody(
                "b".repeat(64), "FROZEN", NOW.minusSeconds(450),
                NOW.minusSeconds(900), NOW.minusSeconds(1200), CATALOGUE)));
        when(calendar.expectedSlots(any(LocalDate.class), eq(HistoricalCandleInterval.FIVE_MINUTE)))
            .thenAnswer(invocation -> slots(invocation.getArgument(0)));
    }

    private static List<ExpectedCandleSlot> slots(LocalDate date) {
        if (date.getDayOfWeek() == DayOfWeek.SATURDAY ||
            date.getDayOfWeek() == DayOfWeek.SUNDAY) return List.of();
        List<ExpectedCandleSlot> result = new ArrayList<>();
        Instant start = date.atTime(9, 15).atZone(IST).toInstant();
        for (int i = 0; i < 75; i++) {
            result.add(new ExpectedCandleSlot(start.plusSeconds(i * 300L),
                HistoricalCandleInterval.FIVE_MINUTE));
        }
        return result;
    }

    private static List<MarketCandle> fullHistory() {
        List<MarketCandle> result = new ArrayList<>();
        LocalDate to = NOW.atZone(IST).toLocalDate().minusDays(1);
        LocalDate from = to.minusDays(179);
        Instant fetched = NOW.minusSeconds(3600);
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            for (var slot : slots(date)) {
                result.add(new MarketCandle(result.size() + 1L, 42, "NSE",
                    "INFY-EQ", "1234", CATALOGUE, "FIVE_MINUTE",
                    slot.timestamp(), new BigDecimal("100"),
                    new BigDecimal("101"), new BigDecimal("99"),
                    new BigDecimal("100"), 1000, "ANGEL_ONE_SMARTAPI", fetched));
            }
        }
        return List.copyOf(result);
    }

    private void setupHistory(List<MarketCandle> rows) {
        when(market.backtestCandles(eq(42L), eq("FIVE_MINUTE"),
            any(Instant.class), any(Instant.class), eq(20001))).thenReturn(rows);
    }

    private void fixedMetrics(int validationTrades) {
        when(evaluator.evaluate(anyList())).thenAnswer(invocation -> {
            List<?> bars = invocation.getArgument(0);
            boolean train = bars.size() > 2000;
            int count = train ? 12 : validationTrades;
            return new MindMaWalkForwardEvaluator.Metrics(bars.size(), count,
                Math.max(0, count / 2), new BigDecimal("100"),
                new BigDecimal("20"), new BigDecimal("10"),
                new BigDecimal("70"), new BigDecimal("0.0700"),
                new BigDecimal("1.2500"));
        });
    }

    @Test
    void completeNonOverlappingHoldsOutOnlyMeanResearchMetricsNotTradeApproval() {
        setupReference();
        setupHistory(fullHistory());
        fixedMetrics(4);  // 6 validation folds * 4 OOS trades => 24
        var report = service.inspect();
        assertEquals("EVALUATED_RESEARCH_ONLY", report.status());
        assertEquals(6, report.folds().size());
        assertEquals(6, report.completedFolds());
        assertEquals(24, report.oosTrades());
        assertEquals(new BigDecimal("0.0700"), report.meanOosReturnPercent());
        assertEquals(new BigDecimal("1.2500"), report.worstOosDrawdownPercent());
        assertEquals(new BigDecimal("420.00"), report.oosNetPnl());
        assertTrue(report.blockers().contains("SELECTION_BIAS_NOT_CORRECTED"));
        assertTrue(report.blockers().contains("SOURCE_KNOWN_AT_NOT_INDEPENDENTLY_PROVEN"));
        assertEquals("ABSTAIN", report.decisionPosture());
        assertFalse(report.validatedTradingEdge());
        assertFalse(report.sourceKnownAtIndependentlyVerified());
        assertFalse(report.paperOrderSubmitted());
        assertFalse(report.executionEnabled());

        for (int i = 1; i < report.folds().size(); i++) {
            assertEquals(report.folds().get(i - 1).validationTo().plusDays(1),
                report.folds().get(i).validationFrom(),
                "Validation folds must not overlap");
        }
    }

    @Test
    void missingHistoricDataPreventsOutOfSamplePerformanceClaims() {
        setupReference();
        setupHistory(List.of());
        var report = service.inspect();
        assertEquals("INSUFFICIENT_COVERAGE", report.status());
        assertEquals(0, report.oosTrades());
        assertNull(report.meanOosReturnPercent());
        assertTrue(report.blockers().contains("OUT_OF_SAMPLE_COVERAGE_INCOMPLETE"));
        verify(evaluator, never()).evaluate(anyList());
    }

    @Test
    void zeroOosTradesIsAnInsufficientSampleEvenWithCompleteBarCoverage() {
        setupReference();
        setupHistory(fullHistory());
        fixedMetrics(0);
        var report = service.inspect();
        assertEquals("INSUFFICIENT_SAMPLE", report.status());
        assertEquals(0, report.oosTrades());
        assertTrue(report.blockers().contains("OUT_OF_SAMPLE_TRADE_SAMPLE_INSUFFICIENT"));
        assertFalse(report.validatedTradingEdge());
    }

    @Test
    void missingCustodyCannotBeReplacedByRetrospectiveBacktest() {
        setupReference();
        when(scans.findMindInputCustody(RUN, FINGERPRINT)).thenReturn(Optional.empty());
        var report = service.inspect();
        assertEquals("UNAVAILABLE", report.status());
        assertTrue(report.blockers().contains("REFERENCE_SCAN_CUSTODY_MISSING"));
        verifyNoInteractions(market, calendar);
    }

    @Test
    void fabricatedMarketSourceIsRejectedWithoutDisplayingNetReturns() {
        setupReference();
        var rows = fullHistory();
        var updated = new ArrayList<>(rows);
        MarketCandle source = updated.getFirst();
        updated.set(0, new MarketCandle(source.id(), source.instrumentId(),
            source.exchange(), source.tradingSymbol(), source.symbolToken(),
            source.catalogueVersion(), source.interval(), source.candleTime(),
            source.open(), source.high(), source.low(), source.close(),
            source.volume(), "TEST_FIXTURE", source.fetchedAt()));
        setupHistory(updated);
        var report = service.inspect();
        assertEquals("UNAVAILABLE", report.status());
        assertTrue(report.blockers().contains("HISTORICAL_SOURCE_INVALID_OR_GAPPED"));
        assertNull(report.meanOosReturnPercent());
        verify(evaluator, never()).evaluate(anyList());
    }

    @Test
    void missingLatestScanCannotFallBackToOlderWinner() {
        when(scans.latestCompletedRun()).thenReturn(Optional.empty());
        when(evaluator.assumptions()).thenReturn(
            new MindMaWalkForwardEvaluator.Assumptions(
                new BigDecimal("100000"), new BigDecimal("0.001"),
                new BigDecimal("0.0005"), new BigDecimal("0.5"),
                new BigDecimal("20"), "COST_ESTIMATES"));
        var result = service.inspect();
        assertEquals("UNAVAILABLE", result.status());
        assertEquals("ABSTAIN", result.decisionPosture());
        verify(scans, never()).rankedCandidates(any(), anyInt());
        verifyNoInteractions(market, calendar);
    }
}
