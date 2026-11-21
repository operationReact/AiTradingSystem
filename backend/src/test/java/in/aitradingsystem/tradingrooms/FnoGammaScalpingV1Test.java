package in.aitradingsystem.tradingrooms;

import static org.junit.jupiter.api.Assertions.*;
import in.aitradingsystem.tradingrooms.FnoGammaScalpingV1.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FnoGammaScalpingV1Test {
    private static final Instant NOW=Instant.parse("2026-11-20T05:00:00Z");
    private static final Instant[] TIMES={
        Instant.parse("2026-11-20T04:15:00Z"),
        Instant.parse("2026-11-20T04:30:00Z"),
        Instant.parse("2026-11-20T04:45:00Z"),
        Instant.parse("2026-11-20T04:59:00Z")
    };
    private static final String PROVIDER="UPSTOX_ANALYTICS";
    private static final String INDEX=FnoLongOptionCrossoverV1.UNDERLYING;
    private static final String CE="NSE_FO|NIFTY_20261127_100_CE";
    private static final String PE="NSE_FO|NIFTY_20261127_100_PE";
    private static final String FUT="NSE_FO|NIFTY_20261126_FUT";
    private static final LocalDate LISTED=LocalDate.of(2026,11,1);
    private static final OptionContract CALL=new OptionContract(CE,INDEX,"CE",
        n("100"),n("0.05"),LISTED,LocalDate.of(2026,11,27),50,true);
    private static final OptionContract PUT=new OptionContract(PE,INDEX,"PE",
        n("100"),n("0.05"),LISTED,LocalDate.of(2026,11,27),50,true);
    private static final FutureContract FUTURE=new FutureContract(FUT,INDEX,n("0.05"),
        LISTED,LocalDate.of(2026,11,26),50,true);
    private static final Rights RIGHTS=new Rights(true,true,true,true,true);
    private static final Assumptions A=new Assumptions(3,30,180,300,300,
        5,2,300,25,25,n("5"),10000);
    private static BigDecimal n(String v){return new BigDecimal(v);}

    private static List<CompletedClose> history(){
        var days=new ArrayList<LocalDate>();
        var d=LocalDate.of(2026,11,19);
        while(days.size()<35) {
            if(d.getDayOfWeek().getValue()<=5)days.add(0,d);
            d=d.minusDays(1);
        }
        var out=new ArrayList<CompletedClose>();
        for(var day:days){
            Instant close=day.atTime(LocalTime.of(15,30))
                .atZone(ZoneId.of("Asia/Kolkata")).toInstant();
            out.add(new CompletedClose(day,PROVIDER,n("100"),
                close,close.plusSeconds(60),true));
        }
        return out;
    }
    private static Sample sample(int index,String spot,String ceDelta,String peDelta){
        BigDecimal mid=n(spot);
        return new Sample(PROVIDER,INDEX,CE,PE,FUT,mid,
            mid.add(n("0.95")),mid.add(n("1.05")),
            n("3.95"),n("4"),n("3.95"),n("4"),
            n(ceDelta),n(peDelta),n("0.05"),n("0.05"),
            n("-1"),n("-1"),n("30"),n("30"),1000,1000,
            10,10,TIMES[index],TIMES[index].plusSeconds(5),true);
    }
    private static List<Sample> path(){
        return new ArrayList<>(List.of(
            sample(0,"100","0.55","-0.45"),
            sample(1,"110","0.9","-0.1"),
            sample(2,"90","0.1","-0.9"),
            sample(3,"108","0.8","-0.2")
        ));
    }
    private static Sample replace(Sample s,String field,Object value){
        // Fixture-only copy helper. Production evaluates records, never strings.
        return new Sample(
            "provider".equals(field)?(String)value:s.provider(),
            s.indexKey(),s.callKey(),s.putKey(),
            "futureKey".equals(field)?(String)value:s.futureKey(),
            s.indexSpot(),
            "futureBid".equals(field)?(BigDecimal)value:s.futuresBid(),s.futuresAsk(),
            s.callBid(),s.callAsk(),s.putBid(),s.putAsk(),
            "callDelta".equals(field)?(BigDecimal)value:s.callDelta(),
            "putDelta".equals(field)?(BigDecimal)value:s.putDelta(),
            "callGamma".equals(field)?(BigDecimal)value:s.callGamma(),
            "putGamma".equals(field)?(BigDecimal)value:s.putGamma(),
            "callTheta".equals(field)?(BigDecimal)value:s.callThetaPerDay(),
            s.putThetaPerDay(),
            "callIv".equals(field)?(BigDecimal)value:s.callIvPercent(),
            s.putIvPercent(),
            "callOi".equals(field)?(Long)value:s.callOpenInterestLots(),
            s.putOpenInterestLots(),
            "bidDepth".equals(field)?(Long)value:s.futureBidDepthLots(),
            s.futureAskDepthLots(),
            "exchangeAt".equals(field)?(Instant)value:s.exchangeAt(),
            "knownAt".equals(field)?(Instant)value:s.firstKnownAt(),
            "verified".equals(field)?(Boolean)value:s.sourceVerified());
    }
    private static Decision evaluate(OptionContract call,OptionContract put,
                                     FutureContract future,List<CompletedClose> history,
                                     List<Sample> path,Rights rights,Assumptions a){
        return FnoGammaScalpingV1.evaluate(call,put,future,history,path,rights,a,NOW);
    }
    private static Decision run(){
        return evaluate(CALL,PUT,FUTURE,history(),path(),RIGHTS,A);
    }
    private static void state(Decision d,Status s,String reason){
        assertEquals(s,d.status());assertEquals(reason,d.reasonCode());
        assertEquals("strategy.STR-203",d.strategyId());
        assertTrue(d.retrospectiveOnly());
        assertFalse(d.actualOptionOrHedgeFillsObserved());
        assertFalse(d.executable());assertFalse(d.orderPlaced());
        assertFalse(d.liveTradeAuthorized());
    }
    @Test void deltaRebalanceGammaThetaHedgeCostCalculationIsResearchOnly(){
        var d=run();
        state(d,Status.RESEARCH_CANDIDATE,"GAMMA_SCALP_RETROSPECTIVE_RESEARCH_ONLY");
        assertEquals(4,d.originalSamples());
        assertEquals(3,d.hypotheticalFuturesRebalances());
        assertEquals(1,d.largestAbsoluteHedgeLots());
        assertEquals(0,d.approximateGammaCaptureInr().compareTo(n("2060.0000")));
        assertTrue(d.estimatedThetaDragInr().signum()>0);
        assertTrue(d.estimatedFuturesHedgeCostsInr().signum()>0);
        assertTrue(d.approximateNetGammaThetaAfterHedgeCostsInr().signum()>0);
    }
    @Test void fiveOriginalSourceRightsMustAllBePresent(){
        for(var rights:List.of(
            new Rights(false,true,true,true,true),new Rights(true,false,true,true,true),
            new Rights(true,true,false,true,true),new Rights(true,true,true,false,true),
            new Rights(true,true,true,true,false))){
            state(evaluate(CALL,PUT,FUTURE,history(),path(),rights,A),
                Status.BLOCKED_RIGHTS,"GAMMA_SCALP_SOURCE_RIGHTS_UNVERIFIED");
        }
    }
    @Test void genuineMatchedLongAtmCePeAndListedFutureMustValidate(){
        for(var bad:List.of(
            new OptionContract(PE,INDEX,"CE",n("100"),n("0.05"),LISTED,
                PUT.expiry(),50,true),
            new OptionContract(PE,INDEX,"PE",n("105"),n("0.05"),LISTED,
                PUT.expiry(),50,true),
            new OptionContract(PE,INDEX,"PE",n("100"),n("0.05"),LISTED,
                PUT.expiry().plusDays(7),50,true),
            new OptionContract(PE,INDEX,"PE",n("100"),n("0.05"),LISTED,
                PUT.expiry(),25,true),
            new OptionContract(PE,INDEX,"PE",n("100"),n("0.05"),LISTED,
                PUT.expiry(),50,false))){
            state(evaluate(CALL,bad,FUTURE,history(),path(),RIGHTS,A),
                Status.BLOCKED_DATA,"GAMMA_SCALP_CONTRACT_OR_ASSUMPTIONS_INVALID");
        }
    }
    @Test void futureMustBeDifferentOfficialListedInstrumentNotInventedIndexFill(){
        var unlisted=new FutureContract(FUT,INDEX,n("0.05"),LISTED,
            FUTURE.expiry(),50,false);
        state(evaluate(CALL,PUT,unlisted,history(),path(),RIGHTS,A),
            Status.BLOCKED_DATA,"GAMMA_SCALP_CONTRACT_OR_ASSUMPTIONS_INVALID");
        var badFuture=new FutureContract("NSE_FO|FAKE_FUT","NSE_INDEX|FAKE",n("0.05"),
            LISTED,FUTURE.expiry(),50,true);
        state(evaluate(CALL,PUT,badFuture,history(),path(),RIGHTS,A),
            Status.BLOCKED_DATA,"GAMMA_SCALP_CONTRACT_OR_ASSUMPTIONS_INVALID");
    }
    @Test void originalHistoricalIndexClosesMustBeCompleteAndNotFutureKnown(){
        var bars=history();var last=bars.getLast();
        bars.set(34,new CompletedClose(last.session(),PROVIDER,last.close(),
            last.exchangeCloseAt(),NOW.plusSeconds(1),true));
        state(evaluate(CALL,PUT,FUTURE,bars,path(),RIGHTS,A),
            Status.BLOCKED_DATA,"GAMMA_SCALP_COMPLETED_HISTORY_INVALID");
        bars=history();bars.set(34,bars.get(33));
        state(evaluate(CALL,PUT,FUTURE,bars,path(),RIGHTS,A),
            Status.BLOCKED_DATA,"GAMMA_SCALP_COMPLETED_HISTORY_INVALID");
    }
    @Test void eachTickMustBeOriginalSameProviderAndCorrectListedContract(){
        var ticks=path();
        ticks.set(1,replace(ticks.get(1),"provider","ANGEL_ONE_SMARTAPI"));
        state(evaluate(CALL,PUT,FUTURE,history(),ticks,RIGHTS,A),
            Status.BLOCKED_DATA,"GAMMA_SCALP_SOURCE_GREEKS_OR_PATH_INVALID");
        ticks=path();ticks.set(1,replace(ticks.get(1),"futureKey","NSE_FO|UNKNOWN"));
        state(evaluate(CALL,PUT,FUTURE,history(),ticks,RIGHTS,A),
            Status.BLOCKED_DATA,"GAMMA_SCALP_SOURCE_GREEKS_OR_PATH_INVALID");
        ticks=path();ticks.set(1,replace(ticks.get(1),"verified",false));
        state(evaluate(CALL,PUT,FUTURE,history(),ticks,RIGHTS,A),
            Status.BLOCKED_DATA,"GAMMA_SCALP_SOURCE_GREEKS_OR_PATH_INVALID");
    }
    @Test void sourceGreeksCannotBeInvalidOrFabricated(){
        for(var pair:List.of(
            new Object[]{"callDelta",n("-0.2")},
            new Object[]{"putDelta",n("0.2")},
            new Object[]{"callGamma",n("0")},
            new Object[]{"putGamma",n("-0.01")},
            new Object[]{"callTheta",n("1")},
            new Object[]{"callIv",n("0")},
            new Object[]{"callIv",n("500")})){
            var ticks=path();ticks.set(1,replace(ticks.get(1),(String)pair[0],pair[1]));
            state(evaluate(CALL,PUT,FUTURE,history(),ticks,RIGHTS,A),
                Status.BLOCKED_DATA,"GAMMA_SCALP_SOURCE_GREEKS_OR_PATH_INVALID");
        }
    }
    @Test void crossTimestampFutureFirstKnownAndStaleLastQuoteFailClosed(){
        var ticks=path();
        ticks.set(3,replace(ticks.get(3),"knownAt",NOW.plusSeconds(1)));
        state(evaluate(CALL,PUT,FUTURE,history(),ticks,RIGHTS,A),
            Status.BLOCKED_DATA,"GAMMA_SCALP_SOURCE_GREEKS_OR_PATH_INVALID");
        ticks=path();
        ticks.set(3,replace(ticks.get(3),"exchangeAt",NOW.minusSeconds(250)));
        state(evaluate(CALL,PUT,FUTURE,history(),ticks,RIGHTS,A),
            Status.BLOCKED_DATA,"GAMMA_SCALP_SOURCE_GREEKS_OR_PATH_INVALID");
        ticks=path();
        ticks.set(1,replace(ticks.get(1),"exchangeAt",TIMES[0]));
        state(evaluate(CALL,PUT,FUTURE,history(),ticks,RIGHTS,A),
            Status.BLOCKED_DATA,"GAMMA_SCALP_SOURCE_GREEKS_OR_PATH_INVALID");
    }
    @Test void sourceActualFuturesBidAskMustNotBeCrossedOrIlliquid(){
        var ticks=path();
        ticks.set(1,replace(ticks.get(1),"futureBid",n("999")));
        state(evaluate(CALL,PUT,FUTURE,history(),ticks,RIGHTS,A),
            Status.BLOCKED_DATA,"GAMMA_SCALP_SOURCE_GREEKS_OR_PATH_INVALID");
        ticks=path();ticks.set(1,replace(ticks.get(1),"bidDepth",0L));
        state(evaluate(CALL,PUT,FUTURE,history(),ticks,RIGHTS,A),
            Status.BLOCKED_DATA,"GAMMA_SCALP_SOURCE_GREEKS_OR_PATH_INVALID");
        ticks=path();ticks.set(1,replace(ticks.get(1),"callOi",0L));
        state(evaluate(CALL,PUT,FUTURE,history(),ticks,RIGHTS,A),
            Status.BLOCKED_DATA,"GAMMA_SCALP_SOURCE_GREEKS_OR_PATH_INVALID");
    }
    @Test void noRoundedFuturesHedgeMeansNoGammaScalpOpportunity(){
        var ticks=new ArrayList<>(List.of(
            sample(0,"100","0.55","-0.45"),
            sample(1,"100.1","0.55","-0.45"),
            sample(2,"99.9","0.55","-0.45"),
            sample(3,"100.1","0.55","-0.45")
        ));
        state(evaluate(CALL,PUT,FUTURE,history(),ticks,RIGHTS,A),
            Status.NO_SIGNAL,"GAMMA_SCALP_NO_DELTA_REBALANCE");
    }
    @Test void highModeledBrokerHedgeFeesExceedObservedGammaCapture(){
        var expensive=new Assumptions(3,30,180,300,300,5,2,300,
            25,25,n("1000"),10000);
        var d=evaluate(CALL,PUT,FUTURE,history(),path(),RIGHTS,expensive);
        state(d,Status.NO_SIGNAL,"GAMMA_SCALP_APPROXIMATE_CAPTURE_INADEQUATE");
        assertTrue(d.estimatedFuturesHedgeCostsInr().compareTo(d.approximateGammaCaptureInr())>0);
    }
    @Test void pathNeedsMultipleOriginalObservations(){
        var ticks=path();
        state(evaluate(CALL,PUT,FUTURE,history(),ticks.subList(2,4),RIGHTS,A),
            Status.BLOCKED_DATA,"GAMMA_SCALP_SOURCE_GREEKS_OR_PATH_INVALID");
    }
    @Test void badSourceFeesAndContradictoryThresholdsFailClosed(){
        var invalid=new Assumptions(3,30,180,300,300,5,2,300,25,25,n("0"),10000);
        state(evaluate(CALL,PUT,FUTURE,history(),path(),RIGHTS,invalid),
            Status.BLOCKED_DATA,"GAMMA_SCALP_CONTRACT_OR_ASSUMPTIONS_INVALID");
        invalid=new Assumptions(3,30,180,300,300,5,2,300,25,25,n("5"),0);
        state(evaluate(CALL,PUT,FUTURE,history(),path(),RIGHTS,invalid),
            Status.BLOCKED_DATA,"GAMMA_SCALP_CONTRACT_OR_ASSUMPTIONS_INVALID");
    }
    @Test void deterministicAndNeverAnExecutedOrLiveApprovedPosition(){
        assertEquals(run(),run());
        assertFalse(run().actualOptionOrHedgeFillsObserved());
        assertTrue(run().retrospectiveOnly());
        assertEquals(0,run().approximateGammaCaptureInr()
            .subtract(run().estimatedThetaDragInr())
            .subtract(run().estimatedFuturesHedgeCostsInr())
            .setScale(4,RoundingMode.HALF_UP)
            .compareTo(run().approximateNetGammaThetaAfterHedgeCostsInr()));
    }
}
