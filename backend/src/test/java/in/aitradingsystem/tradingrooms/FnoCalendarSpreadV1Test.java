package in.aitradingsystem.tradingrooms;

import static org.junit.jupiter.api.Assertions.*;
import in.aitradingsystem.tradingrooms.FnoCalendarSpreadV1.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FnoCalendarSpreadV1Test {
    static final Instant NOW=Instant.parse("2026-11-20T05:00:00Z");
    static final Instant TICK=NOW.minusSeconds(40);
    static final String PROVIDER="UPSTOX_ANALYTICS";
    static final String INDEX=FnoLongOptionCrossoverV1.UNDERLYING;
    static final String NEAR_KEY="NSE_FO|NIFTY_20261127_100_CE";
    static final String FAR_KEY="NSE_FO|NIFTY_20261224_100_CE";
    static final LocalDate NEAR_EXPIRY=LocalDate.of(2026,11,27);
    static final LocalDate FAR_EXPIRY=LocalDate.of(2026,12,24);
    static final Contract NEAR=new Contract(NEAR_KEY,INDEX,"CE",n("100"),n("0.05"),
        LocalDate.of(2026,11,1),NEAR_EXPIRY,50,true);
    static final Contract FAR=new Contract(FAR_KEY,INDEX,"CE",n("100"),n("0.05"),
        LocalDate.of(2026,11,1),FAR_EXPIRY,50,true);
    static final Quote SPOT=quote(INDEX,"100","100.05",null,100,100,100000);
    static final Quote NEAR_QUOTE=quote(NEAR_KEY,"3.95","4","40",10,10,1000);
    static final Quote FAR_QUOTE=quote(FAR_KEY,"5.95","6","35",10,10,1000);
    static final Rights RIGHTS=new Rights(true,true,true,true);
    static final Assumptions A=new Assumptions(50,50,300,2,50,
        1500,300,n("20"),n("10000"),1000,9000,5000,2000);

    static BigDecimal n(String x){return new BigDecimal(x);}
    static Quote quote(String key,String bid,String ask,String iv,long bd,long ad,long oi){
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
        var h=new ArrayList<CompletedClose>();
        for(var day:days) {
            var at=day.atTime(LocalTime.of(15,30))
                .atZone(ZoneId.of("Asia/Kolkata")).toInstant();
            h.add(new CompletedClose(day,PROVIDER,n("100"),at,at.plusSeconds(60),true));
        }
        return h;
    }
    static Decision eval(Contract near,Contract far,List<CompletedClose> h,
                         Quote spot,Quote nq,Quote fq,Rights rights,Assumptions a){
        return FnoCalendarSpreadV1.evaluate(near,far,h,spot,nq,fq,rights,a,NOW);
    }
    static Decision run(){return eval(NEAR,FAR,history(),SPOT,NEAR_QUOTE,FAR_QUOTE,RIGHTS,A);}
    static void state(Decision d,Status st,String reason){
        assertEquals(st,d.status());assertEquals(reason,d.reasonCode());
        assertEquals("strategy.STR-199",d.strategyId());
        assertTrue(d.nearExpiryResidualLongMarkUnknown());
        assertFalse(d.executable());assertFalse(d.orderPlaced());assertFalse(d.liveTradeAuthorized());
    }
    @Test void genuineCrossExpirySameStrikeCallGeneratesResearchOnlyCandidate(){
        var d=run();
        state(d,Status.RESEARCH_CANDIDATE,"CALENDAR_SPREAD_MARK_TO_MARKET_UNRESOLVED_RESEARCH_ONLY");
        assertEquals(NEAR_KEY,d.shortNearKey());assertEquals(FAR_KEY,d.longFarKey());
        assertEquals(NEAR_EXPIRY,d.nearExpiry());assertEquals(FAR_EXPIRY,d.farExpiry());
        assertEquals(0,d.netDebitWithFeesInr().compareTo(n("124.9875")));
        assertTrue(d.nearIvToFarIvRatioBps().compareTo(n("9000"))>0);
        assertTrue(d.shortPremiumToLongPremiumRatioBps().compareTo(n("5000"))>0);
        assertTrue(d.illustrativeNearOneSigmaMove().signum()>0);
    }
    @Test void allFourLicensedResearchRightsAreMandatory(){
        for(var rights:List.of(new Rights(false,true,true,true),
                new Rights(true,false,true,true),new Rights(true,true,false,true),
                new Rights(true,true,true,false))){
            state(eval(NEAR,FAR,history(),SPOT,NEAR_QUOTE,FAR_QUOTE,rights,A),
                Status.BLOCKED_RIGHTS,"CALENDAR_SPREAD_RIGHTS_UNVERIFIED");
        }
    }
    @Test void sameUnderlyingStrikeOptionTypeAndLotButDistinctOrderedExpiries(){
        for(var invalid:List.of(
            new Contract(FAR_KEY,INDEX,"PE",n("100"),n("0.05"),FAR.listedFrom(),FAR_EXPIRY,50,true),
            new Contract(FAR_KEY,INDEX,"CE",n("105"),n("0.05"),FAR.listedFrom(),FAR_EXPIRY,50,true),
            new Contract(FAR_KEY,INDEX,"CE",n("100"),n("0.05"),FAR.listedFrom(),FAR_EXPIRY,25,true),
            new Contract(FAR_KEY,INDEX,"CE",n("100"),n("0.05"),FAR.listedFrom(),NEAR_EXPIRY,50,true),
            new Contract(FAR_KEY,INDEX,"CE",n("100"),n("0.05"),FAR.listedFrom(),NEAR_EXPIRY.minusDays(1),50,true),
            new Contract(FAR_KEY,INDEX,"CE",n("100"),n("0.05"),FAR.listedFrom(),FAR_EXPIRY,50,false),
            new Contract(FAR_KEY,INDEX,"CE",n("100"),n("0.05"),FAR.listedFrom(),FAR_EXPIRY.plusMonths(4),50,true))){
            state(eval(NEAR,invalid,history(),SPOT,NEAR_QUOTE,FAR_QUOTE,RIGHTS,A),
                Status.BLOCKED_DATA,"CALENDAR_SPREAD_CONTRACT_OR_FEES_INVALID");
        }
        state(eval(NEAR,NEAR,history(),SPOT,NEAR_QUOTE,FAR_QUOTE,RIGHTS,A),
            Status.BLOCKED_DATA,"CALENDAR_SPREAD_CONTRACT_OR_FEES_INVALID");
    }
    @Test void sameStrikePutCalendarAlsoValidatesAgainstTrueSourceQuotes(){
        var putNear=new Contract("NSE_FO|NIFTY_20261127_100_PE",INDEX,"PE",n("100"),n("0.05"),
            NEAR.listedFrom(),NEAR_EXPIRY,50,true);
        var putFar=new Contract("NSE_FO|NIFTY_20261224_100_PE",INDEX,"PE",n("100"),n("0.05"),
            FAR.listedFrom(),FAR_EXPIRY,50,true);
        var nq=quote(putNear.key(),"3.95","4","40",10,10,1000);
        var fq=quote(putFar.key(),"5.95","6","35",10,10,1000);
        state(eval(putNear,putFar,history(),SPOT,nq,fq,RIGHTS,A),
            Status.RESEARCH_CANDIDATE,"CALENDAR_SPREAD_MARK_TO_MARKET_UNRESOLVED_RESEARCH_ONLY");
    }
    @Test void completedBarsRequireSourceFirstKnownAndChronologicalSessions(){
        var h=history();var last=h.getLast();
        h.set(34,new CompletedClose(last.session(),PROVIDER,last.close(),
            last.exchangeCloseAt(),NOW.plusSeconds(1),true));
        state(eval(NEAR,FAR,h,SPOT,NEAR_QUOTE,FAR_QUOTE,RIGHTS,A),
            Status.BLOCKED_DATA,"CALENDAR_SPREAD_HISTORY_INVALID");
        h=history();h.set(34,h.get(33));
        state(eval(NEAR,FAR,h,SPOT,NEAR_QUOTE,FAR_QUOTE,RIGHTS,A),
            Status.BLOCKED_DATA,"CALENDAR_SPREAD_HISTORY_INVALID");
    }
    @Test void authenticIvProviderQuotesAndDirectionSpecificMarketDepthMandatory(){
        for(var bad:List.of(
            quote(NEAR_KEY,"3.95","4",null,10,10,1000),
            quote(NEAR_KEY,"3.95","4","0",10,10,1000),
            quote(NEAR_KEY,"3.95","4","501",10,10,1000),
            quote(NEAR_KEY,"4.25","4","40",10,10,1000),
            quote(NEAR_KEY,"3.95","4","40",0,10,1000),
            quote(NEAR_KEY,"3.95","4","40",10,10,0),
            new Quote("ANGEL_ONE_SMARTAPI",NEAR_KEY,n("3.95"),n("4"),
                n("40"),10,10,1000,TICK,TICK.plusSeconds(10)))){
            state(eval(NEAR,FAR,history(),SPOT,bad,FAR_QUOTE,RIGHTS,A),
                Status.BLOCKED_DATA,"CALENDAR_SPREAD_QUOTES_OR_IV_INVALID");
        }
        var noBuyDepth=quote(FAR_KEY,"5.95","6","35",10,0,1000);
        state(eval(NEAR,FAR,history(),SPOT,NEAR_QUOTE,noBuyDepth,RIGHTS,A),
            Status.BLOCKED_DATA,"CALENDAR_SPREAD_QUOTES_OR_IV_INVALID");
    }
    @Test void staleFutureKnownAndUnsynchronizedExchangeQuotesFailClosed(){
        for(var bad:List.of(
            new Quote(PROVIDER,FAR_KEY,n("5.95"),n("6"),n("35"),10,10,1000,
                NOW.minusSeconds(220),NOW.minusSeconds(210)),
            new Quote(PROVIDER,FAR_KEY,n("5.95"),n("6"),n("35"),10,10,1000,
                TICK,NOW.plusSeconds(1)),
            new Quote(PROVIDER,FAR_KEY,n("5.95"),n("6"),n("35"),10,10,1000,
                NOW.minusSeconds(150),NOW.minusSeconds(140)))){
            state(eval(NEAR,FAR,history(),SPOT,NEAR_QUOTE,bad,RIGHTS,A),
                Status.BLOCKED_DATA,"CALENDAR_SPREAD_QUOTES_OR_IV_INVALID");
        }
    }
    @Test void nearStrikeMustBeWithinSourceObservedAtmRange(){
        var farAway=quote(INDEX,"120","120.05",null,100,100,100000);
        state(eval(NEAR,FAR,history(),farAway,NEAR_QUOTE,FAR_QUOTE,RIGHTS,A),
            Status.BLOCKED_DATA,"CALENDAR_SPREAD_QUOTES_OR_IV_INVALID");
    }
    @Test void negativeAfterCostDebitNeverProducesCandidate(){
        var nearExpensive=quote(NEAR_KEY,"10","10.05","40",10,10,1000);
        state(eval(NEAR,FAR,history(),SPOT,nearExpensive,FAR_QUOTE,RIGHTS,A),
            Status.NO_SIGNAL,"CALENDAR_SPREAD_NONPOSITIVE_NET_DEBIT");
    }
    @Test void fullResearchPremiumAllocationLimitAppliedToNetDebit(){
        var limited=new Assumptions(50,50,300,2,50,1500,300,
            n("20"),n("100"),1000,9000,5000,2000);
        state(eval(NEAR,FAR,history(),SPOT,NEAR_QUOTE,FAR_QUOTE,RIGHTS,limited),
            Status.NO_SIGNAL,"CALENDAR_SPREAD_NET_DEBIT_BUDGET_EXCEEDED");
    }
    @Test void unfavorableNearVsFarIvTermStructureFailsScreen(){
        var nearLowIv=quote(NEAR_KEY,"3.95","4","10",10,10,1000);
        state(eval(NEAR,FAR,history(),SPOT,nearLowIv,FAR_QUOTE,RIGHTS,A),
            Status.NO_SIGNAL,"CALENDAR_SPREAD_IV_TERM_STRUCTURE_INADEQUATE");
    }
    @Test void weakNearPremiumCoverageFailsDistinctFromIv(){
        var cheap=quote(NEAR_KEY,"1","1.01","40",10,10,1000);
        state(eval(NEAR,FAR,history(),SPOT,cheap,FAR_QUOTE,RIGHTS,A),
            Status.NO_SIGNAL,"CALENDAR_SPREAD_SHORT_PREMIUM_COVERAGE_INADEQUATE");
    }
    @Test void highNearExpiryIvMoveFailsIndependentHeuristic(){
        var high=quote(NEAR_KEY,"3.95","4","300",10,10,1000);
        var stricter=new Assumptions(50,50,300,2,50,1500,300,
            n("20"),n("10000"),1000,9000,5000,1000);
        state(eval(NEAR,FAR,history(),SPOT,high,FAR_QUOTE,RIGHTS,stricter),
            Status.NO_SIGNAL,"CALENDAR_SPREAD_NEAR_IV_MOVE_EXCEEDS_HURDLE");
    }
    @Test void zeroFeesRejectedAndDeterministicDecisionsNeverPlaceOrders(){
        var invalid=new Assumptions(50,50,300,2,50,1500,300,
            n("0"),n("10000"),1000,9000,5000,2000);
        state(eval(NEAR,FAR,history(),SPOT,NEAR_QUOTE,FAR_QUOTE,RIGHTS,invalid),
            Status.BLOCKED_DATA,"CALENDAR_SPREAD_CONTRACT_OR_FEES_INVALID");
        assertEquals(run(),run());assertFalse(run().orderPlaced());
    }
}
