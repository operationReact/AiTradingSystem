package in.aitradingsystem.tradingrooms;

import static org.junit.jupiter.api.Assertions.*;

import in.aitradingsystem.tradingrooms.FnoFuturesMeanReversionV1.CompletedSession;
import in.aitradingsystem.tradingrooms.FnoFuturesMeanReversionV1.Contract;
import in.aitradingsystem.tradingrooms.FnoFuturesMeanReversionV1.Decision;
import in.aitradingsystem.tradingrooms.FnoFuturesMeanReversionV1.Pattern;
import in.aitradingsystem.tradingrooms.FnoFuturesMeanReversionV1.SourceRights;
import in.aitradingsystem.tradingrooms.FnoFuturesMeanReversionV1.Status;
import in.aitradingsystem.tradingrooms.FnoFuturesMeanReversionV1.Thresholds;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FnoFuturesMeanReversionV1Test {
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Instant AS_OF = Instant.parse("2026-11-20T11:00:00Z");
    private static final LocalDate[] DATES = {
        LocalDate.of(2026,11,9), LocalDate.of(2026,11,10),
        LocalDate.of(2026,11,11), LocalDate.of(2026,11,12),
        LocalDate.of(2026,11,13), LocalDate.of(2026,11,16),
        LocalDate.of(2026,11,17), LocalDate.of(2026,11,18),
        LocalDate.of(2026,11,19), LocalDate.of(2026,11,20)
    };
    private static final Contract CONTRACT = new Contract(
        "NSE_FO|FUT_DEC_MR", "NSE_INDEX|Nifty 50",
        LocalDate.of(2026,11,1), LocalDate.of(2026,12,24), 75, true);
    private static final SourceRights RIGHTS = new SourceRights(true,true,true);
    private static final Thresholds THRESHOLDS =
        new Thresholds(25, 200, 60, 100, 25, 9000, 1500, 1000, 900, 5000);

    private static BigDecimal n(String s) { return new BigDecimal(s); }
    private static Instant close(LocalDate day) {
        LocalTime end = day.isBefore(LocalDate.of(2026, 8, 3))
            ? LocalTime.of(15, 30) : day.isBefore(LocalDate.of(2026, 12, 7))
            ? LocalTime.of(15, 40) : LocalTime.of(15, 45);
        return day.atTime(end).atZone(IST).toInstant();
    }
    private static CompletedSession bar(int i, String price, long volume, long oi) {
        LocalDate day = DATES[i];
        BigDecimal p = n(price);
        return new CompletedSession(day, true, CONTRACT.instrumentKey(), "UPSTOX_ANALYTICS",
            p, p.add(n("0.4")), p.subtract(n("0.4")), p, true,
            close(day).plusSeconds(150), volume, oi, close(day).plusSeconds(900));
    }
    private static List<CompletedSession> baseline() {
        return List.of(bar(0,"100",30000,100000),bar(1,"101",30000,100000),
            bar(2,"99",30000,100000),bar(3,"100",30000,100000),
            bar(4,"100.5",30000,100000),bar(5,"99.5",30000,100000),
            bar(6,"101",30000,100000),bar(7,"99",30000,100000));
    }
    private static List<CompletedSession> lowRebound() {
        var bars = new ArrayList<>(baseline());
        bars.add(bar(8,"95",45000,100000));
        bars.add(bar(9,"96.5",50000,100000));
        return bars;
    }
    private static List<CompletedSession> highPullback() {
        var bars = new ArrayList<>(baseline());
        bars.add(bar(8,"105",45000,100000));
        bars.add(bar(9,"103.5",50000,100000));
        return bars;
    }
    private static List<CompletedSession> replace(List<CompletedSession> bars,int index,
                                                   CompletedSession item) {
        var copy = new ArrayList<>(bars);
        copy.set(index,item);
        return copy;
    }
    private static Decision evaluate(Contract c, List<CompletedSession> bars,
                                     SourceRights rights, Thresholds t, Instant asOf) {
        return FnoFuturesMeanReversionV1.evaluate(c,bars,rights,t,asOf);
    }
    private static Decision evaluate(List<CompletedSession> bars) {
        return evaluate(CONTRACT,bars,RIGHTS,THRESHOLDS,AS_OF);
    }

    @Test
    void retainedNseResearchUsesSameSignalAndHonorsObservationCutoff() {
        var broker = lowRebound();
        var archive = broker.stream().map(bar -> new CompletedSession(
            bar.sessionDate(), true, bar.instrumentKey(), "NSE_RETAINED_EOD",
            bar.open(), bar.high(), bar.low(), bar.close(), true, AS_OF,
            bar.volumeUnits(), bar.openInterestUnits(), AS_OF)).toList();
        var research = FnoFuturesMeanReversionV1.evaluateRetainedNseResearch(
            CONTRACT, archive, THRESHOLDS, AS_OF);
        assertEquals(Status.RESEARCH_CANDIDATE, research.status());
        assertEquals(evaluate(broker).pattern(), research.pattern());
        assertEquals(evaluate(broker).reasonCode(), research.reasonCode());
        assertFalse(research.executable());
        assertEquals(Status.BLOCKED_DATA, evaluate(archive).status());
        assertEquals(Status.BLOCKED_DATA, FnoFuturesMeanReversionV1.evaluateRetainedNseResearch(
            CONTRACT, archive, THRESHOLDS, AS_OF.minusSeconds(1)).status());
    }

    @Test
    void prematurelyClaimedFinalOHLCVAt1535MustNotPassNseDerivativesClose() {
        var rows = lowRebound();
        var last = rows.get(9);
        var invalid = new CompletedSession(last.sessionDate(), true, last.instrumentKey(),
            last.provider(), last.open(), last.high(), last.low(), last.close(), true,
            close(last.sessionDate()).minusSeconds(300), last.volumeUnits(),
            last.openInterestUnits(), last.oiFirstObservedAt());
        assertEquals("FUT_MR_SESSION_INVALID",
            evaluate(replace(rows, 9, invalid)).reasonCode());
    }

    @Test
    void lowPriceExtremeAndReboundIsNonExecutableResearchCandidate() {
        Decision d = evaluate(lowRebound());
        assertEquals(Status.RESEARCH_CANDIDATE,d.status());
        assertEquals(Pattern.LOW_EXTREME_REBOUND,d.pattern());
        assertEquals("FUT_MR_LOW_EXTREME_REBOUND_OBSERVED",d.reasonCode());
        assertEquals("strategy.STR-177",d.strategyId());
        assertEquals(CONTRACT.instrumentKey(),d.instrumentKey());
        assertEquals("UPSTOX_ANALYTICS",d.provider());
        assertEquals(10,d.completedSessions());
        assertEquals(0,d.baselineMean().compareTo(n("100.0000")));
        assertTrue(d.extremeZScore().compareTo(n("-2.00")) < 0);
        assertTrue(d.reversalFromExtremeBps().compareTo(n("60")) > 0);
        assertTrue(d.costAdjustedRemainingBps().compareTo(n("100")) > 0);
        assertFalse(d.executable());
        assertFalse(d.orderPlaced());
        assertFalse(d.liveTradeAuthorized());
    }

    @Test
    void highPriceExtremeThenPullbackIsResearchOnly() {
        Decision d = evaluate(highPullback());
        assertEquals(Status.RESEARCH_CANDIDATE,d.status());
        assertEquals(Pattern.HIGH_EXTREME_PULLBACK,d.pattern());
        assertEquals("FUT_MR_HIGH_EXTREME_PULLBACK_OBSERVED",d.reasonCode());
        assertTrue(d.extremeZScore().compareTo(n("2.00")) > 0);
        assertFalse(d.liveTradeAuthorized());
    }

    @Test
    void noExtremeMeansNoReversionSignal() {
        var bars=replace(lowRebound(),8,bar(8,"99",45000,100000));
        bars=replace(bars,9,bar(9,"99.2",50000,100000));
        Decision d=evaluate(bars);
        assertEquals(Status.NO_SIGNAL,d.status());
        assertEquals("FUT_MR_EXTREME_NOT_CONFIRMED",d.reasonCode());
    }

    @Test
    void extremeWithoutRecoveryDoesNotPredictARebound() {
        Decision d=evaluate(replace(lowRebound(),9,bar(9,"94",50000,100000)));
        assertEquals(Status.NO_SIGNAL,d.status());
        assertEquals("FUT_MR_RECOVERY_NOT_CONFIRMED",d.reasonCode());
    }

    @Test
    void priceAlreadyCrossedBaselineMeanIsNotQualified() {
        Decision d=evaluate(replace(lowRebound(),9,bar(9,"101",50000,100000)));
        assertEquals(Status.NO_SIGNAL,d.status());
        assertEquals("FUT_MR_RECOVERY_NOT_CONFIRMED",d.reasonCode());
    }

    @Test
    void reboundTooSmallForExplicitThresholdCannotQualify() {
        var threshold=new Thresholds(25,200,500,100,25,9000,1500,1000,900,5000);
        Decision d=evaluate(CONTRACT,lowRebound(),RIGHTS,threshold,AS_OF);
        assertEquals(Status.NO_SIGNAL,d.status());
        assertEquals("FUT_MR_REVERSAL_OR_COST_HURDLE_NOT_MET",d.reasonCode());
    }

    @Test
    void highEstimatedCostsCanEliminateRemainingGap() {
        var expensive=new Thresholds(25,200,60,100,500,9000,1500,1000,900,5000);
        Decision d=evaluate(CONTRACT,lowRebound(),RIGHTS,expensive,AS_OF);
        assertEquals(Status.NO_SIGNAL,d.status());
        assertEquals("FUT_MR_REVERSAL_OR_COST_HURDLE_NOT_MET",d.reasonCode());
    }

    @Test
    void weakLatestVolumeVersusBaselineBlocksSignal() {
        Decision d=evaluate(replace(lowRebound(),9,bar(9,"96.5",100,100000)));
        assertEquals(Status.NO_SIGNAL,d.status());
        assertEquals("FUT_MR_VOLUME_OR_OI_LOW",d.reasonCode());
    }

    @Test
    void weakLatestVolumeToOiBlocksSignalEvenWithHighAbsoluteVolume() {
        // Keep OI unit normalization consistent across all ten sessions so this
        // reaches the liquidity gate rather than correctly failing as an OI spike.
        var highOi=new ArrayList<CompletedSession>();
        for(var bar:lowRebound()) {
            highOi.add(new CompletedSession(bar.sessionDate(),bar.sessionVerified(),
                bar.instrumentKey(),bar.provider(),bar.open(),bar.high(),bar.low(),
                bar.close(),bar.ohlcvVerified(),bar.ohlcvFirstObservedAt(),
                bar.volumeUnits(),100000000L,bar.oiFirstObservedAt()));
        }
        Decision d=evaluate(highOi);
        assertEquals(Status.NO_SIGNAL,d.status());
        assertEquals("FUT_MR_VOLUME_OR_OI_LOW",d.reasonCode());
    }

    @Test
    void constantEightSessionBaselineCannotBeUsedForZScore() {
        var bars=new ArrayList<CompletedSession>();
        for(int i=0;i<8;i++) bars.add(bar(i,"100",30000,100000));
        bars.add(bar(8,"95",45000,100000));
        bars.add(bar(9,"96.5",50000,100000));
        Decision d=evaluate(bars);
        assertEquals(Status.NO_SIGNAL,d.status());
        assertEquals("FUT_MR_BASELINE_VARIANCE_TOO_LOW",d.reasonCode());
        assertNull(d.extremeZScore());
    }

    @Test
    void eachIndependentDataUseRightIsMandatory() {
        for(var rights:List.of(
            new SourceRights(false,true,true),
            new SourceRights(true,false,true),
            new SourceRights(true,true,false))) {
            Decision d=evaluate(CONTRACT,lowRebound(),rights,THRESHOLDS,AS_OF);
            assertEquals(Status.BLOCKED_RIGHTS,d.status());
            assertEquals("FUT_MR_SOURCE_RIGHTS_NOT_VERIFIED",d.reasonCode());
            assertNull(d.instrumentKey());
        }
    }

    @Test
    void unlistedWrongExchangeNearExpiryAndUnverifiedContractBlock() {
        for(var invalid:List.of(
            new Contract("NSE_EQ|NOT_FUT",CONTRACT.underlyingKey(),CONTRACT.listedFrom(),
                CONTRACT.expiry(),75,true),
            new Contract(CONTRACT.instrumentKey(),CONTRACT.underlyingKey(),
                CONTRACT.listedFrom(),LocalDate.of(2026,11,24),75,true),
            new Contract(CONTRACT.instrumentKey(),CONTRACT.underlyingKey(),
                CONTRACT.listedFrom(),CONTRACT.expiry(),0,true),
            new Contract(CONTRACT.instrumentKey(),CONTRACT.underlyingKey(),
                CONTRACT.listedFrom(),CONTRACT.expiry(),75,false))) {
            assertEquals(Status.BLOCKED_DATA,
                evaluate(invalid,lowRebound(),RIGHTS,THRESHOLDS,AS_OF).status());
        }
    }

    @Test
    void changingFutureKeyOrProviderDuringHistoryFailsClosed() {
        var bars=lowRebound();
        var last=bars.getLast();
        var nextExpiry=new CompletedSession(last.sessionDate(),true,"NSE_FO|NEXT_MONTH",
            last.provider(),last.open(),last.high(),last.low(),last.close(),true,
            last.ohlcvFirstObservedAt(),last.volumeUnits(),
            last.openInterestUnits(),last.oiFirstObservedAt());
        assertEquals("FUT_MR_SESSION_INVALID",evaluate(replace(bars,9,nextExpiry)).reasonCode());
        var otherSource=new CompletedSession(last.sessionDate(),true,last.instrumentKey(),
            "ANGEL_ONE_SMARTAPI",last.open(),last.high(),last.low(),last.close(),true,
            last.ohlcvFirstObservedAt(),last.volumeUnits(),
            last.openInterestUnits(),last.oiFirstObservedAt());
        assertEquals("FUT_MR_PROVIDER_CHANGED",evaluate(replace(bars,9,otherSource)).reasonCode());
    }

    @Test
    void malformedOhlcAndUnverifiedCloseCannotCreateSignal() {
        var bars=lowRebound();
        var last=bars.getLast();
        var impossible=new CompletedSession(last.sessionDate(),true,last.instrumentKey(),
            last.provider(),last.open(),n("95"),last.low(),last.close(),true,
            last.ohlcvFirstObservedAt(),last.volumeUnits(),
            last.openInterestUnits(),last.oiFirstObservedAt());
        assertEquals(Status.BLOCKED_DATA,evaluate(replace(bars,9,impossible)).status());
        var unverified=new CompletedSession(last.sessionDate(),true,last.instrumentKey(),
            last.provider(),last.open(),last.high(),last.low(),last.close(),false,
            last.ohlcvFirstObservedAt(),last.volumeUnits(),
            last.openInterestUnits(),last.oiFirstObservedAt());
        assertEquals(Status.BLOCKED_DATA,evaluate(replace(bars,9,unverified)).status());
    }

    @Test
    void futureKnownAndPreCloseObservationsDoNotEnterHistoricalReplay() {
        var bars=lowRebound();
        var last=bars.getLast();
        var futureOi=new CompletedSession(last.sessionDate(),true,last.instrumentKey(),
            last.provider(),last.open(),last.high(),last.low(),last.close(),true,
            last.ohlcvFirstObservedAt(),last.volumeUnits(),last.openInterestUnits(),
            AS_OF.plusSeconds(5));
        var preClose=new CompletedSession(last.sessionDate(),true,last.instrumentKey(),
            last.provider(),last.open(),last.high(),last.low(),last.close(),true,
            close(last.sessionDate()).minusSeconds(1),last.volumeUnits(),
            last.openInterestUnits(),last.oiFirstObservedAt());
        assertEquals("FUT_MR_SESSION_INVALID",evaluate(replace(bars,9,futureOi)).reasonCode());
        assertEquals("FUT_MR_SESSION_INVALID",evaluate(replace(bars,9,preClose)).reasonCode());
    }

    @Test
    void excessivePriceJumpDailyRangeAndOiUnitChangeFailClosed() {
        var bars=lowRebound();
        var last=bars.getLast();
        var highRange=new CompletedSession(last.sessionDate(),true,last.instrumentKey(),
            last.provider(),last.open(),n("120"),n("90"),last.close(),true,
            last.ohlcvFirstObservedAt(),last.volumeUnits(),
            last.openInterestUnits(),last.oiFirstObservedAt());
        assertEquals("FUT_MR_INTRADAY_RANGE_OUTLIER",
            evaluate(replace(bars,9,highRange)).reasonCode());
        assertEquals("FUT_MR_DAILY_CHANGE_OUTLIER",
            evaluate(replace(bars,9,bar(9,"110",50000,100000))).reasonCode());
        assertEquals("FUT_MR_DAILY_CHANGE_OUTLIER",
            evaluate(replace(bars,9,bar(9,"96.5",50000,300000))).reasonCode());
    }

    @Test
    void zeroOrNegativeOiAndNegativeVolumeAreRejectedAsInvalid() {
        var bars=lowRebound();
        var last=bars.getLast();
        for(long oi:new long[]{0,-1,1_000_000_000_001L}) {
            var invalid=new CompletedSession(last.sessionDate(),true,last.instrumentKey(),
                last.provider(),last.open(),last.high(),last.low(),last.close(),true,
                last.ohlcvFirstObservedAt(),last.volumeUnits(),oi,last.oiFirstObservedAt());
            assertEquals(Status.BLOCKED_DATA,evaluate(replace(bars,9,invalid)).status());
        }
        assertEquals(Status.BLOCKED_DATA,evaluate(replace(bars,9,bar(9,"96.5",-1,100000))).status());
    }

    @Test
    void duplicateOutOfOrderAndMissingSessionsCannotCreateResearchCandidate() {
        var rows=lowRebound();
        assertEquals(Status.BLOCKED_DATA,evaluate(rows.subList(0,9)).status());
        assertEquals("FUT_MR_SESSION_SEQUENCE_INVALID",evaluate(List.of(
            rows.get(0),rows.get(1),rows.get(2),rows.get(3),rows.get(4),
            rows.get(5),rows.get(6),rows.get(7),rows.get(8),rows.get(8))).reasonCode());
        var outOfOrder=new ArrayList<>(rows);
        outOfOrder.set(8,rows.get(9));
        outOfOrder.set(9,rows.get(8));
        assertEquals(Status.BLOCKED_DATA,evaluate(outOfOrder).status());
    }

    @Test
    void staleLastBarAndListingAfterFirstBarAreRejected() {
        assertEquals("FUT_MR_LAST_SESSION_STALE",
            evaluate(CONTRACT,lowRebound(),RIGHTS,THRESHOLDS,
                AS_OF.plusSeconds(8*24*3600L)).reasonCode());
        var listedLate=new Contract(CONTRACT.instrumentKey(),CONTRACT.underlyingKey(),
            LocalDate.of(2026,11,12),CONTRACT.expiry(),75,true);
        assertEquals("FUT_MR_SESSION_INVALID",
            evaluate(listedLate,lowRebound(),RIGHTS,THRESHOLDS,AS_OF).reasonCode());
    }

    // ---- Open-interest change integrity: distinguish a genuine exchange-reported
    // ---- change (NSE ChngInOpnIntrst) from corrupt data. ----

    /** Archive-style NSE session carrying the exchange-reported per-session OI change. */
    private static CompletedSession archiveBar(int i, String price, long volume, long oi,
                                               Long reportedOiChange) {
        LocalDate day = DATES[i];
        BigDecimal p = n(price);
        return new CompletedSession(day, true, CONTRACT.instrumentKey(), "NSE_RETAINED_EOD",
            p, p.add(n("0.4")), p.subtract(n("0.4")), p, true,
            AS_OF, volume, oi, AS_OF, reportedOiChange);
    }
    // Baseline/extreme/latest closes reproducing a clean low-extreme rebound,
    // with a large open-interest jump across the 18->19 Nov step (indices 7->8).
    private static final String[] ARCHIVE_CLOSES =
        {"100","101","99","100","100.5","99.5","101","99","95","96.5"};
    private static final long[] ARCHIVE_VOL =
        {30000,30000,30000,30000,30000,30000,30000,30000,45000,50000};
    // OI flat at 100000, then jumps to 160000 from the 19 Nov session onward
    // (+60000 == +6000bps, above the 5000bps guard) and holds.
    private static final long[] ARCHIVE_OI =
        {100000,100000,100000,100000,100000,100000,100000,100000,160000,160000};
    private static List<CompletedSession> archiveWindow(java.util.function.IntUnaryOperator reportedAt) {
        var bars = new ArrayList<CompletedSession>();
        for (int i = 0; i < 10; i++) {
            Long reported = i == 0 ? null : Long.valueOf(reportedAt.applyAsInt(i));
            bars.add(archiveBar(i, ARCHIVE_CLOSES[i], ARCHIVE_VOL[i], ARCHIVE_OI[i], reported));
        }
        return bars;
    }
    private static int trueOiDelta(int i) {
        return (int) (ARCHIVE_OI[i] - ARCHIVE_OI[i - 1]);
    }

    @Test
    void exchangeReconciledLargeOiChangeIsNotADataOutlier() {
        // The +60000 OI jump across 18->19 Nov reconciles exactly with the
        // exchange-reported ChngInOpnIntrst, so it is authentic data: the guard is
        // satisfied and the unchanged mean-reversion logic proceeds to a verdict.
        Decision d = FnoFuturesMeanReversionV1.evaluateRetainedNseResearch(
            CONTRACT, archiveWindow(FnoFuturesMeanReversionV1Test::trueOiDelta), THRESHOLDS, AS_OF);
        assertEquals(Status.RESEARCH_CANDIDATE, d.status());
        assertEquals(Pattern.LOW_EXTREME_REBOUND, d.pattern());
        assertEquals("FUT_MR_LOW_EXTREME_REBOUND_OBSERVED", d.reasonCode());
        assertFalse(d.executable());
    }

    @Test
    void exchangeReportedChangeThatContradictsSeriesFailsClosed() {
        // Exchange reports no change (0) while the retained OI series jumped 60000:
        // the two fields are internally inconsistent, so the row is corrupt.
        Decision d = FnoFuturesMeanReversionV1.evaluateRetainedNseResearch(
            CONTRACT, archiveWindow(i -> i == 8 ? 0 : trueOiDelta(i)), THRESHOLDS, AS_OF);
        assertEquals(Status.BLOCKED_DATA, d.status());
        assertEquals("FUT_MR_OI_CHANGE_CONTRADICTS_EXCHANGE", d.reasonCode());
    }

    @Test
    void largeOiChangeWithoutAnyExchangeReconciliationStillBlocks() {
        // No independent exchange change field (null, e.g. broker-analytics source):
        // the magnitude guard applies exactly as before.
        Decision d = FnoFuturesMeanReversionV1.evaluateRetainedNseResearch(
            CONTRACT, archiveWindow(i -> 0 /*unused*/).stream()
                .map(b -> new CompletedSession(b.sessionDate(), true, b.instrumentKey(),
                    b.provider(), b.open(), b.high(), b.low(), b.close(), true,
                    b.ohlcvFirstObservedAt(), b.volumeUnits(), b.openInterestUnits(),
                    b.oiFirstObservedAt())) // 13-arg ctor => null reported change
                .toList(),
            THRESHOLDS, AS_OF);
        assertEquals(Status.BLOCKED_DATA, d.status());
        assertEquals("FUT_MR_DAILY_CHANGE_OUTLIER", d.reasonCode());
    }

    @Test
    void reconciliationIsEnforcedEvenForSmallOiChanges() {
        // A below-threshold OI change still must agree with the exchange-reported
        // value when that value is present; a mismatch is a data defect.
        // Flat OI everywhere (delta 0) but claim the exchange reported +5 on day 1.
        var flat = new ArrayList<CompletedSession>();
        for (int i = 0; i < 10; i++) {
            Long reported = i == 1 ? Long.valueOf(5) : (i == 0 ? null : Long.valueOf(0));
            flat.add(archiveBar(i, ARCHIVE_CLOSES[i], ARCHIVE_VOL[i], 100000L, reported));
        }
        Decision d = FnoFuturesMeanReversionV1.evaluateRetainedNseResearch(
            CONTRACT, flat, THRESHOLDS, AS_OF);
        assertEquals(Status.BLOCKED_DATA, d.status());
        assertEquals("FUT_MR_OI_CHANGE_CONTRADICTS_EXCHANGE", d.reasonCode());
    }

    @Test
    void backwardCompatibleConstructorLeavesExchangeChangeUnset() {
        CompletedSession legacy = bar(0, "100", 30000, 100000);
        assertNull(legacy.exchangeReportedOiChangeUnits());
        CompletedSession full = archiveBar(0, "100", 30000, 100000, Long.valueOf(-42));
        assertEquals(Long.valueOf(-42), full.exchangeReportedOiChangeUnits());
    }

    @Test
    void reconciledExchangeChangeDoesNotBypassPriceOrRangeIntegrity() {
        // Even with a reconciled OI change, an impossible intraday close jump is
        // still rejected: the OI rule never relaxes price-data integrity.
        var window = new ArrayList<>(archiveWindow(FnoFuturesMeanReversionV1Test::trueOiDelta));
        var jumped = archiveBar(9, "140", 50000, 160000, Long.valueOf(0));
        window.set(9, jumped);
        Decision d = FnoFuturesMeanReversionV1.evaluateRetainedNseResearch(
            CONTRACT, window, THRESHOLDS, AS_OF);
        assertEquals(Status.BLOCKED_DATA, d.status());
        assertEquals("FUT_MR_DAILY_CHANGE_OUTLIER", d.reasonCode());
    }

    @Test
    void explicitThresholdValidationAndDeterministicEvaluation() {
        assertEquals(Status.BLOCKED_DATA,
            evaluate(CONTRACT,lowRebound(),RIGHTS,null,AS_OF).status());
        assertEquals(Status.BLOCKED_DATA,
            evaluate(CONTRACT,lowRebound(),RIGHTS,
                new Thresholds(0,200,60,100,25,9000,1500,1000,900,5000),AS_OF).status());
        assertEquals(Status.BLOCKED_DATA,
            evaluate(CONTRACT,lowRebound(),RIGHTS,
                new Thresholds(25,200,60,100,0,9000,1500,1000,900,5000),AS_OF).status());
        assertEquals(evaluate(lowRebound()),evaluate(lowRebound()));
        assertFalse(evaluate(highPullback()).orderPlaced());
    }
}
