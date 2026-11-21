package in.aitradingsystem.tradingrooms;

import static org.junit.jupiter.api.Assertions.*;
import in.aitradingsystem.tradingrooms.FnoIronCondorV1.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FnoIronCondorV1Test {
    static final Instant NOW=Instant.parse("2026-11-20T05:00:00Z");
    static final Instant TICK=NOW.minusSeconds(40);
    static final String PROVIDER="UPSTOX_ANALYTICS";
    static final String INDEX=FnoLongOptionCrossoverV1.UNDERLYING;
    static final LocalDate EXP=LocalDate.of(2026,11,27);
    static final Contract LP=contract("NSE_FO|NIFTY_80_PE","PE","80");
    static final Contract SP=contract("NSE_FO|NIFTY_90_PE","PE","90");
    static final Contract SC=contract("NSE_FO|NIFTY_110_CE","CE","110");
    static final Contract LC=contract("NSE_FO|NIFTY_120_CE","CE","120");
    static final Quote SPOT=quote(INDEX,"100","100.10","5",100,100,100000);
    static final Quote LPQ=quote(LP.key(),"0.99","1","5",10,10,1000);
    static final Quote SPQ=quote(SP.key(),"4","4.05","5",10,10,1000);
    static final Quote SCQ=quote(SC.key(),"4","4.05","5",10,10,1000);
    static final Quote LCQ=quote(LC.key(),"0.99","1","5",10,10,1000);
    static final Rights RIGHTS=new Rights(true,true,true,true);
    static final Assumptions A=new Assumptions(50,50,300,2,50,10000,300,
        n("20"),n("10000"),1000,1000,10000);
    static BigDecimal n(String v){return new BigDecimal(v);}
    static Contract contract(String key,String type,String strike){
        return new Contract(key,INDEX,type,n(strike),n("0.05"),
            LocalDate.of(2026,11,1),EXP,50,true);
    }
    static Quote quote(String key,String bid,String ask,String iv,
                       long bd,long ad,long oi){
        return new Quote(PROVIDER,key,n(bid),n(ask),iv==null?null:n(iv),
            bd,ad,oi,TICK,TICK.plusSeconds(10));
    }
    static List<CompletedClose> history(){
        var dates=new ArrayList<LocalDate>();
        var day=LocalDate.of(2026,11,19);
        while(dates.size()<35){
            if(day.getDayOfWeek().getValue()<=5)dates.add(0,day);
            day=day.minusDays(1);
        }
        var list=new ArrayList<CompletedClose>();
        for(var d:dates){
            var close=d.atTime(LocalTime.of(15,30))
                .atZone(ZoneId.of("Asia/Kolkata")).toInstant();
            list.add(new CompletedClose(d,PROVIDER,n("100"),close,
                close.plusSeconds(60),true));
        }
        return list;
    }
    static Decision eval(Contract lp,Contract sp,Contract sc,Contract lc,
                         List<CompletedClose> h,Quote spot,Quote lpq,Quote spq,
                         Quote scq,Quote lcq,Rights r,Assumptions a) {
        return FnoIronCondorV1.evaluate(lp,sp,sc,lc,h,spot,lpq,spq,scq,lcq,r,a,NOW);
    }
    static Decision run(){
        return eval(LP,SP,SC,LC,history(),SPOT,LPQ,SPQ,SCQ,LCQ,RIGHTS,A);
    }
    static void state(Decision d,Status expected,String reason){
        assertEquals(expected,d.status());assertEquals(reason,d.reasonCode());
        assertEquals("strategy.STR-196",d.strategyId());
        assertTrue(d.expiryPayoffDefinedRisk());
        assertFalse(d.executable());assertFalse(d.orderPlaced());
        assertFalse(d.liveTradeAuthorized());
    }

    @Test void fourRealMatchedLegsYieldFiniteResearchExpiryPayoffNeverAnOrder(){
        var d=run();
        state(d,Status.RESEARCH_CANDIDATE,"IRON_CONDOR_DEFINED_EXPIRY_RISK_RESEARCH_ONLY");
        assertEquals(LP.key(),d.longPutKey());assertEquals(SP.key(),d.shortPutKey());
        assertEquals(SC.key(),d.shortCallKey());assertEquals(LC.key(),d.longCallKey());
        assertTrue(d.netCreditInr().signum()>0);
        assertEquals(0,d.netCreditInr().compareTo(d.theoreticalMaximumExpiryProfitInr()));
        assertEquals(0,d.theoreticalMaximumExpiryLossInr()
            .add(d.netCreditInr()).compareTo(n("500.0000")));
        assertTrue(d.lowerBreakeven().compareTo(n("90"))<0);
        assertTrue(d.upperBreakeven().compareTo(n("110"))>0);
    }
    @Test void independentIndexOptionInstrumentAndIvRightsAllRequired(){
        for(var rights:List.of(new Rights(false,true,true,true),
                new Rights(true,false,true,true),
                new Rights(true,true,false,true),
                new Rights(true,true,true,false))){
            state(eval(LP,SP,SC,LC,history(),SPOT,LPQ,SPQ,SCQ,LCQ,rights,A),
                Status.BLOCKED_RIGHTS,"IRON_CONDOR_RIGHTS_MISSING");
        }
    }
    @Test void allFourStrikeTypeLotExpiryAndListingIdentityMustBeValid(){
        for(var invalid:List.of(
            contract(LC.key(),"PE","120"),
            contract(LC.key(),"CE","105"),
            new Contract(LC.key(),INDEX,"CE",n("120"),n("0.05"),
                LC.listedFrom(),EXP,25,true),
            new Contract(LC.key(),INDEX,"CE",n("120"),n("0.05"),
                LC.listedFrom(),EXP.plusDays(7),50,true),
            new Contract(LC.key(),INDEX,"CE",n("120"),n("0.05"),
                LC.listedFrom(),EXP,50,false))){
            state(eval(LP,SP,SC,invalid,history(),SPOT,LPQ,SPQ,SCQ,LCQ,RIGHTS,A),
                Status.BLOCKED_DATA,"IRON_CONDOR_CONTRACT_OR_COST_INVALID");
        }
        state(eval(LP,SP,SC,SC,history(),SPOT,LPQ,SPQ,SCQ,LCQ,RIGHTS,A),
            Status.BLOCKED_DATA,"IRON_CONDOR_CONTRACT_OR_COST_INVALID");
    }
    @Test void underlyingMustStayStrictlyBetweenInnerShortStrikes(){
        for(var value:List.of("90","110")){
            var spot=quote(INDEX,value,"111","5",100,100,100000);
            state(eval(LP,SP,SC,LC,history(),spot,LPQ,SPQ,SCQ,LCQ,RIGHTS,A),
                Status.BLOCKED_DATA,"IRON_CONDOR_QUOTE_OR_IV_INVALID");
        }
    }
    @Test void quotesRejectWrongProviderMissingIvCrossedBidAndThinDirectionSpecificDepth(){
        for(var q:List.of(
            new Quote("ANGEL_ONE_SMARTAPI",SC.key(),n("4"),n("4.05"),n("5"),
                10,10,1000,TICK,TICK.plusSeconds(10)),
            quote(SC.key(),"4","4.05",null,10,10,1000),
            quote(SC.key(),"4.50","4.05","5",10,10,1000),
            quote(SC.key(),"4","4.05","5",0,10,1000),
            quote(SC.key(),"4","4.05","5",10,10,0),
            quote(SC.key(),"4","4.05","501",10,10,1000))){
            state(eval(LP,SP,SC,LC,history(),SPOT,LPQ,SPQ,q,LCQ,RIGHTS,A),
                Status.BLOCKED_DATA,"IRON_CONDOR_QUOTE_OR_IV_INVALID");
        }
        var noBuyDepth=quote(LC.key(),"0.99","1","5",10,0,1000);
        state(eval(LP,SP,SC,LC,history(),SPOT,LPQ,SPQ,SCQ,noBuyDepth,RIGHTS,A),
            Status.BLOCKED_DATA,"IRON_CONDOR_QUOTE_OR_IV_INVALID");
    }
    @Test void quoteStalenessFutureKnowledgeAndCrossLegSkewBlock(){
        for(var q:List.of(
            new Quote(PROVIDER,LP.key(),n("0.99"),n("1"),n("5"),10,10,1000,
                NOW.minusSeconds(190),NOW.minusSeconds(180)),
            new Quote(PROVIDER,LP.key(),n("0.99"),n("1"),n("5"),10,10,1000,
                TICK,NOW.plusSeconds(1)),
            new Quote(PROVIDER,LP.key(),n("0.99"),n("1"),n("5"),10,10,1000,
                NOW.minusSeconds(150),NOW.minusSeconds(140)))){
            state(eval(LP,SP,SC,LC,history(),SPOT,q,SPQ,SCQ,LCQ,RIGHTS,A),
                Status.BLOCKED_DATA,"IRON_CONDOR_QUOTE_OR_IV_INVALID");
        }
    }
    @Test void completedBarIntegrityAndOriginalFirstSeenMustBeProven(){
        var bars=history();var last=bars.getLast();
        bars.set(34,new CompletedClose(last.session(),PROVIDER,last.close(),
            last.exchangeCloseAt(),NOW.plusSeconds(1),true));
        state(eval(LP,SP,SC,LC,bars,SPOT,LPQ,SPQ,SCQ,LCQ,RIGHTS,A),
            Status.BLOCKED_DATA,"IRON_CONDOR_COMPLETED_HISTORY_INVALID");
        bars=history();bars.set(34,bars.get(33));
        state(eval(LP,SP,SC,LC,bars,SPOT,LPQ,SPQ,SCQ,LCQ,RIGHTS,A),
            Status.BLOCKED_DATA,"IRON_CONDOR_COMPLETED_HISTORY_INVALID");
    }
    @Test void trulyAsymmetricWingsUseLargerWingForMaximumLoss(){
        var wide=contract("NSE_FO|NIFTY_140_CE","CE","140");
        var q=quote(wide.key(),"0.99","1","5",10,10,1000);
        var budget=new Assumptions(50,50,300,2,50,10000,300,
            n("20"),n("100000"),1000,1000,10000);
        var d=eval(LP,SP,SC,wide,history(),SPOT,LPQ,SPQ,SCQ,q,RIGHTS,budget);
        state(d,Status.RESEARCH_CANDIDATE,"IRON_CONDOR_DEFINED_EXPIRY_RISK_RESEARCH_ONLY");
        assertEquals(0,d.theoreticalMaximumExpiryLossInr().add(d.netCreditInr())
            .compareTo(n("1500")));
    }
    @Test void nonpositiveCombinedCreditIsNeverAResearchCandidate(){
        var cheap=quote(SP.key(),"0.20","0.202","5",10,10,1000);
        state(eval(LP,SP,SC,LC,history(),SPOT,LPQ,cheap,SCQ,LCQ,RIGHTS,A),
            Status.RESEARCH_CANDIDATE,"IRON_CONDOR_DEFINED_EXPIRY_RISK_RESEARCH_ONLY");
        var bothCheap=quote(SC.key(),"0.20","0.202","5",10,10,1000);
        state(eval(LP,SP,SC,LC,history(),SPOT,LPQ,cheap,bothCheap,LCQ,RIGHTS,A),
            Status.NO_SIGNAL,"IRON_CONDOR_NONPOSITIVE_NET_CREDIT");
    }
    @Test void fullWorstWingNotPremiumControlsRiskAllocation(){
        var limited=new Assumptions(50,50,300,2,50,10000,300,
            n("20"),n("100"),100,1000,10000);
        state(eval(LP,SP,SC,LC,history(),SPOT,LPQ,SPQ,SCQ,LCQ,RIGHTS,limited),
            Status.NO_SIGNAL,"IRON_CONDOR_WORST_EXPIRY_LOSS_BUDGET_EXCEEDED");
    }
    @Test void highLicensedIvMoveFailsIllustrativeShortStrikeDistanceGate(){
        var x=quote(SC.key(),"4","4.05","300",10,10,1000);
        var y=quote(SP.key(),"4","4.05","300",10,10,1000);
        var z=quote(LC.key(),"0.99","1","300",10,10,1000);
        var t=quote(LP.key(),"0.99","1","300",10,10,1000);
        state(eval(LP,SP,SC,LC,history(),SPOT,t,y,x,z,RIGHTS,A),
            Status.NO_SIGNAL,"IRON_CONDOR_IV_MOVE_CROSSES_SHORT_WING_HURDLE");
    }
    @Test void zeroFourLegFeesCannotPassAndRepeatableResultsNeverAuthorizeOrders(){
        var zero=new Assumptions(50,50,300,2,50,10000,300,
            n("0"),n("10000"),1000,1000,10000);
        state(eval(LP,SP,SC,LC,history(),SPOT,LPQ,SPQ,SCQ,LCQ,RIGHTS,zero),
            Status.BLOCKED_DATA,"IRON_CONDOR_CONTRACT_OR_COST_INVALID");
        assertEquals(run(),run());assertFalse(run().orderPlaced());
    }
}
