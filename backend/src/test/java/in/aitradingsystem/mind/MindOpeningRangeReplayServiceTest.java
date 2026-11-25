package in.aitradingsystem.mind;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.aitradingsystem.marketdata.MarketCandle;
import in.aitradingsystem.marketdata.MarketDataRepository;
import in.aitradingsystem.marketdata.MarketSessionCalendar;
import in.aitradingsystem.scanner.CandidateFingerprint;
import in.aitradingsystem.scanner.OpeningRangeBreakoutStrategy;
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

class MindOpeningRangeReplayServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-25T09:10:00Z");
    private static final Instant SCAN_AT = NOW.minusSeconds(9 * 60L);
    private static final Instant SIGNAL_AT = NOW.minusSeconds(10 * 60L);
    private static final UUID RUN_ID = UUID.randomUUID();
    private static final UUID CATALOGUE = UUID.randomUUID();

    private final ResearchScanRepository scans = mock(ResearchScanRepository.class);
    private final MarketDataRepository candles = mock(MarketDataRepository.class);
    private final MarketSessionCalendar calendar = mock(MarketSessionCalendar.class);
    private final OpeningRangeBreakoutStrategy strategy = new OpeningRangeBreakoutStrategy();
    private final MindOpeningRangeReplayService service = new MindOpeningRangeReplayService(
        scans, candles, strategy, calendar, Clock.fixed(NOW, ZoneOffset.UTC));

    private ResearchScanSummary scan(String status) {
        return new ResearchScanSummary(RUN_ID, "FIVE_MINUTE", status,
            1, 1, 0, OpeningRangeBreakoutStrategy.NAME, SCAN_AT,
            SCAN_AT.plusSeconds(15), null, null);
    }

    private List<MarketCandle> bars() {
        List<MarketCandle> result = new ArrayList<>();
        Instant first = SIGNAL_AT.minusSeconds(63 * 300L);
        for (int i = 0; i < MindSourceSnapshot.OPENING_RANGE_BARS; i++) {
            boolean breakout = i == MindSourceSnapshot.OPENING_RANGE_BARS - 1;
            Instant time = first.plusSeconds(i * 300L);
            result.add(new MarketCandle(i + 1L, 42L, "NSE", "INFY-EQ", "1234",
                CATALOGUE, "FIVE_MINUTE", time,
                breakout ? new BigDecimal("101.5") : new BigDecimal("100"),
                breakout ? new BigDecimal("104") : new BigDecimal("101"),
                breakout ? new BigDecimal("101.2") : new BigDecimal("99"),
                breakout ? new BigDecimal("103") : new BigDecimal("100"),
                breakout ? 2000L : 1000L, "ANGEL_ONE_SMARTAPI", time.plusSeconds(30)));
        }
        return List.copyOf(result);
    }

    private ResearchScanCandidateView candidate(List<MarketCandle> input) {
        var generated = strategy.evaluate(new ScanContext(42L, "INFY-EQ", "NSE",
            "FIVE_MINUTE", SCAN_AT, input)).orElseThrow();
        String fingerprint = CandidateFingerprint.of(42L, "FIVE_MINUTE",
            OpeningRangeBreakoutStrategy.NAME, strategy.descriptor().version(),
            SIGNAL_AT, generated.referencePrice(), generated.suggestedStop(),
            generated.suggestedTarget());
        return new ResearchScanCandidateView(RUN_ID, 42L, "INFY-EQ", "NSE",
            "FIVE_MINUTE", OpeningRangeBreakoutStrategy.NAME, generated.score(),
            generated.referencePrice(), generated.suggestedStop(),
            generated.suggestedTarget(), SIGNAL_AT, generated.reasons(),
            fingerprint, 1, CATALOGUE, SIGNAL_AT, 7L);
    }

    private void persist(List<MarketCandle> input, ResearchScanCandidateView candidate) {
        when(scans.latestCompletedRun()).thenReturn(Optional.of(scan("SUCCEEDED")));
        when(scans.rankedCandidates(RUN_ID, 200)).thenReturn(List.of(candidate));
        when(candles.recentCandlesAsOf(42L, "FIVE_MINUTE", SIGNAL_AT,
            MindSourceSnapshot.OPENING_RANGE_BARS)).thenReturn(input);
        var snapshot = MindSourceSnapshot.fromOpeningRangeCandles(
            bars(), 42L, "NSE", "INFY-EQ", "FIVE_MINUTE", SIGNAL_AT,
            SCAN_AT.plusSeconds(5));
        when(scans.findMindInputCustody(RUN_ID, candidate.candidateFingerprint()))
            .thenReturn(Optional.of(new ResearchScanRepository.MindInputCustody(
                snapshot.digest(), snapshot.canonicalInput(), SCAN_AT.plusSeconds(5),
                SIGNAL_AT, snapshot.maxFetchedAt(), CATALOGUE)));
    }

    @Test
    void exactCapturedOrbInputReplaysWithoutCreatingOrderAuthority() {
        var input = bars();
        var candidate = candidate(input);
        persist(input, candidate);
        when(calendar.isInsideCashSession(NOW)).thenReturn(true);

        var replay = service.inspect();

        assertEquals("mind.opening-range-replay.v1", replay.contractVersion());
        assertEquals("OPENING_RANGE_BREAKOUT_V1", replay.strategyCode());
        assertEquals("REPRODUCED", replay.replayStatus());
        assertEquals("WAIT", replay.decisionPosture());
        assertEquals(SIGNAL_AT, replay.dataCutoffAt());
        assertTrue(replay.blockingReasons().contains("CAPTURED_INPUT_DIGEST_REPRODUCED"));
        assertFalse(replay.independentlyVerifiedSignal());
        assertFalse(replay.paperOrderSubmitted());
        assertFalse(replay.executionEnabled());
    }

    @Test
    void revisedCandleFailsClosedAgainstTheFrozenDigest() {
        var original = bars();
        var candidate = candidate(original);
        var revised = new ArrayList<>(original);
        var first = revised.getFirst();
        revised.set(0, new MarketCandle(first.id(), first.instrumentId(),
            first.exchange(), first.tradingSymbol(), first.symbolToken(),
            first.catalogueVersion(), first.interval(), first.candleTime(),
            first.open().add(new BigDecimal("0.01")), first.high(), first.low(),
            first.close(), first.volume(), first.source(), first.fetchedAt()));
        persist(revised, candidate);

        var replay = service.inspect();

        assertEquals("INVALID", replay.replayStatus());
        assertEquals("ABSTAIN", replay.decisionPosture());
        assertTrue(replay.blockingReasons().contains("SOURCE_CANDLE_REVISION_DETECTED"));
        assertFalse(replay.executionEnabled());
    }

    @Test
    void missingCustodyAndFailedScanAbstainInsteadOfUsingOtherEvidence() {
        when(scans.latestCompletedRun()).thenReturn(Optional.of(scan("PARTIAL")));
        assertEquals("LATEST_SCAN_NOT_SUCCESSFUL",
            service.inspect().blockingReasons().getFirst());

        when(scans.latestCompletedRun()).thenReturn(Optional.of(scan("SUCCEEDED")));
        var input = bars();
        var candidate = candidate(input);
        when(scans.rankedCandidates(RUN_ID, 200)).thenReturn(List.of(candidate));
        when(scans.findMindInputCustody(RUN_ID, candidate.candidateFingerprint()))
            .thenReturn(Optional.empty());

        var replay = service.inspect();

        assertEquals("INVALID", replay.replayStatus());
        assertTrue(replay.blockingReasons().contains("INPUT_CUSTODY_NOT_RECORDED"));
        assertFalse(replay.paperOrderSubmitted());
    }

    @Test
    void closedCashSessionKeepsReplayedCandidateAtAbstain() {
        var input = bars();
        var candidate = candidate(input);
        persist(input, candidate);
        when(calendar.isInsideCashSession(NOW)).thenReturn(false);

        var replay = service.inspect();

        assertEquals("REPRODUCED", replay.replayStatus());
        assertEquals("ABSTAIN", replay.decisionPosture());
        assertTrue(replay.blockingReasons().contains("CASH_MARKET_CLOSED"));
    }
}
