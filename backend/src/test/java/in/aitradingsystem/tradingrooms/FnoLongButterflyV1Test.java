package in.aitradingsystem.tradingrooms;

import static org.junit.jupiter.api.Assertions.*;
import in.aitradingsystem.tradingrooms.FnoLongButterflyV1.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FnoLongButterflyV1Test {
    static final Instant NOW=Instant.parse("2026-11-20T05:00:00Z");
    static final Instant TICK=NOW.minusSeconds(40);
    static final String PROVIDER="UPSTOX_ANALYTICS";
    static final String INDEX=FnoLongOptionCrossoverV1.UNDERLYING;
    static final LocalDate EXPIRY=LocalDate.of(2026,11,27);
    static final Contract LOWER=contract("NSE_FO|NIFTY_90_CE","CE","90");
    static final Contract CENTER=contract("NSE_FO|NIFTY_100_CE","CE","100");
    static final Contract UPPER=contract("NSE_FO|NIFTY_110_CE","CE","110");
    static final Quote SPOT=quote(INDEX,"100","100.05",null,100,100,100000);
    static final Quote LOWER_QUOTE=quote(LOWER.key(),"9.90","10","5",10,10,1000);
    static final Quote CENTER_QUOTE=quote(CENTER.key(),"6","6.1","5",10,10,1000);
    static final Quote UPPER_QUOTE=quote(UPPER.key(),"3.95","4","5",10,10,1000);
    static final Rights RIGHTS=new Rights(true,true,true,true);
    static final Assumptions ASSUMPTIONS=new Assumptions(50,50,300,2,50,
        10000,500,300,n("20"),n("10000"),1000,1000,8000);
    static BigDecimal n(String v){return new BigDecimal(v);}
    static Contract contract(String key,String type,String strike){
        return new Contract(key,INDEX,type,n(strike),n("0.05"),
            LocalDate.of(2026,11,1),EXPIRY,50,true);
    }
    static Quote quote(String key,String bid,String ask,String iv,
                       long bd,long ad,long oi){
        return new Quote(PROVIDER,key,n(bid),n(ask),iv==null?null:n(iv),
            bd,ad,oi,TICK,TICK.plusSeconds(10));
    }
    static List<CompletedClose> history(){
        var days=new ArrayList<LocalDate>();
        var d=LocalDate.of(2026,11,19);
        while(days.size()<35){
            if(d.getDayOfWeek().getValue()<=5)days.add(0,d);
            d=d.minusDays(1);
        }
        var ret=new ArrayList<CompletedClose>();
        for(var day:days){
            var close=day.atTime(LocalTime.of(15,30))
                .atZone(ZoneId.of("Asia/Kolkata")).toInstant();
            ret.add(new CompletedClose(day,PROVIDER,n("100"),close,close.plusSeconds(60),true));
        }
        return ret;
    }
    static Decision eval(Contract low,Contract mid,Contract high,
                         List<CompletedClose> bars,Quote spot,
                         Quote lowQ,Quote midQ,Quote highQ,Rights rights,Assumptions a){
        return FnoLongButterflyV1.evaluate(low,mid,high,bars,spot,lowQ,midQ,highQ,
            rights,a,NOW);
    }
    static Decision run(){
        return eval(LOWER,CENTER,UPPER,history(),SPOT,
            LOWER_QUOTE,CENTER_QUOTE,UPPER_QUOTE,RIGHTS,ASSUMPTIONS);
    }
    static void state(Decision d,Status status,String reason){
        assertEquals(status,d.status());assertEquals(reason,d.reasonCode());
        assertEquals("strategy.STR-198",d.strategyId());
        assertTrue(d.expiryRiskBoundedOnlyWhenAllLegsIntact());
        assertFalse(d.executable());assertFalse(d.orderPlaced());
        assertFalse(d.liveTradeAuthorized());
    }

    @Test void authenticOneBuyTwoSellOneBuyProducesFiniteResearchCandidate(){
        var d=run();
        state(d,Status.RESEARCH_CANDIDATE,"LONG_BUTTERFLY_RESEARCH_ONLY");
        assertEquals(LOWER.key(),d.longLowerCallKey());
        assertEquals(CENTER.key(),d.twoShortCenterCallsKey());
        assertEquals(UPPER.key(),d.longUpperCallKey());
        assertEquals(0,d.totalDebitWithFeesInr().compareTo(n("126.5000")));
        assertEquals(d.totalDebitWithFeesInr(),d.theoreticalMaximumExpiryLossInr());
        assertEquals(0,d.theoreticalMaximumExpiryProfitInr().compareTo(n("373.5000")));
        assertEquals(0,d.totalDebitWithFeesInr()
            .add(d.theoreticalMaximumExpiryProfitInr()).compareTo(n("500.0000")));
        assertTrue(d.lowerExpiryBreakeven().compareTo(n("90"))>0);
        assertTrue(d.upperExpiryBreakeven().compareTo(n("110"))<0);
    }

    @Test void allIndependentResearchRightsRequired(){
        for(var rights:List.of(new Rights(false,true,true,true),
                new Rights(true,false,true,true),new Rights(true,true,false,true),
                new Rights(true,true,true,false))){
            state(eval(LOWER,CENTER,UPPER,history(),SPOT,
                LOWER_QUOTE,CENTER_QUOTE,UPPER_QUOTE,rights,ASSUMPTIONS),
                Status.BLOCKED_RIGHTS,"LONG_BUTTERFLY_RIGHTS_UNVERIFIED");
        }
    }

    @Test void exactlyThreeDistinctCallsSameExpiryAndLotSymmetricWings(){
        for(var bad:List.of(
                contract(UPPER.key(),"PE","110"),
                contract(UPPER.key(),"CE","115"),
                contract(UPPER.key(),"CE","100"),
                new Contract(UPPER.key(),INDEX,"CE",n("110"),n("0.05"),
                    UPPER.listedFrom(),EXPIRY,25,true),
                new Contract(UPPER.key(),INDEX,"CE",n("110"),n("0.05"),
                    UPPER.listedFrom(),EXPIRY.plusDays(7),50,true),
                new Contract(UPPER.key(),INDEX,"CE",n("110"),n("0.05"),
                    UPPER.listedFrom(),EXPIRY,50,false))){
            state(eval(LOWER,CENTER,bad,history(),SPOT,
                LOWER_QUOTE,CENTER_QUOTE,UPPER_QUOTE,RIGHTS,ASSUMPTIONS),
                Status.BLOCKED_DATA,"LONG_BUTTERFLY_CONTRACT_OR_COST_INVALID");
        }
        state(eval(LOWER,CENTER,CENTER,history(),SPOT,
            LOWER_QUOTE,CENTER_QUOTE,UPPER_QUOTE,RIGHTS,ASSUMPTIONS),
            Status.BLOCKED_DATA,"LONG_BUTTERFLY_CONTRACT_OR_COST_INVALID");
    }

    @Test void historicalBarsRequireGenuineAscendingCompletedFirstKnownObservations(){
        var h=history();var last=h.getLast();
        h.set(34,new CompletedClose(last.session(),PROVIDER,last.close(),
            last.exchangeCloseAt(),NOW.plusSeconds(1),true));
        state(eval(LOWER,CENTER,UPPER,h,SPOT,
            LOWER_QUOTE,CENTER_QUOTE,UPPER_QUOTE,RIGHTS,ASSUMPTIONS),
            Status.BLOCKED_DATA,"LONG_BUTTERFLY_HISTORY_INVALID");
        h=history();h.set(34,h.get(33));
        state(eval(LOWER,CENTER,UPPER,h,SPOT,
            LOWER_QUOTE,CENTER_QUOTE,UPPER_QUOTE,RIGHTS,ASSUMPTIONS),
            Status.BLOCKED_DATA,"LONG_BUTTERFLY_HISTORY_INVALID");
    }

    @Test void callCenterMustBeNearActualSpotAndWingsMustSurroundIndex(){
        for(var value:List.of("89","111")){
            var spot=quote(INDEX,value,"112",null,100,100,100000);
            state(eval(LOWER,CENTER,UPPER,history(),spot,
                LOWER_QUOTE,CENTER_QUOTE,UPPER_QUOTE,RIGHTS,ASSUMPTIONS),
                Status.BLOCKED_DATA,"LONG_BUTTERFLY_QUOTES_OR_IV_INVALID");
        }
        var offAtm=quote(INDEX,"106","106.05",null,100,100,100000);
        state(eval(LOWER,CENTER,UPPER,history(),offAtm,
            LOWER_QUOTE,CENTER_QUOTE,UPPER_QUOTE,RIGHTS,ASSUMPTIONS),
            Status.BLOCKED_DATA,"LONG_BUTTERFLY_QUOTES_OR_IV_INVALID");
    }

    @Test void originalSourceQuotesAndAnnualizedIvMustBeRealAndLiquid(){
        for(var bad:List.of(
            quote(CENTER.key(),"6","6.1",null,10,10,1000),
            quote(CENTER.key(),"6","6.1","0",10,10,1000),
            quote(CENTER.key(),"6","6.1","501",10,10,1000),
            quote(CENTER.key(),"6.5","6.1","5",10,10,1000),
            quote(CENTER.key(),"6","6.1","5",3,10,1000),
            quote(CENTER.key(),"6","6.1","5",10,10,0),
            new Quote("ANGEL_ONE_SMARTAPI",CENTER.key(),n("6"),n("6.1"),
                n("5"),10,10,1000,TICK,TICK.plusSeconds(10)))){
            state(eval(LOWER,CENTER,UPPER,history(),SPOT,
                LOWER_QUOTE,bad,UPPER_QUOTE,RIGHTS,ASSUMPTIONS),
                Status.BLOCKED_DATA,"LONG_BUTTERFLY_QUOTES_OR_IV_INVALID");
        }
        var noAskDepth=quote(UPPER.key(),"3.95","4","5",10,0,1000);
        state(eval(LOWER,CENTER,UPPER,history(),SPOT,
            LOWER_QUOTE,CENTER_QUOTE,noAskDepth,RIGHTS,ASSUMPTIONS),
            Status.BLOCKED_DATA,"LONG_BUTTERFLY_QUOTES_OR_IV_INVALID");
    }

    @Test void staleQuoteFutureFirstSeenAndCrossLegTimeSkewFailClosed(){
        for(var bad:List.of(
            new Quote(PROVIDER,LOWER.key(),n("9.9"),n("10"),n("5"),
                10,10,1000,NOW.minusSeconds(220),NOW.minusSeconds(210)),
            new Quote(PROVIDER,LOWER.key(),n("9.9"),n("10"),n("5"),
                10,10,1000,TICK,NOW.plusSeconds(1)),
            new Quote(PROVIDER,LOWER.key(),n("9.9"),n("10"),n("5"),
                10,10,1000,NOW.minusSeconds(150),NOW.minusSeconds(140)))){
            state(eval(LOWER,CENTER,UPPER,history(),SPOT,
                bad,CENTER_QUOTE,UPPER_QUOTE,RIGHTS,ASSUMPTIONS),
                Status.BLOCKED_DATA,"LONG_BUTTERFLY_QUOTES_OR_IV_INVALID");
        }
    }

    @Test void negativeDebitCannotBeTreatedAsValidCallButterfly(){
        var bargainLow=quote(LOWER.key(),"2.95","3","5",10,10,1000);
        var bargainHigh=quote(UPPER.key(),"1.95","2","5",10,10,1000);
        state(eval(LOWER,CENTER,UPPER,history(),SPOT,
            bargainLow,CENTER_QUOTE,bargainHigh,RIGHTS,ASSUMPTIONS),
            Status.NO_SIGNAL,"LONG_BUTTERFLY_NONPOSITIVE_DEBIT");
    }

    @Test void debitGreaterThanPeakIntrinsicPayoffCannotBeCandidate(){
        var expensiveLower=quote(LOWER.key(),"20.9","21","5",10,10,1000);
        state(eval(LOWER,CENTER,UPPER,history(),SPOT,
            expensiveLower,CENTER_QUOTE,UPPER_QUOTE,RIGHTS,ASSUMPTIONS),
            Status.NO_SIGNAL,"LONG_BUTTERFLY_DEBIT_EXCEEDS_PEAK_PAYOFF");
    }

    @Test void premiumAtRiskMustFitResearchBudget(){
        var limited=new Assumptions(50,50,300,2,50,10000,500,300,
            n("20"),n("100"),100,1000,8000);
        state(eval(LOWER,CENTER,UPPER,history(),SPOT,
            LOWER_QUOTE,CENTER_QUOTE,UPPER_QUOTE,RIGHTS,limited),
            Status.NO_SIGNAL,"LONG_BUTTERFLY_FULL_DEBIT_BUDGET_EXCEEDED");
    }

    @Test void overlyWeakRewardRiskIsNotResearchCandidate(){
        var strict=new Assumptions(50,50,300,2,50,10000,500,300,
            n("20"),n("10000"),1000,90000,8000);
        state(eval(LOWER,CENTER,UPPER,history(),SPOT,
            LOWER_QUOTE,CENTER_QUOTE,UPPER_QUOTE,RIGHTS,strict),
            Status.NO_SIGNAL,"LONG_BUTTERFLY_REWARD_TO_RISK_INADEQUATE");
    }

    @Test void largeSourceIvMoveRelativeToWingFailsHeuristic(){
        var low=quote(LOWER.key(),"9.9","10","300",10,10,1000);
        var center=quote(CENTER.key(),"6","6.1","300",10,10,1000);
        var high=quote(UPPER.key(),"3.95","4","300",10,10,1000);
        state(eval(LOWER,CENTER,UPPER,history(),SPOT,
            low,center,high,RIGHTS,ASSUMPTIONS),
            Status.NO_SIGNAL,"LONG_BUTTERFLY_IV_MOVE_EXCEEDS_WING_WIDTH");
    }

    @Test void zeroFeeAssumptionAndRepeatabilityNeverAuthorizeOrders(){
        var invalid=new Assumptions(50,50,300,2,50,10000,500,300,
            n("0"),n("10000"),1000,1000,8000);
        state(eval(LOWER,CENTER,UPPER,history(),SPOT,
            LOWER_QUOTE,CENTER_QUOTE,UPPER_QUOTE,RIGHTS,invalid),
            Status.BLOCKED_DATA,"LONG_BUTTERFLY_CONTRACT_OR_COST_INVALID");
        assertEquals(run(),run());assertFalse(run().liveTradeAuthorized());
    }
}
