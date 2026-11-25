package in.aitradingsystem.tradingrooms;

import static org.junit.jupiter.api.Assertions.*;
import in.aitradingsystem.tradingrooms.FnoSkewBasedHedgeV1.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FnoSkewBasedHedgeV1Test {
    private static final Instant NOW=Instant.parse("2026-11-20T05:00:00Z");
    private static final Instant EX=Instant.parse("2026-11-20T04:59:20Z");
    private static final Instant FIRST=Instant.parse("2026-11-20T04:59:24Z");
    private static final Instant BROKER=Instant.parse("2026-11-19T07:00:00Z");
    private static final LocalDate EXPIRY=LocalDate.of(2026,11,27);
    private static final LocalDate LISTED=LocalDate.of(2026,11,1);
    private static final String INDEX="NSE_INDEX|Nifty 50";
    private static final String SRC="UPSTOX_ANALYTICS";
    private static final String FUT="NSE_FO|NIFTY_FUT_20261127";
    private static final String HIGH="NSE_FO|NIFTY_99_PE";
    private static final String LOW="NSE_FO|NIFTY_95_PE";
    private static final String CALL="NSE_FO|NIFTY_105_CE";
    private static BigDecimal n(String s){return new BigDecimal(s);}
    private static Rights rights(){return new Rights(true,true,true,true,true,true);}
    private static Policy policy(){
        return new Policy(35,180,30,1000,100,10,300,10,
                3,45,2,n("0.06"),n("4"),100,10,10,n("5"),n("8"),n("200"));
    }
    private static FutureContract future(){
        return new FutureContract(FUT,INDEX,LISTED,EXPIRY,50,true);
    }
    private static BrokerLongFuture held(int lots){
        return new BrokerLongFuture(FUT,lots,n("100"),BROKER,true);
    }
    private static OptionContract option(String key,String type,String strike,
                                         LocalDate expiry,int lot){
        return new OptionContract(key,INDEX,type,n(strike),n("0.05"),
                LISTED,expiry,lot,true);
    }
    private static OptionContract higher(){return option(HIGH,"PE","99",EXPIRY,50);}
    private static OptionContract lower(){return option(LOW,"PE","95",EXPIRY,50);}
    private static OptionContract call(){return option(CALL,"CE","105",EXPIRY,50);}
    private static Quote quote(String key,String bid,String ask,String delta,String iv,
                               long bidDepth,long askDepth,String provider,
                               Instant exchange,Instant firstKnown){
        return new Quote(provider,key,n(bid),n(ask),n(delta),n(iv),1000,
                bidDepth,askDepth,exchange,firstKnown,true);
    }
    private static Quote highQuote(){
        return quote(HIGH,"1.95","2","-0.25","30",10,10,SRC,EX,FIRST);
    }
    private static Quote lowQuote(){
        return quote(LOW,"0.80","0.85","-0.12","33",10,10,SRC,EX,FIRST);
    }
    private static Quote callQuote(){
        return quote(CALL,"1.50","1.55","0.25","20",10,10,SRC,EX,FIRST);
    }
    private static Snapshot snap(Quote high,Quote low,Quote call){
        return new Snapshot(SRC,INDEX,FUT,n("100"),n("100"),n("100.05"),
                high,low,call,EX,FIRST,true);
    }
    private static Snapshot snapshot(){return snap(highQuote(),lowQuote(),callQuote());}
    /** Fictional, reproducible data; NEVER evidence of real exchange quotes. */
    private static List<CompletedClose> history(boolean rising){
        var days=new ArrayList<LocalDate>();
        LocalDate date=LocalDate.of(2026,11,19);
        while(days.size()<35){
            if(date.getDayOfWeek().getValue()<=5)days.add(0,date);
            date=date.minusDays(1);
        }
        var bars=new ArrayList<CompletedClose>();
        for(int i=0;i<days.size();i++){
            LocalDate d=days.get(i);
            Instant close=d.atTime(LocalTime.of(15,30))
                    .atZone(ZoneId.of("Asia/Kolkata")).toInstant();
            BigDecimal px=rising?n(Integer.toString(100+i)):i%2==0?n("100"):n("105");
            bars.add(new CompletedClose(d,SRC,px,close,close.plusSeconds(60),true));
        }
        return bars;
    }
    private static List<CompletedClose> history(){return history(false);}
    private static Decision evaluate(BrokerLongFuture broker,FutureContract future,
                                     OptionContract high,OptionContract low,OptionContract call,
                                     Snapshot snap,List<CompletedClose> completed,
                                     Rights rights,Policy policy){
        return FnoSkewBasedHedgeV1.evaluate(broker,future,high,low,call,
                snap,completed,rights,policy,NOW);
    }
    private static Decision run(){
        return evaluate(held(1),future(),higher(),lower(),call(),
                snapshot(),history(),rights(),policy());
    }
    private static void neverOrders(Decision d){
        assertEquals("strategy.STR-212",d.strategyId());
        assertTrue(d.originalIndexShockPersistsToFutureExpiryAssumption());
        assertFalse(d.executable());
        assertFalse(d.orderPlaced());
        assertFalse(d.paperTradeAuthorized());
        assertFalse(d.liveTradeAuthorized());
    }

    @Test void matchedOriginalPutCallSkewShowsCheaperButTailCappedHedge(){
        Decision d=run();
        assertEquals(Status.RESEARCH_CANDIDATE,d.status());
        assertEquals(35,d.originalCompletedSessions());
        assertEquals(1,d.brokerConfirmedLongFutureLots());
        assertEquals(1,d.hypotheticalPutSpreadLots());
        assertEquals(0,n("10").compareTo(d.sourceOriginalPutCallIvSkewVolPoints()));
        assertTrue(d.worstObservedOriginalDailyIndexDropBps().compareTo(n("475"))>0);
        assertTrue(d.worstObservedOriginalDailyIndexDropBps().compareTo(n("477"))<0);
        assertEquals(0,n("105.1").compareTo(d.hypotheticalOutrightLongPutDebitInr()));
        assertEquals(0,n("68.14").compareTo(d.hypotheticalPutSpreadDebitInr()));
        assertEquals(0,n("36.96").compareTo(d.hypotheticalPremiumSavingsInr()));
        assertEquals(0,n("200").compareTo(d.maximumPutSpreadIntrinsicProtectionInr()));
        assertEquals(0,n("-118.14").compareTo(d.conditionalPutSpreadFutureStressPnlInr()));
        assertEquals(0,n("-4868.14").compareTo(d.conditionalPutSpreadFutureAtZeroPnlInr()));
        assertEquals(0,n("-155.1").compareTo(d.conditionalOutrightPutFutureAtZeroPnlInr()));
        assertTrue(d.conditionalPutSpreadFutureAtZeroPnlInr().compareTo(
                d.conditionalOutrightPutFutureAtZeroPnlInr())<0);
        neverOrders(d);
    }
    @Test void allSixIndependentRightsMustBePermitted(){
        var blocked=new Rights(true,true,true,false,true,true);
        Decision d=evaluate(held(1),future(),higher(),lower(),call(),snapshot(),
                history(),blocked,policy());
        assertEquals(Status.BLOCKED_RIGHTS,d.status());
        neverOrders(d);
    }
    @Test void existingFutureMustBeBrokerVerifiedAndLongOnly(){
        for(var p:List.of(new BrokerLongFuture(FUT,1,n("100"),BROKER,false),
                held(0),held(-1),
                new BrokerLongFuture(FUT,1,n("100"),NOW,true))){
            assertEquals(Status.BLOCKED_DATA,evaluate(p,future(),higher(),lower(),
                    call(),snapshot(),history(),rights(),policy()).status());
        }
    }
    @Test void genuineFutureInstrumentAndBrokerKeyMustMatch(){
        var f=new FutureContract("NSE_FO|OTHER",INDEX,LISTED,EXPIRY,50,true);
        assertEquals(Status.BLOCKED_DATA,evaluate(held(1),f,higher(),lower(),call(),
                snapshot(),history(),rights(),policy()).status());
        f=new FutureContract(FUT,"NSE_INDEX|OTHER",LISTED,EXPIRY,50,true);
        assertEquals(Status.BLOCKED_DATA,evaluate(held(1),f,higher(),lower(),call(),
                snapshot(),history(),rights(),policy()).status());
    }
    @Test void realOfficialExpiryAndLotMustMatchEveryLeg(){
        var bad=option(HIGH,"PE","99",EXPIRY.plusDays(7),50);
        assertEquals(Status.BLOCKED_DATA,evaluate(held(1),future(),bad,lower(),call(),
                snapshot(),history(),rights(),policy()).status());
        bad=option(HIGH,"PE","99",EXPIRY,75);
        assertEquals(Status.BLOCKED_DATA,evaluate(held(1),future(),bad,lower(),call(),
                snapshot(),history(),rights(),policy()).status());
    }
    @Test void exactPutWingAndOtmCallAreSourceListed(){
        var bad=option(HIGH,"PE","94",EXPIRY,50);
        assertEquals(Status.BLOCKED_DATA,evaluate(held(1),future(),bad,lower(),call(),
                snapshot(),history(),rights(),policy()).status());
        bad=option(CALL,"CE","99",EXPIRY,50);
        assertEquals(Status.BLOCKED_DATA,evaluate(held(1),future(),higher(),lower(),bad,
                snapshot(),history(),rights(),policy()).status());
    }
    @Test void bothPutLegsMustBeValidPeAndReferenceActualCe(){
        var bad=option(HIGH,"CE","99",EXPIRY,50);
        assertEquals(Status.BLOCKED_DATA,evaluate(held(1),future(),bad,lower(),call(),
                snapshot(),history(),rights(),policy()).status());
        bad=option(CALL,"PE","105",EXPIRY,50);
        assertEquals(Status.BLOCKED_DATA,evaluate(held(1),future(),higher(),lower(),bad,
                snapshot(),history(),rights(),policy()).status());
    }
    @Test void authenticPutCallIvSpreadIsNotAutomaticallyAProfitableSignal(){
        var tooFlat=quote(CALL,"1.5","1.55","0.25","28",10,10,SRC,EX,FIRST);
        var d=evaluate(held(1),future(),higher(),lower(),call(),
                snap(highQuote(),lowQuote(),tooFlat),history(),rights(),policy());
        assertEquals(Status.NO_HEDGE,d.status());
        assertEquals(0,d.hypotheticalPutSpreadLots());
        neverOrders(d);
    }
    @Test void sourcePutAndCallMustBeApproximatelyEqualAbsDelta(){
        var deltaMismatch=quote(CALL,"1.5","1.55","0.5","20",10,10,SRC,EX,FIRST);
        assertEquals(Status.BLOCKED_DATA,evaluate(held(1),future(),higher(),lower(),call(),
                snap(highQuote(),lowQuote(),deltaMismatch),history(),rights(),policy()).status());
    }
    @Test void historicalObservedDownMoveMustClearMinimum(){
        var d=evaluate(held(1),future(),higher(),lower(),call(),
                snapshot(),history(true),rights(),policy());
        assertEquals(Status.NO_HEDGE,d.status());
        assertEquals(0,d.hypotheticalPutSpreadLots());
        neverOrders(d);
    }
    @Test void sourceOriginalDailyClosesMustBeAtLeast35Sessions(){
        assertEquals(Status.BLOCKED_DATA,evaluate(held(1),future(),higher(),lower(),call(),
                snapshot(),history().subList(0,34),rights(),policy()).status());
    }
    @Test void noCrossProviderOrFutureKnownOriginalHistory(){
        var h=new ArrayList<>(history());
        var x=h.get(8);
        h.set(8,new CompletedClose(x.session(),"ANGEL_ONE_SMARTAPI",x.close(),
                x.exchangeCloseAt(),x.firstKnownAt(),true));
        assertEquals(Status.BLOCKED_DATA,evaluate(held(1),future(),higher(),lower(),call(),
                snapshot(),h,rights(),policy()).status());
        h=new ArrayList<>(history());
        x=h.get(8);
        h.set(8,new CompletedClose(x.session(),SRC,x.close(),
                x.exchangeCloseAt(),NOW.plusSeconds(1),true));
        assertEquals(Status.BLOCKED_DATA,evaluate(held(1),future(),higher(),lower(),call(),
                snapshot(),h,rights(),policy()).status());
    }
    @Test void boughtPutMustHaveTrueAskDepthForAllOwnedFutureLots(){
        var high=quote(HIGH,"1.95","2","-0.25","30",10,1,SRC,EX,FIRST);
        assertEquals(Status.BLOCKED_DATA,evaluate(held(2),future(),higher(),lower(),call(),
                snap(high,lowQuote(),callQuote()),history(),rights(),policy()).status());
    }
    @Test void soldPutMustHaveTrueBidDepthForAllOwnedFutureLots(){
        var low=quote(LOW,"0.8","0.85","-0.12","33",1,10,SRC,EX,FIRST);
        assertEquals(Status.BLOCKED_DATA,evaluate(held(2),future(),higher(),lower(),call(),
                snap(highQuote(),low,callQuote()),history(),rights(),policy()).status());
    }
    @Test void referenceGreeksAndMarketQuotesCannotMixProviders(){
        var other=quote(CALL,"1.5","1.55","0.25","20",10,10,
                "ANGEL_ONE_SMARTAPI",EX,FIRST);
        assertEquals(Status.BLOCKED_DATA,evaluate(held(1),future(),higher(),lower(),call(),
                snap(highQuote(),lowQuote(),other),history(),rights(),policy()).status());
    }
    @Test void futureKnownOptionQuoteAndStaleOptionQuoteBlocked(){
        var q=quote(HIGH,"1.95","2","-0.25","30",10,10,SRC,EX,NOW);
        assertEquals(Status.BLOCKED_DATA,evaluate(held(1),future(),higher(),lower(),call(),
                snap(q,lowQuote(),callQuote()),history(),rights(),policy()).status());
        q=quote(HIGH,"1.95","2","-0.25","30",10,10,
                SRC,EX.minusSeconds(200),FIRST.minusSeconds(200));
        assertEquals(Status.BLOCKED_DATA,evaluate(held(1),future(),higher(),lower(),call(),
                snap(q,lowQuote(),callQuote()),history(),rights(),policy()).status());
    }
    @Test void callOriginalDeltaMustBePositiveAndPutNegative(){
        var q=quote(CALL,"1.5","1.55","-0.25","20",10,10,SRC,EX,FIRST);
        assertEquals(Status.BLOCKED_DATA,evaluate(held(1),future(),higher(),lower(),call(),
                snap(highQuote(),lowQuote(),q),history(),rights(),policy()).status());
        q=quote(HIGH,"1.95","2","0.25","30",10,10,SRC,EX,FIRST);
        assertEquals(Status.BLOCKED_DATA,evaluate(held(1),future(),higher(),lower(),call(),
                snap(q,lowQuote(),callQuote()),history(),rights(),policy()).status());
    }
    @Test void sourceBidAskSpreadAndOiMayNeverBeInvented(){
        var q=quote(HIGH,"0.01","2","-0.25","30",10,10,SRC,EX,FIRST);
        assertEquals(Status.BLOCKED_DATA,evaluate(held(1),future(),higher(),lower(),call(),
                snap(q,lowQuote(),callQuote()),history(),rights(),policy()).status());
        q=new Quote(SRC,HIGH,n("1.95"),n("2"),n("-0.25"),n("30"),
                0,10,10,EX,FIRST,true);
        assertEquals(Status.BLOCKED_DATA,evaluate(held(1),future(),higher(),lower(),call(),
                snap(q,lowQuote(),callQuote()),history(),rights(),policy()).status());
    }
    @Test void verifiedLongFuturesMarketMustHaveRealBidAsk(){
        var s=new Snapshot(SRC,INDEX,FUT,n("100"),n("100.05"),n("100"),
                highQuote(),lowQuote(),callQuote(),EX,FIRST,true);
        assertEquals(Status.BLOCKED_DATA,evaluate(held(1),future(),higher(),lower(),call(),
                s,history(),rights(),policy()).status());
    }
    @Test void spreadCostOrLostSavingsCanSafelyBlockEvenOnSteepSkew(){
        var strict=new Policy(35,180,30,1000,100,10,300,10,3,45,2,
                n("0.06"),n("4"),100,10,10,n("5"),n("8"),n("50"));
        Decision d=evaluate(held(1),future(),higher(),lower(),call(),
                snapshot(),history(),rights(),strict);
        assertEquals(Status.NO_HEDGE,d.status());
        neverOrders(d);
        var expensiveFee=new Policy(35,180,30,1000,100,10,300,10,3,45,2,
                n("0.06"),n("4"),100,10,10,n("5"),n("50"),n("200"));
        d=evaluate(held(1),future(),higher(),lower(),call(),
                snapshot(),history(),rights(),expensiveFee);
        assertEquals(Status.NO_HEDGE,d.status());
        neverOrders(d);
    }
    @Test void shallowPutWingCanFailNetStressProtectionDespiteSkew(){
        var closer=option(HIGH,"PE","96",EXPIRY,50);
        var narrowWing=new Policy(35,180,30,1000,100,10,300,10,3,45,1,
                n("0.06"),n("4"),100,10,10,n("5"),n("8"),n("200"));
        Decision d=evaluate(held(1),future(),closer,lower(),call(),
                snapshot(),history(),rights(),narrowWing);
        assertEquals(Status.NO_HEDGE,d.status());
        assertEquals(0,d.hypotheticalPutSpreadLots());
        neverOrders(d);
    }
    @Test void allSourceConfirmedButStillNoPaperOrLiveOrders(){
        for(var d:List.of(run(),evaluate(held(1),future(),higher(),lower(),call(),
                snapshot(),history(true),rights(),policy()),
                evaluate(held(1),future(),higher(),lower(),call(),snapshot(),
                history(),new Rights(false,true,true,true,true,true),policy())))
            neverOrders(d);
    }
}
