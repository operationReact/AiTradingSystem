package in.aitradingsystem.tradingrooms;

import static org.junit.jupiter.api.Assertions.*;

import in.aitradingsystem.tradingrooms.FnoFuturesMomentumV1.CompletedSession;
import in.aitradingsystem.tradingrooms.FnoFuturesMomentumV1.Contract;
import in.aitradingsystem.tradingrooms.FnoFuturesMomentumV1.Decision;
import in.aitradingsystem.tradingrooms.FnoFuturesMomentumV1.Momentum;
import in.aitradingsystem.tradingrooms.FnoFuturesMomentumV1.SourceRights;
import in.aitradingsystem.tradingrooms.FnoFuturesMomentumV1.Status;
import in.aitradingsystem.tradingrooms.FnoFuturesMomentumV1.Thresholds;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FnoFuturesMomentumV1Test {
    private static final Instant AS_OF = Instant.parse("2026-11-20T11:00:00Z");
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate[] DATES = {
        LocalDate.of(2026, 11, 11), LocalDate.of(2026, 11, 12),
        LocalDate.of(2026, 11, 13), LocalDate.of(2026, 11, 16),
        LocalDate.of(2026, 11, 17), LocalDate.of(2026, 11, 18),
        LocalDate.of(2026, 11, 19)
    };
    private static final Contract CONTRACT = new Contract(
        "NSE_FO|FUT_DEC_176", "NSE_INDEX|Nifty 50",
        LocalDate.of(2026, 11, 1), LocalDate.of(2026, 12, 24), 75, true);
    private static final SourceRights RIGHTS = new SourceRights(true, true, true);
    private static final Thresholds THRESHOLDS =
        new Thresholds(100, 2, 11000, 2000, 2500, 800, 20, 30);

    private static BigDecimal n(String value) { return new BigDecimal(value); }
    private static Instant close(LocalDate d) {
        LocalTime end = d.isBefore(LocalDate.of(2026, 8, 3))
            ? LocalTime.of(15, 30) : d.isBefore(LocalDate.of(2026, 12, 7))
            ? LocalTime.of(15, 40) : LocalTime.of(15, 45);
        return d.atTime(end).atZone(IST).toInstant();
    }
    private static CompletedSession bar(int i, String p, long volume, long oi) {
        LocalDate day = DATES[i];
        BigDecimal price = n(p);
        return new CompletedSession(day, true, CONTRACT.instrumentKey(),
            "UPSTOX_ANALYTICS", price.subtract(n("1")),
            price.add(n("0.5")), price.subtract(n("1.5")),
            price, true, close(day).plusSeconds(150), volume, oi,
            close(day).plusSeconds(900));
    }
    private static List<CompletedSession> up() {
        return List.of(bar(0,"100",30000,100000), bar(1,"101",30000,100000),
            bar(2,"102",30000,100000), bar(3,"103",30000,100000),
            bar(4,"104",50000,100000), bar(5,"105",50000,100000),
            bar(6,"111",50000,100000));
    }
    private static List<CompletedSession> down() {
        return List.of(bar(0,"111",30000,100000), bar(1,"110",30000,100000),
            bar(2,"109",30000,100000), bar(3,"108",30000,100000),
            bar(4,"107",50000,100000), bar(5,"106",50000,100000),
            bar(6,"99",50000,100000));
    }
    private static Decision run(Contract c, List<CompletedSession> bars,
                                SourceRights r, Thresholds t) {
        return FnoFuturesMomentumV1.evaluate(c, bars, r, t, AS_OF);
    }
    private static Decision run(List<CompletedSession> bars) {
        return run(CONTRACT, bars, RIGHTS, THRESHOLDS);
    }
    private static List<CompletedSession> replace(List<CompletedSession> bars,
                                                  int index, CompletedSession newBar) {
        var copied = new ArrayList<>(bars);
        copied.set(index, newBar);
        return copied;
    }

    @Test
    void exchangeDerivativesCloseGuardRejectsPrematureFinalBarAt1530() {
        var data = up();
        var latest = data.get(6);
        var premature = new CompletedSession(latest.exchangeDate(), true,
            latest.instrumentKey(), latest.provider(), latest.open(), latest.high(),
            latest.low(), latest.close(), true,
            close(latest.exchangeDate()).minusSeconds(300),
            latest.tradedVolumeUnits(), latest.openInterestUnits(),
            latest.oiFirstObservedAt());
        assertEquals("FUT_MOM_COMPLETED_BAR_INVALID",
            run(replace(data, 6, premature)).reasonCode());
    }

    @Test
    void exactBudgetSundayRequiresVerifiedSessionAndGenuineAvailability() {
        var days = List.of(LocalDate.of(2026,1,27),LocalDate.of(2026,1,28),
            LocalDate.of(2026,1,29),LocalDate.of(2026,1,30),
            LocalDate.of(2026,2,1),LocalDate.of(2026,2,2),LocalDate.of(2026,2,3));
        var contract = new Contract(CONTRACT.instrumentKey(), CONTRACT.underlyingKey(),
            LocalDate.of(2026,1,1),LocalDate.of(2026,2,24),75,true);
        var asOf = LocalDate.of(2026,2,4).atTime(9,0).atZone(IST).toInstant();
        var bars = new ArrayList<CompletedSession>();
        for (int i=0;i<days.size();i++) {
            var sample=up().get(i);
            var day=days.get(i);
            bars.add(new CompletedSession(day,true,contract.instrumentKey(),
                sample.provider(),sample.open(),sample.high(),sample.low(),
                sample.close(),true,close(day).plusSeconds(150),
                sample.tradedVolumeUnits(),sample.openInterestUnits(),
                close(day).plusSeconds(900)));
        }
        var result=FnoFuturesMomentumV1.evaluate(contract,bars,RIGHTS,THRESHOLDS,asOf);
        assertEquals(Status.RESEARCH_CANDIDATE,result.status());
        assertFalse(result.executable());
        assertFalse(result.orderPlaced());
        var special=bars.get(4);
        for (var badDay : List.of(LocalDate.of(2026,1,31),special.exchangeDate())) {
            var invalid=new CompletedSession(badDay,!badDay.equals(special.exchangeDate()),
                special.instrumentKey(),special.provider(),special.open(),special.high(),
                special.low(),special.close(),true,close(badDay).plusSeconds(150),
                special.tradedVolumeUnits(),special.openInterestUnits(),
                close(badDay).plusSeconds(900));
            assertEquals("FUT_MOM_COMPLETED_BAR_INVALID",
                FnoFuturesMomentumV1.evaluate(contract,replace(bars,4,invalid),
                    RIGHTS,THRESHOLDS,asOf).reasonCode());
        }
        var premature=new CompletedSession(special.exchangeDate(),true,
            special.instrumentKey(),special.provider(),special.open(),special.high(),
            special.low(),special.close(),true,close(special.exchangeDate()).minusSeconds(1),
            special.tradedVolumeUnits(),special.openInterestUnits(),special.oiFirstObservedAt());
        assertEquals("FUT_MOM_COMPLETED_BAR_INVALID",
            FnoFuturesMomentumV1.evaluate(contract,replace(bars,4,premature),
                RIGHTS,THRESHOLDS,asOf).reasonCode());
    }

    @Test
    void upwardBreakoutIsResearchOnlyAndAllRequiredMetricsAreRecorded() {
        Decision d = run(up());
        assertEquals(Status.RESEARCH_CANDIDATE, d.status());
        assertEquals(Momentum.UPWARD_BREAKOUT, d.momentum());
        assertEquals("FUT_MOM_UPWARD_BREAKOUT_OBSERVED", d.reasonCode());
        assertEquals("strategy.STR-176", d.strategyId());
        assertEquals(7, d.completedSessions());
        assertEquals(3, d.risingCloseSteps());
        assertEquals(0, d.fallingCloseSteps());
        assertTrue(d.upwardBreakoutBps().compareTo(n("100")) > 0);
        assertTrue(d.costAdjustedBreakoutBps().compareTo(n("30")) > 0);
        assertTrue(d.recentVolumeVsBaselineBps().compareTo(n("11000")) > 0);
        assertFalse(d.executable());
        assertFalse(d.orderPlaced());
        assertFalse(d.liveTradeAuthorized());
    }

    @Test
    void downwardBreakoutIsObservedButNotAnAutomaticShortOrder() {
        Decision d = run(down());
        assertEquals(Status.RESEARCH_CANDIDATE, d.status());
        assertEquals(Momentum.DOWNWARD_BREAKOUT, d.momentum());
        assertEquals("FUT_MOM_DOWNWARD_BREAKOUT_OBSERVED", d.reasonCode());
        assertEquals(3, d.fallingCloseSteps());
        assertTrue(d.downwardBreakoutBps().compareTo(n("100")) > 0);
        assertFalse(d.executable());
    }

    @Test
    void flatBarsAndNonBreakoutPricesCannotCreateMomentumSignal() {
        var bars = List.of(bar(0,"100",30000,100000), bar(1,"100",30000,100000),
            bar(2,"100",30000,100000), bar(3,"100",30000,100000),
            bar(4,"100",50000,100000), bar(5,"100",50000,100000),
            bar(6,"100",50000,100000));
        assertEquals(Status.NO_SIGNAL, run(bars).status());
        assertEquals("FUT_MOM_BREAKOUT_NOT_CONFIRMED", run(bars).reasonCode());
    }

    @Test
    void oneNeutralCloseStepCanStillConfirmMomentum() {
        var rows = replace(up(), 5, bar(5, "104", 50000, 100000));
        Decision d = run(rows);
        assertEquals(Status.RESEARCH_CANDIDATE, d.status());
        assertEquals(2, d.risingCloseSteps());
        assertEquals(0, d.fallingCloseSteps());
    }

    @Test
    void opposingRecentCloseStepDisqualifiesDespiteBigFinalBreakout() {
        var rows = replace(up(), 5, bar(5, "103", 50000, 100000));
        assertEquals(Status.NO_SIGNAL, run(rows).status());
        assertEquals("FUT_MOM_BREAKOUT_NOT_CONFIRMED", run(rows).reasonCode());
    }

    @Test
    void weakRecentVolumeRejectsOtherwiseValidBreakout() {
        var original = up();
        var weak = List.of(original.get(0), original.get(1), original.get(2),
            original.get(3), bar(4,"104",1000,100000),
            bar(5,"105",1000,100000), bar(6,"111",1000,100000));
        assertEquals("FUT_MOM_VOLUME_OR_OI_PARTICIPATION_LOW", run(weak).reasonCode());
        assertEquals(Status.NO_SIGNAL, run(weak).status());
    }

    @Test
    void highVolumeWithInsufficientFinalVolumeToOiIsNotQualified() {
        var rows = replace(up(),6,bar(6,"111",50000,100000000));
        assertEquals(Status.NO_SIGNAL, run(rows).status());
        assertEquals("FUT_MOM_VOLUME_OR_OI_PARTICIPATION_LOW", run(rows).reasonCode());
    }

    @Test
    void smallBreakoutDisappearsAfterExplicitCostAndResearchHurdle() {
        Thresholds expensive = new Thresholds(100,2,11000,2000,2500,800,500,50);
        Decision d = run(CONTRACT,up(),RIGHTS,expensive);
        assertEquals(Status.NO_SIGNAL,d.status());
        assertEquals("FUT_MOM_COST_ADJUSTED_BREAKOUT_TOO_SMALL",d.reasonCode());
    }

    @Test
    void singleSessionPriceJumpOutlierIsBlocked() {
        Thresholds strict = new Thresholds(100,2,11000,2000,200,800,20,30);
        assertEquals("FUT_MOM_PRICE_STEP_OUTLIER",
            run(CONTRACT,up(),RIGHTS,strict).reasonCode());
    }

    @Test
    void abnormalDailyRangeIsBlockedBeforeBreakoutMath() {
        var bars = up();
        var last = bars.getLast();
        var outlier = new CompletedSession(last.exchangeDate(),true,last.instrumentKey(),
            last.provider(),n("100"),n("130"),n("90"),n("111"),true,
            last.ohlcvFirstObservedAt(),last.tradedVolumeUnits(),
            last.openInterestUnits(),last.oiFirstObservedAt());
        assertEquals("FUT_MOM_INTRADAY_RANGE_OUTLIER",
            run(replace(bars,6,outlier)).reasonCode());
    }

    @Test
    void independentPriceOiAndContractRightsAllFailClosed() {
        for (var rights : List.of(new SourceRights(false,true,true),
                                  new SourceRights(true,false,true),
                                  new SourceRights(true,true,false))) {
            var d = run(CONTRACT,up(),rights,THRESHOLDS);
            assertEquals(Status.BLOCKED_RIGHTS,d.status());
            assertEquals("FUT_MOM_SOURCE_RIGHTS_NOT_VERIFIED",d.reasonCode());
            assertNull(d.instrumentKey());
        }
    }

    @Test
    void nearExpiryBadLotWrongExchangeAndUnverifiedContractBlock() {
        for (Contract c : List.of(
            new Contract("NSE_EQ|BAD",CONTRACT.underlyingKey(),CONTRACT.listedFrom(),
                CONTRACT.expiry(),75,true),
            new Contract(CONTRACT.instrumentKey(),CONTRACT.underlyingKey(),
                CONTRACT.listedFrom(),LocalDate.of(2026,11,24),75,true),
            new Contract(CONTRACT.instrumentKey(),CONTRACT.underlyingKey(),
                CONTRACT.listedFrom(),CONTRACT.expiry(),0,true),
            new Contract(CONTRACT.instrumentKey(),CONTRACT.underlyingKey(),
                CONTRACT.listedFrom(),CONTRACT.expiry(),75,false)
        )) assertEquals(Status.BLOCKED_DATA,
            run(c,up(),RIGHTS,THRESHOLDS).status());
    }

    @Test
    void mixingAnotherExpiryOrBrokerIntoWindowCannotCreateSignal() {
        var bars=up();
        var last=bars.getLast();
        var otherKey = new CompletedSession(last.exchangeDate(),true,"NSE_FO|NEXT",
            last.provider(),last.open(),last.high(),last.low(),last.close(),true,
            last.ohlcvFirstObservedAt(),last.tradedVolumeUnits(),last.openInterestUnits(),
            last.oiFirstObservedAt());
        assertEquals("FUT_MOM_COMPLETED_BAR_INVALID",
            run(replace(bars,6,otherKey)).reasonCode());
        var otherProvider=new CompletedSession(last.exchangeDate(),true,
            last.instrumentKey(),"ANGEL_ONE_SMARTAPI",last.open(),last.high(),
            last.low(),last.close(),true,last.ohlcvFirstObservedAt(),
            last.tradedVolumeUnits(),last.openInterestUnits(),last.oiFirstObservedAt());
        assertEquals("FUT_MOM_PROVIDER_CHANGED",
            run(replace(bars,6,otherProvider)).reasonCode());
    }

    @Test
    void invalidOhlcAndUnverifiedBarCannotEnterScore() {
        var bars=up();
        var last=bars.getLast();
        var invalid=new CompletedSession(last.exchangeDate(),true,
            last.instrumentKey(),last.provider(),last.open(),n("105"),last.low(),
            last.close(),true,last.ohlcvFirstObservedAt(),last.tradedVolumeUnits(),
            last.openInterestUnits(),last.oiFirstObservedAt());
        assertEquals(Status.BLOCKED_DATA,run(replace(bars,6,invalid)).status());
        var unverified=new CompletedSession(last.exchangeDate(),true,
            last.instrumentKey(),last.provider(),last.open(),last.high(),last.low(),
            last.close(),false,last.ohlcvFirstObservedAt(),last.tradedVolumeUnits(),
            last.openInterestUnits(),last.oiFirstObservedAt());
        assertEquals(Status.BLOCKED_DATA,run(replace(bars,6,unverified)).status());
    }

    @Test
    void noLookaheadBeforeExchangeCloseAndLateOiPublicationBlocked() {
        var bars=up();
        var last=bars.getLast();
        var early=new CompletedSession(last.exchangeDate(),true,last.instrumentKey(),
            last.provider(),last.open(),last.high(),last.low(),last.close(),true,
            close(last.exchangeDate()).minusSeconds(1),last.tradedVolumeUnits(),
            last.openInterestUnits(),last.oiFirstObservedAt());
        var future=new CompletedSession(last.exchangeDate(),true,last.instrumentKey(),
            last.provider(),last.open(),last.high(),last.low(),last.close(),true,
            last.ohlcvFirstObservedAt(),last.tradedVolumeUnits(),last.openInterestUnits(),
            AS_OF.plusSeconds(1));
        var old=bars.getFirst();
        var late=new CompletedSession(old.exchangeDate(),true,old.instrumentKey(),
            old.provider(),old.open(),old.high(),old.low(),old.close(),true,
            old.ohlcvFirstObservedAt(),old.tradedVolumeUnits(),old.openInterestUnits(),
            close(old.exchangeDate()).plusSeconds(40*3600));
        for(var value : List.of(replace(bars,6,early),replace(bars,6,future),
                                replace(bars,0,late))) {
            assertEquals("FUT_MOM_COMPLETED_BAR_INVALID",run(value).reasonCode());
        }
    }

    @Test
    void invalidOrMissingOiAndUnlistedHistoricalContractRejected() {
        var bars=up();
        var last=bars.getLast();
        for(long badOi:new long[]{0,-1,1_000_000_000_001L}){
            var bad=new CompletedSession(last.exchangeDate(),true,last.instrumentKey(),
                last.provider(),last.open(),last.high(),last.low(),last.close(),true,
                last.ohlcvFirstObservedAt(),last.tradedVolumeUnits(),badOi,last.oiFirstObservedAt());
            assertEquals(Status.BLOCKED_DATA,run(replace(bars,6,bad)).status());
        }
        var tooNew=new Contract(CONTRACT.instrumentKey(),CONTRACT.underlyingKey(),
            LocalDate.of(2026,11,16),CONTRACT.expiry(),75,true);
        assertEquals("FUT_MOM_COMPLETED_BAR_INVALID",
            run(tooNew,bars,RIGHTS,THRESHOLDS).reasonCode());
    }

    @Test
    void missingUnorderedDuplicatedOrGappedSessionsRejected() {
        var bars=up();
        assertEquals(Status.BLOCKED_DATA,run(bars.subList(0,6)).status());
        assertEquals("FUT_MOM_SESSION_SEQUENCE_INVALID",run(List.of(
            bars.get(0),bars.get(1),bars.get(2),bars.get(3),
            bars.get(4),bars.get(4),bars.get(6))).reasonCode());
        assertEquals(Status.BLOCKED_DATA,run(List.of(
            bars.get(0),bars.get(2),bars.get(1),bars.get(3),
            bars.get(4),bars.get(5),bars.get(6))).status());
    }

    @Test
    void absentOrOutOfBoundsThresholdsRejectedWithoutAssumingDefaults() {
        assertEquals("FUT_MOM_HISTORY_OR_THRESHOLDS_INVALID",
            run(CONTRACT,up(),RIGHTS,null).reasonCode());
        assertEquals(Status.BLOCKED_DATA,run(CONTRACT,up(),RIGHTS,
            new Thresholds(0,2,11000,2000,2500,800,20,30)).status());
        assertEquals(Status.BLOCKED_DATA,run(CONTRACT,up(),RIGHTS,
            new Thresholds(100,4,11000,2000,2500,800,20,30)).status());
        assertEquals(Status.BLOCKED_DATA,run(CONTRACT,up(),RIGHTS,
            new Thresholds(100,2,11000,2000,2500,800,0,30)).status());
    }

    @Test
    void deterministicSameDataSameResultAndNeverOrders() {
        assertEquals(run(up()),run(up()));
        assertFalse(run(down()).orderPlaced());
    }
}
