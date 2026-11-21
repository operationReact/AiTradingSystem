package in.aitradingsystem.tradingrooms;

import static org.junit.jupiter.api.Assertions.*;

import in.aitradingsystem.tradingrooms.FnoPutRatioSpreadV1.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FnoPutRatioSpreadV1Test {
    static final Instant NOW=Instant.parse("2026-11-20T05:00:00Z");
    static final Instant TICK=NOW.minusSeconds(40);
    static final String PROVIDER="UPSTOX_ANALYTICS";
    static final String INDEX=FnoLongOptionCrossoverV1.UNDERLYING;
    static final String LOW="NSE_FO|NIFTY_20261127_70_PE";
    static final String HIGH="NSE_FO|NIFTY_20261127_80_PE";
    static final LocalDate EXPIRY=LocalDate.of(2026,11,27);
    static final Contract LONG=new Contract(HIGH,INDEX,"PE",n("80"),n("0.05"),
        LocalDate.of(2026,11,1),EXPIRY,50,true);
    static final Contract SHORT=new Contract(LOW,INDEX,"PE",n("70"),n("0.05"),
        LocalDate.of(2026,11,1),EXPIRY,50,true);
    static final Quote SPOT=new Quote(PROVIDER,INDEX,n("75"),n("75.10"),
        100,100,100000,TICK,TICK.plusSeconds(10));
    static final Quote LONG_QUOTE=new Quote(PROVIDER,HIGH,n("5.90"),n("6.00"),
        10,10,1000,TICK,TICK.plusSeconds(10));
    static final Quote SHORT_QUOTE=new Quote(PROVIDER,LOW,n("2.70"),n("2.75"),
        10,10,1000,TICK,TICK.plusSeconds(10));
    static final Rights RIGHTS=new Rights(true,true,true);
    static final Assumptions A=new Assumptions(50,50,300,2,50,3000,
        n("10"),n("10000"),10000,n("10"),500);
    static BigDecimal n(String v){return new BigDecimal(v);}
    static List<Bar> candles(String last) {
        var dates=new ArrayList<LocalDate>();var day=LocalDate.of(2026,11,19);
        while(dates.size()<35){
            if(day.getDayOfWeek().getValue()<=5)dates.add(0,day);
            day=day.minusDays(1);
        }
        var result=new ArrayList<Bar>();
        for(int i=0;i<dates.size();i++){
            var close=dates.get(i).atTime(LocalTime.of(15,30))
                .atZone(ZoneId.of("Asia/Kolkata")).toInstant();
            result.add(new Bar(dates.get(i),PROVIDER,n(i==34?last:"100"),
                close,close.plusSeconds(60),true));
        }
        return result;
    }
    static Decision eval(Contract l,Contract s,List<Bar> bars,Quote spot,
                         Quote lq,Quote sq,Rights r,Assumptions a){
        return FnoPutRatioSpreadV1.evaluate(l,s,bars,spot,lq,sq,r,a,NOW);
    }
    static Decision run(){return eval(LONG,SHORT,candles("75"),SPOT,LONG_QUOTE,SHORT_QUOTE,RIGHTS,A);}
    static void assertState(Decision d,Status status,String code){
        assertEquals(status,d.status());
        assertEquals(code,d.reasonCode());
        assertEquals("strategy.STR-191",d.strategyId());
        assertFalse(d.unlimitedTheoreticalDownsideLoss());
        assertFalse(d.executable());
        assertFalse(d.orderPlaced());
        assertFalse(d.liveTradeAuthorized());
    }
    @Test void profitablePeakStillCarriesSevereButFiniteDownsideLossAndCannotOrder() {
        var d=run();
        assertState(d,Status.RESEARCH_CANDIDATE,"PUT_RATIO_FINITE_DOWNWARD_TAIL_RESEARCH_ONLY");
        assertEquals(1,d.longLots());
        assertEquals(2,d.shortLots());
        assertEquals(HIGH,d.longPutKey());
        assertEquals(LOW,d.shortPutKey());
        assertTrue(d.expiryPeakProfitPerRatioLotInr().signum()>0);
        assertTrue(d.hypotheticalDownsideStressPnlInr().signum()<0);
        assertTrue(d.theoreticalMaximumExpiryLossInr().signum()>0);
    }
    @Test void noNewBearishCrossoverIsNoSignal() {
        for(var close:List.of("100","125")){
            assertState(eval(LONG,SHORT,candles(close),SPOT,LONG_QUOTE,SHORT_QUOTE,RIGHTS,A),
                Status.NO_SIGNAL,"PUT_RATIO_NEW_BEARISH_CROSSOVER_ABSENT");
        }
    }
    @Test void independentRightsRequiredForEverySource() {
        assertState(eval(LONG,SHORT,candles("75"),SPOT,LONG_QUOTE,SHORT_QUOTE,
            new Rights(true,false,true),A),
            Status.BLOCKED_RIGHTS,"PUT_RATIO_RIGHTS_MISSING");
    }
    @Test void bothLegsMustBeGenuineMatchingPutContracts() {
        for(var c:List.of(
            new Contract(LOW,INDEX,"CE",n("70"),n("0.05"),SHORT.listedFrom(),EXPIRY,50,true),
            new Contract(LOW,INDEX,"PE",n("70"),n("0.05"),SHORT.listedFrom(),EXPIRY,25,true),
            new Contract(LOW,INDEX,"PE",n("70"),n("0.05"),SHORT.listedFrom(),EXPIRY.plusDays(7),50,true),
            new Contract(LOW,INDEX,"PE",n("90"),n("0.05"),SHORT.listedFrom(),EXPIRY,50,true),
            new Contract(LOW,INDEX,"PE",n("70"),n("0.05"),SHORT.listedFrom(),EXPIRY,50,false))) {
            assertState(eval(LONG,c,candles("75"),SPOT,LONG_QUOTE,SHORT_QUOTE,RIGHTS,A),
                Status.BLOCKED_DATA,"PUT_RATIO_CONTRACT_OR_COST_INVALID");
        }
    }
    @Test void shortPutMustHaveDepthForTwoLots() {
        var q=new Quote(PROVIDER,LOW,n("2.70"),n("2.75"),3,10,1000,TICK,TICK.plusSeconds(10));
        assertState(eval(LONG,SHORT,candles("75"),SPOT,LONG_QUOTE,q,RIGHTS,A),
            Status.BLOCKED_DATA,"PUT_RATIO_QUOTES_OR_ASOF_INVALID");
    }
    @Test void thinCrossedAndCrossProviderQuotesFailClosed() {
        for(var q:List.of(
            new Quote(PROVIDER,LOW,n("2.70"),n("2.75"),10,0,1000,TICK,TICK.plusSeconds(10)),
            new Quote(PROVIDER,LOW,n("2.80"),n("2.75"),10,10,1000,TICK,TICK.plusSeconds(10)),
            new Quote("ANGEL_ONE_SMARTAPI",LOW,n("2.70"),n("2.75"),10,10,1000,TICK,TICK.plusSeconds(10)))) {
            assertState(eval(LONG,SHORT,candles("75"),SPOT,LONG_QUOTE,q,RIGHTS,A),
                Status.BLOCKED_DATA,"PUT_RATIO_QUOTES_OR_ASOF_INVALID");
        }
    }
    @Test void pointInTimeQuoteIntegrityBlocksStaleAndForwardKnownData() {
        for(var q:List.of(
            new Quote(PROVIDER,LOW,n("2.70"),n("2.75"),10,10,1000,
                NOW.minusSeconds(200),NOW.minusSeconds(190)),
            new Quote(PROVIDER,LOW,n("2.70"),n("2.75"),10,10,1000,
                TICK,NOW.plusSeconds(1)))) {
            assertState(eval(LONG,SHORT,candles("75"),SPOT,LONG_QUOTE,q,RIGHTS,A),
                Status.BLOCKED_DATA,"PUT_RATIO_QUOTES_OR_ASOF_INVALID");
        }
    }
    @Test void historicalLookaheadDuplicateAndProviderMismatchRejected() {
        var b=candles("75");var last=b.getLast();
        b.set(34,new Bar(last.session(),PROVIDER,last.close(),last.exchangeCloseAt(),NOW.plusSeconds(1),true));
        assertState(eval(LONG,SHORT,b,SPOT,LONG_QUOTE,SHORT_QUOTE,RIGHTS,A),
            Status.BLOCKED_DATA,"PUT_RATIO_HISTORY_INVALID");
        b=candles("75");b.set(34,b.get(33));
        assertState(eval(LONG,SHORT,b,SPOT,LONG_QUOTE,SHORT_QUOTE,RIGHTS,A),
            Status.BLOCKED_DATA,"PUT_RATIO_HISTORY_INVALID");
    }
    @Test void stressedLossAboveResearchBudgetRejectsCandidateNotTheoreticallyLimitsIt() {
        var a=new Assumptions(50,50,300,2,50,3000,n("10"),n("1000"),1000,n("10"),500);
        assertState(eval(LONG,SHORT,candles("75"),SPOT,LONG_QUOTE,SHORT_QUOTE,RIGHTS,a),
            Status.NO_SIGNAL,"PUT_RATIO_MAXIMUM_LOSS_BUDGET_EXCEEDED");
    }
    @Test void stressAboveShortStrikeIsNotAValidDownsideStressScenario() {
        var a=new Assumptions(50,50,300,2,50,3000,n("10"),n("10000"),10000,n("75"),500);
        assertState(eval(LONG,SHORT,candles("75"),SPOT,LONG_QUOTE,SHORT_QUOTE,RIGHTS,a),
            Status.NO_SIGNAL,"PUT_RATIO_DOWNSIDE_STRESS_NOT_BELOW_SHORT_STRIKE");
    }
    @Test void zeroFeesAndInvalidCostsAreDataBlockers() {
        var a=new Assumptions(50,50,300,2,50,3000,n("0"),n("10000"),10000,n("10"),500);
        assertState(eval(LONG,SHORT,candles("75"),SPOT,LONG_QUOTE,SHORT_QUOTE,RIGHTS,a),
            Status.BLOCKED_DATA,"PUT_RATIO_CONTRACT_OR_COST_INVALID");
    }
    @Test void deterministicResearchResultsNeverAuthorizeNakedPutExposure() {
        assertEquals(run(),run());
        assertFalse(run().unlimitedTheoreticalDownsideLoss());
        assertFalse(run().orderPlaced());
    }
}
