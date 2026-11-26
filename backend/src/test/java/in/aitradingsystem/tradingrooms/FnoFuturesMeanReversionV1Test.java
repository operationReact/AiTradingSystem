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
