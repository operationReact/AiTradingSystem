package in.aitradingsystem.mind;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import in.aitradingsystem.marketdata.MarketCandle;
import in.aitradingsystem.marketdata.MarketDataRepository;
import in.aitradingsystem.marketdata.MarketSessionCalendar;
import in.aitradingsystem.scanner.CandidateFingerprint;
import in.aitradingsystem.scanner.MovingAverageReclaimStrategy;
import in.aitradingsystem.scanner.ResearchScanCandidateView;
import in.aitradingsystem.scanner.ResearchScanRepository;
import in.aitradingsystem.scanner.ResearchScanSummary;
import in.aitradingsystem.scanner.ScanContext;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MindStrategyReplayServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-09T09:00:00Z");
    private static final Instant SCAN_AT = NOW.minusSeconds(60);
    private static final Instant SIGNAL_AT = NOW.minusSeconds(600);
    private static final UUID RUN_ID = UUID.randomUUID();
    private static final UUID CATALOGUE = UUID.randomUUID();
    private final ResearchScanRepository scans = mock(ResearchScanRepository.class);
    private final MarketDataRepository candles = mock(MarketDataRepository.class);
    private final MarketSessionCalendar calendar = mock(MarketSessionCalendar.class);
    private final MovingAverageReclaimStrategy strategy = new MovingAverageReclaimStrategy();
    private final MindStrategyReplayService service = new MindStrategyReplayService(
        scans, candles, strategy, calendar, Clock.fixed(NOW, ZoneOffset.UTC));

    private ResearchScanSummary scan(String status) {
        return new ResearchScanSummary(
            RUN_ID, "FIVE_MINUTE", status, 100, 80, 1, "baseline-v1",
            SCAN_AT, SCAN_AT.plusSeconds(15), null, null);
    }

    private List<MarketCandle> bars() {
        List<MarketCandle> result = new ArrayList<>();
        Instant first = SIGNAL_AT.minusSeconds(20 * 300L);
        for (int i = 0; i < 21; i++) {
            BigDecimal close = new BigDecimal(i == 19 ? "95" : i == 20 ? "110" : "100");
            BigDecimal open = new BigDecimal(i == 20 ? "96" : "100");
            BigDecimal high = new BigDecimal(i == 19 ? "100" : i == 20 ? "111" : "101");
            BigDecimal low = new BigDecimal(i == 19 ? "94" : i == 20 ? "95" : "99");
            result.add(new MarketCandle(
                i + 1L, 42L, "NSE", "INFY-EQ", "1234", CATALOGUE, "FIVE_MINUTE",
                first.plusSeconds(i * 300L), open, high, low, close,
                1000L, "ANGEL_ONE_SMARTAPI", SCAN_AT.minusSeconds(10)));
        }
        return List.copyOf(result);
    }

    private ResearchScanCandidateView candidate(List<MarketCandle> input, String fingerprint) {
        var computed = strategy.evaluate(
            new ScanContext(42L, "INFY-EQ", "NSE", "FIVE_MINUTE", SCAN_AT, input)).orElseThrow();
        String fp = fingerprint == null
            ? CandidateFingerprint.of(42L, "FIVE_MINUTE",
                MovingAverageReclaimStrategy.NAME, strategy.descriptor().version(),
                SIGNAL_AT, computed.referencePrice(), computed.suggestedStop(), computed.suggestedTarget())
            : fingerprint;
        return new ResearchScanCandidateView(
            RUN_ID, 42L, "INFY-EQ", "NSE", "FIVE_MINUTE",
            MovingAverageReclaimStrategy.NAME, computed.score(),
            computed.referencePrice(), computed.suggestedStop(),
            computed.suggestedTarget(), SIGNAL_AT, computed.reasons(),
            fp, 2, CATALOGUE, SIGNAL_AT, 7L);
    }

    private void persisted(List<MarketCandle> input, ResearchScanCandidateView candidate) {
        when(scans.latestCompletedRun()).thenReturn(Optional.of(scan("SUCCEEDED")));
        when(scans.rankedCandidates(RUN_ID, 200)).thenReturn(List.of(candidate));
        when(candles.recentCandlesAsOf(42L, "FIVE_MINUTE", SIGNAL_AT, 21)).thenReturn(input);
        // Freeze original source independently of replay input: the scanner
        // stored this at capture time, even if market_candle later changed.
        var snapshot = MindSourceSnapshot.fromCandles(
            bars(), 42L, "NSE", "INFY-EQ", "FIVE_MINUTE", SIGNAL_AT, SCAN_AT);
        when(scans.findMindInputCustody(RUN_ID, candidate.candidateFingerprint()))
            .thenReturn(Optional.of(new ResearchScanRepository.MindInputCustody(
                snapshot.digest(), snapshot.canonicalInput(),
                SCAN_AT.plusSeconds(5), SIGNAL_AT,
                snapshot.maxFetchedAt(), CATALOGUE)));
    }

    @Test
    void realHistoricalRecalculationProducesWaitButNeverATradeOrPaperOrder() {
        List<MarketCandle> input = bars();
        persisted(input, candidate(input, null));
        when(calendar.isInsideCashSession(NOW)).thenReturn(true);
        var result = service.inspect();
        assertEquals("mind.strategy-replay.v1", result.contractVersion());
        assertEquals("REPRODUCED", result.replayStatus());
        assertEquals("WAIT", result.decisionPosture());
        assertEquals("NSE:INFY-EQ", result.instrument());
        assertEquals(SIGNAL_AT, result.dataCutoffAt());
        assertTrue(result.blockingReasons().contains("ACCOUNT_RISK_NOT_EVALUATED"));
        assertTrue(result.blockingReasons().contains("PAPER_SHADOW_ENROLLMENT_REQUIRED"));
        assertFalse(result.independentlyVerifiedSignal());
        assertEquals("NOT_ENROLLED", result.paperShadowStatus());
        assertFalse(result.paperOrderSubmitted());
        assertFalse(result.executionEnabled());
    }

    @Test
    void mutatedBarSnapshotFailsReproductionInsteadOfRecommendingTrade() {
        var original = bars();
        persisted(original, candidate(original, "a".repeat(64)));
        var result = service.inspect();
        assertEquals("INVALID", result.replayStatus());
        assertEquals("ABSTAIN", result.decisionPosture());
        assertTrue(result.blockingReasons().contains("STRATEGY_FINGERPRINT_MISMATCH"));
    }

    @Test
    void futureBarOrDataBackfilledAfterScanIsRejected() {
        var original = bars();
        List<MarketCandle> after = new ArrayList<>(original);
        var end = after.getLast();
        after.set(20, new MarketCandle(end.id(), end.instrumentId(), end.exchange(),
            end.tradingSymbol(), end.symbolToken(), end.catalogueVersion(),
            end.interval(), end.candleTime(), end.open(), end.high(), end.low(),
            end.close(), end.volume(), end.source(), SCAN_AT.plusSeconds(5)));
        persisted(after, candidate(original, null));
        var result = service.inspect();
        assertEquals("INVALID", result.replayStatus());
        assertTrue(result.blockingReasons().contains("SOURCE_BARS_UNAVAILABLE_OR_UNVERIFIED"));
    }

    @Test
    void rejectsSyntheticCandleSourcesEvenWhenTechnicalPatternMatches() {
        var original = bars();
        List<MarketCandle> synthetic = new ArrayList<>();
        for (var bar : original) synthetic.add(new MarketCandle(
            bar.id(), bar.instrumentId(), bar.exchange(), bar.tradingSymbol(),
            bar.symbolToken(), bar.catalogueVersion(), bar.interval(), bar.candleTime(),
            bar.open(), bar.high(), bar.low(), bar.close(), bar.volume(),
            "TEST_FIXTURE", bar.fetchedAt()));
        persisted(synthetic, candidate(original, null));
        assertEquals("INVALID", service.inspect().replayStatus());
    }

    @Test
    void missingCapturedInputCannotBeBackfilledIntoSuccessfulResearch() {
        var original = bars();
        var candidate = candidate(original, null);
        persisted(original, candidate);
        when(scans.findMindInputCustody(RUN_ID, candidate.candidateFingerprint()))
            .thenReturn(Optional.empty());
        var result = service.inspect();
        assertEquals("INVALID", result.replayStatus());
        assertEquals("ABSTAIN", result.decisionPosture());
        assertTrue(result.blockingReasons().contains("INPUT_CUSTODY_NOT_RECORDED"));
        assertFalse(result.executionEnabled());
    }

    @Test
    void retrospectiveCandleEditIsDetectedEvenIfTheSignalStillMatches() {
        var original = bars();
        var revised = new ArrayList<>(original);
        var old = revised.getFirst();
        revised.set(0, new MarketCandle(old.id(), old.instrumentId(),
            old.exchange(), old.tradingSymbol(), old.symbolToken(),
            old.catalogueVersion(), old.interval(), old.candleTime(),
            new BigDecimal("100.25"), old.high(), old.low(), old.close(),
            old.volume(), old.source(), old.fetchedAt()));
        persisted(revised, candidate(original, null));
        var result = service.inspect();
        assertEquals("INVALID", result.replayStatus());
        assertTrue(result.blockingReasons().contains("SOURCE_CANDLE_REVISION_DETECTED"));
        assertFalse(result.paperOrderSubmitted());
    }

    @Test
    void missingLatestCandidateOrFailedScanNeverUsesOlderWinner() {
        when(scans.latestCompletedRun()).thenReturn(Optional.of(scan("PARTIAL")));
        assertEquals("LATEST_SCAN_NOT_SUCCESSFUL", service.inspect().blockingReasons().getFirst());
        verify(scans, never()).rankedCandidates(any(), anyInt());

        reset(scans);
        when(scans.latestCompletedRun()).thenReturn(Optional.of(scan("SUCCEEDED")));
        when(scans.rankedCandidates(RUN_ID, 200)).thenReturn(List.of());
        assertEquals("NO_MATCH", service.inspect().replayStatus());
        assertEquals("ABSTAIN", service.inspect().decisionPosture());
    }

    @Test
    void closedCashMarketAlwaysAbstainsEvenForReproducedResearch() {
        var original = bars();
        persisted(original, candidate(original, null));
        when(calendar.isInsideCashSession(NOW)).thenReturn(false);
        var result = service.inspect();
        assertEquals("REPRODUCED", result.replayStatus());
        assertEquals("ABSTAIN", result.decisionPosture());
        assertTrue(result.blockingReasons().contains("CASH_MARKET_CLOSED"));
    }

    @Test
    void noDataReturnsAbstainWithoutCallingBrokerOrPaperSubsystems() {
        when(scans.latestCompletedRun()).thenReturn(Optional.empty());
        var result = service.inspect();
        assertEquals("UNAVAILABLE", result.replayStatus());
        assertEquals("ABSTAIN", result.decisionPosture());
        assertFalse(result.paperOrderSubmitted());
        verifyNoInteractions(candles, calendar);
    }
}
