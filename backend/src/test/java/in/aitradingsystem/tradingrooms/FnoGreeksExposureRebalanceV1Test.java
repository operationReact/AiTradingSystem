package in.aitradingsystem.tradingrooms;

import static org.junit.jupiter.api.Assertions.*;
import in.aitradingsystem.tradingrooms.FnoGreeksExposureRebalanceV1.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FnoGreeksExposureRebalanceV1Test {
    private static final Instant NOW=Instant.parse("2026-11-20T05:00:00Z");
    private static final Instant EX=Instant.parse("2026-11-20T04:59:20Z");
    private static final Instant FIRST=Instant.parse("2026-11-20T04:59:24Z");
    private static final Instant BOOK_AT=Instant.parse("2026-11-19T08:00:00Z");
    private static final Instant POS_AT=Instant.parse("2026-11-19T07:00:00Z");
    private static final String SRC="UPSTOX_ANALYTICS";
    private static final String INDEX="NSE_INDEX|Nifty 50";
    private static final String OLD_CALL="NSE_FO|NIFTY_OLD_CE";
    private static final String OLD_PUT="NSE_FO|NIFTY_OLD_PE";
    private static final String BUY_PUT="NSE_FO|NIFTY_BUY_PE";
    private static final String BUY_CALL="NSE_FO|NIFTY_BUY_CE";
    private static BigDecimal n(String x){return new BigDecimal(x);}
    private static Rights rights(){return new Rights(true,true,true,true,true,true);}
    private static Policy policy(){
        return new Policy(35,180,30,1000,10,300,10,10,5,50,
                n("10"),n("100"),n("20"),n("250"),n("130"),
                500,10,n("5"),n("500"));
    }
    private static Contract contract(String key,String type,String strike,LocalDate expiry,
                                     int lots){
        return new Contract(key,INDEX,type,n(strike),n("0.05"),
                LocalDate.of(2026,11,1),expiry,lots,true);
    }
    private static Contract oldCall(){
        return contract(OLD_CALL,"CE","105",LocalDate.of(2026,11,27),50);
    }
    private static Contract oldPut(){
        return contract(OLD_PUT,"PE","95",LocalDate.of(2026,11,27),50);
    }
    private static Contract buyPut(){
        return contract(BUY_PUT,"PE","95",LocalDate.of(2026,12,4),50);
    }
    private static Contract buyCall(){
        return contract(BUY_CALL,"CE","105",LocalDate.of(2026,12,4),50);
    }
    private static Position pos(Contract c,int lots){
        return new Position(c,lots,POS_AT,true);
    }
    private static BrokerBook book(){
        return new BrokerBook(true,BOOK_AT,List.of(pos(oldCall(),2),pos(oldPut(),-1)));
    }
    private static List<Contract> candidates(){return List.of(buyPut(),buyCall());}
    private static Quote quote(String key,String delta,String gamma,String vega,
                               String theta,String bid,String ask,long depth,
                               String provider,Instant exchange,Instant known){
        return new Quote(key,provider,n(bid),n(ask),n(delta),n(gamma),
                n(vega),n(theta),n("25"),1000,depth,depth,exchange,known,true);
    }
    private static Quote oldCallQuote(){
        return quote(OLD_CALL,"0.6","0.03","0.8","-0.5",
                "3","3.05",10,SRC,EX,FIRST);
    }
    private static Quote oldPutQuote(){
        return quote(OLD_PUT,"-0.3","0.02","0.6","-0.4",
                "2","2.05",10,SRC,EX,FIRST);
    }
    private static Quote putQuote(){
        return quote(BUY_PUT,"-0.45","0.04","1.2","-0.6",
                "1.95","2",10,SRC,EX,FIRST);
    }
    private static Quote callQuote(){
        return quote(BUY_CALL,"0.3","0.03","0.9","-0.4",
                "1.45","1.5",10,SRC,EX,FIRST);
    }
    private static Snapshot snap(List<Quote> quotes){
        return new Snapshot(SRC,INDEX,n("100"),quotes,EX,FIRST,true);
    }
    private static Snapshot snapshot(){
        return snap(List.of(oldCallQuote(),oldPutQuote(),putQuote(),callQuote()));
    }
    private static List<CompletedClose> history(boolean flat){
        var dates=new ArrayList<LocalDate>();
        LocalDate d=LocalDate.of(2026,11,19);
        while(dates.size()<35){
            if(d.getDayOfWeek().getValue()<=5)dates.add(0,d);
            d=d.minusDays(1);
        }
        var result=new ArrayList<CompletedClose>();
        for(int i=0;i<dates.size();i++){
            LocalDate day=dates.get(i);
            Instant close=day.atTime(LocalTime.of(15,30))
                    .atZone(ZoneId.of("Asia/Kolkata")).toInstant();
            result.add(new CompletedClose(day,SRC,flat?n("100"):i%2==0?n("100"):n("103"),
                    close,close.plusSeconds(60),true));
        }
        return result;
    }
    private static List<CompletedClose> history(){return history(false);}
    private static Decision eval(BrokerBook b,List<Contract> c,Snapshot s,
                                 List<CompletedClose> h,Rights r,Policy p){
        return FnoGreeksExposureRebalanceV1.evaluate(b,c,s,h,r,p,NOW);
    }
    private static Decision run(){
        return eval(book(),candidates(),snapshot(),history(),rights(),policy());
    }
    private static void noOrders(Decision d){
        assertEquals("strategy.STR-211",d.strategyId());
        assertTrue(d.currentGreekLinearizationOnly());
        assertFalse(d.executable());
        assertFalse(d.orderPlaced());
        assertFalse(d.paperTradeAuthorized());
        assertFalse(d.liveTradeAuthorized());
    }

    @Test void authenticExistingBookAndBuyOnlyPutReduceDeltaWithinGreekCaps(){
        Decision d=run();
        assertEquals(Status.RESEARCH_CANDIDATE,d.status());
        assertEquals(35,d.completedOriginalCloses());
        assertEquals(2,d.verifiedExistingContractCount());
        assertEquals(0,n("75").compareTo(d.originalNetDeltaUnits()));
        assertEquals(0,n("2").compareTo(d.originalNetGammaUnitsPerIndexPoint()));
        assertEquals(0,n("50").compareTo(d.originalNetVegaInrPerIvPoint()));
        assertEquals(0,n("-30").compareTo(d.originalNetThetaInrPerCalendarDay()));
        assertEquals(BUY_PUT,d.hypotheticalBuyOptionKey());
        assertEquals(3,d.hypotheticalLongBuyLots());
        assertEquals(0,n("305.3").compareTo(d.hypotheticalBuyPremiumAndFeesInr()));
        assertEquals(0,n("7.5").compareTo(d.hypotheticalResidualDeltaUnits()));
        assertEquals(0,n("8").compareTo(d.hypotheticalResidualGammaUnitsPerIndexPoint()));
        assertEquals(0,n("230").compareTo(d.hypotheticalResidualVegaInrPerIvPoint()));
        assertEquals(0,n("-120").compareTo(d.hypotheticalResidualThetaInrPerCalendarDay()));
        assertEquals(0,n("9000").compareTo(d.hypotheticalAbsDeltaReductionBps()));
        noOrders(d);
    }
    @Test void allResearchRightsMustBeSeparatelyVerified(){
        var r=new Rights(true,true,true,false,true,true);
        Decision d=eval(book(),candidates(),snapshot(),history(),r,policy());
        assertEquals(Status.BLOCKED_RIGHTS,d.status());
        noOrders(d);
    }
    @Test void sourceMustAttestCompleteRealOptionsBook(){
        var b=new BrokerBook(false,BOOK_AT,book().positions());
        assertEquals(Status.BLOCKED_DATA,eval(b,candidates(),snapshot(),history(),
                rights(),policy()).status());
        b=new BrokerBook(true,BOOK_AT,List.of());
        assertEquals(Status.BLOCKED_DATA,eval(b,candidates(),snapshot(),history(),
                rights(),policy()).status());
    }
    @Test void unverifiedBrokerLegAndSignlessPositionFailClosed(){
        var b=new BrokerBook(true,BOOK_AT,List.of(new Position(oldCall(),2,POS_AT,false),
                pos(oldPut(),-1)));
        assertEquals(Status.BLOCKED_DATA,eval(b,candidates(),snapshot(),history(),
                rights(),policy()).status());
        b=new BrokerBook(true,BOOK_AT,List.of(pos(oldCall(),0),pos(oldPut(),-1)));
        assertEquals(Status.BLOCKED_DATA,eval(b,candidates(),snapshot(),history(),
                rights(),policy()).status());
    }
    @Test void brokerPositionAndBookFirstKnownMustPredateMarketSnapshot(){
        var b=new BrokerBook(true,BOOK_AT,List.of(
                new Position(oldCall(),2,NOW,true),pos(oldPut(),-1)));
        assertEquals(Status.BLOCKED_DATA,eval(b,candidates(),snapshot(),history(),
                rights(),policy()).status());
        b=new BrokerBook(true,NOW,book().positions());
        assertEquals(Status.BLOCKED_DATA,eval(b,candidates(),snapshot(),history(),
                rights(),policy()).status());
    }
    @Test void duplicatedOrNonNiftyOrUnlistedOptionLegsAreNeverTrusted(){
        var b=new BrokerBook(true,BOOK_AT,List.of(pos(oldCall(),2),pos(oldCall(),1)));
        assertEquals(Status.BLOCKED_DATA,eval(b,candidates(),snapshot(),history(),
                rights(),policy()).status());
        var invalid=new Contract(OLD_CALL,"NSE_INDEX|Other","CE",n("105"),n("0.05"),
                LocalDate.of(2026,11,1),LocalDate.of(2026,11,27),50,true);
        b=new BrokerBook(true,BOOK_AT,List.of(pos(invalid,2),pos(oldPut(),-1)));
        assertEquals(Status.BLOCKED_DATA,eval(b,candidates(),snapshot(),history(),
                rights(),policy()).status());
    }
    @Test void requireExactlyOneAuthenticCandidatePutAndCall(){
        assertEquals(Status.BLOCKED_DATA,eval(book(),List.of(buyPut(),buyPut()),
                snapshot(),history(),rights(),policy()).status());
        assertEquals(Status.BLOCKED_DATA,eval(book(),List.of(buyPut()),
                snapshot(),history(),rights(),policy()).status());
        var twoCalls=contract(BUY_PUT,"CE","105",LocalDate.of(2026,12,4),50);
        assertEquals(Status.BLOCKED_DATA,eval(book(),List.of(twoCalls,buyCall()),
                snapshot(),history(),rights(),policy()).status());
    }
    @Test void candidateMasterExpiryAndOfficialLotMustMatch(){
        var wrong=contract(BUY_CALL,"CE","105",LocalDate.of(2026,12,11),50);
        assertEquals(Status.BLOCKED_DATA,eval(book(),List.of(buyPut(),wrong),
                snapshot(),history(),rights(),policy()).status());
        wrong=contract(BUY_CALL,"CE","105",LocalDate.of(2026,12,4),75);
        assertEquals(Status.BLOCKED_DATA,eval(book(),List.of(buyPut(),wrong),
                snapshot(),history(),rights(),policy()).status());
    }
    @Test void candidateMustActuallyBeOutOfMoneyAndTickAligned(){
        var wrong=contract(BUY_PUT,"PE","101",LocalDate.of(2026,12,4),50);
        assertEquals(Status.BLOCKED_DATA,eval(book(),List.of(wrong,buyCall()),
                snapshot(),history(),rights(),policy()).status());
        wrong=contract(BUY_PUT,"PE","95.01",LocalDate.of(2026,12,4),50);
        assertEquals(Status.BLOCKED_DATA,eval(book(),List.of(wrong,buyCall()),
                snapshot(),history(),rights(),policy()).status());
    }
    @Test void originalQuoteBookMustExactlyMatchBothPositionsAndBothCandidates(){
        assertEquals(Status.BLOCKED_DATA,eval(book(),candidates(),snap(List.of(
                oldCallQuote(),oldPutQuote(),putQuote())),history(),rights(),policy()).status());
        assertEquals(Status.BLOCKED_DATA,eval(book(),candidates(),snap(List.of(
                oldCallQuote(),oldPutQuote(),putQuote(),putQuote())),
                history(),rights(),policy()).status());
    }
    @Test void sourceOriginalGreekProviderCannotMixBrokers(){
        Quote p=quote(BUY_PUT,"-0.45","0.04","1.2","-0.6",
                "1.95","2",10,"ANGEL_ONE_SMARTAPI",EX,FIRST);
        assertEquals(Status.BLOCKED_DATA,eval(book(),candidates(),snap(List.of(
                oldCallQuote(),oldPutQuote(),p,callQuote())),
                history(),rights(),policy()).status());
    }
    @Test void wrongSignOrAbsentGammaVegaThetaIsBlocked(){
        var wrong=quote(BUY_PUT,"0.45","0.04","1.2","-0.6",
                "1.95","2",10,SRC,EX,FIRST);
        assertEquals(Status.BLOCKED_DATA,eval(book(),candidates(),snap(List.of(
                oldCallQuote(),oldPutQuote(),wrong,callQuote())),
                history(),rights(),policy()).status());
        var absent=new Quote(BUY_PUT,SRC,n("1.95"),n("2"),n("-0.45"),
                null,n("1.2"),n("-0.6"),n("25"),1000,10,10,EX,FIRST,true);
        assertEquals(Status.BLOCKED_DATA,eval(book(),candidates(),snap(List.of(
                oldCallQuote(),oldPutQuote(),absent,callQuote())),
                history(),rights(),policy()).status());
        absent=new Quote(BUY_PUT,SRC,n("1.95"),n("2"),n("-0.45"),
                n("0.04"),n("1.2"),null,n("25"),1000,10,10,EX,FIRST,true);
        assertEquals(Status.BLOCKED_DATA,eval(book(),candidates(),snap(List.of(
                oldCallQuote(),oldPutQuote(),absent,callQuote())),
                history(),rights(),policy()).status());
    }
    @Test void originalGreekQuoteMustHaveTrueBidAskOiAndIv(){
        var quote=quote(BUY_PUT,"-0.45","0.04","1.2","-0.6",
                "0.01","2",10,SRC,EX,FIRST);
        assertEquals(Status.BLOCKED_DATA,eval(book(),candidates(),snap(List.of(
                oldCallQuote(),oldPutQuote(),quote,callQuote())),
                history(),rights(),policy()).status());
        quote=new Quote(BUY_PUT,SRC,n("1.95"),n("2"),n("-0.45"),
                n("0.04"),n("1.2"),n("-0.6"),n("25"),0,10,10,EX,FIRST,true);
        assertEquals(Status.BLOCKED_DATA,eval(book(),candidates(),snap(List.of(
                oldCallQuote(),oldPutQuote(),quote,callQuote())),
                history(),rights(),policy()).status());
    }
    @Test void futureKnownOrStaleQuoteMustFailClosed(){
        var quote=quote(BUY_PUT,"-0.45","0.04","1.2","-0.6",
                "1.95","2",10,SRC,EX,NOW);
        assertEquals(Status.BLOCKED_DATA,eval(book(),candidates(),snap(List.of(
                oldCallQuote(),oldPutQuote(),quote,callQuote())),
                history(),rights(),policy()).status());
        quote=quote(BUY_PUT,"-0.45","0.04","1.2","-0.6",
                "1.95","2",10,SRC,EX.minusSeconds(200),FIRST.minusSeconds(200));
        assertEquals(Status.BLOCKED_DATA,eval(book(),candidates(),snap(List.of(
                oldCallQuote(),oldPutQuote(),quote,callQuote())),
                history(),rights(),policy()).status());
    }
    @Test void realAskDepthCapsHypotheticalPurchaseLots(){
        var quote=quote(BUY_PUT,"-0.45","0.04","1.2","-0.6",
                "1.95","2",1,SRC,EX,FIRST);
        Decision d=eval(book(),candidates(),snap(List.of(
                oldCallQuote(),oldPutQuote(),quote,callQuote())),history(),rights(),policy());
        assertEquals(Status.RESEARCH_CANDIDATE,d.status());
        assertEquals(1,d.hypotheticalLongBuyLots());
        noOrders(d);
        quote=quote(BUY_PUT,"-0.45","0.04","1.2","-0.6",
                "1.95","2",0,SRC,EX,FIRST);
        assertEquals(Status.BLOCKED_DATA,eval(book(),candidates(),snap(List.of(
                oldCallQuote(),oldPutQuote(),quote,callQuote())),
                history(),rights(),policy()).status());
    }
    @Test void sourceHistoryMustHaveAtLeast35OriginallyCompletedSessions(){
        assertEquals(Status.BLOCKED_DATA,eval(book(),candidates(),snapshot(),
                history().subList(0,34),rights(),policy()).status());
    }
    @Test void noFutureKnownOrCrossProviderOriginalDailyIndexCloses(){
        var h=new ArrayList<>(history());
        var b=h.get(4);
        h.set(4,new CompletedClose(b.session(),SRC,b.indexClose(),
                b.exchangeCloseAt(),NOW.plusSeconds(1),true));
        assertEquals(Status.BLOCKED_DATA,eval(book(),candidates(),snapshot(),
                h,rights(),policy()).status());
        h=new ArrayList<>(history());
        b=h.get(4);
        h.set(4,new CompletedClose(b.session(),"ANGEL_ONE_SMARTAPI",b.indexClose(),
                b.exchangeCloseAt(),b.firstKnownAt(),true));
        assertEquals(Status.BLOCKED_DATA,eval(book(),candidates(),snapshot(),
                h,rights(),policy()).status());
    }
    @Test void noOriginalMarketMovementMeansNoGreekRebalance(){
        Decision d=eval(book(),candidates(),snapshot(),history(true),rights(),policy());
        assertEquals(Status.NO_REBALANCE,d.status());
        assertEquals(0,d.hypotheticalLongBuyLots());
        noOrders(d);
    }
    @Test void gammaVegaAndThetaConstraintsCanRejectBuyingEvenWithDeltaBenefits(){
        var vegaCap=new Policy(35,180,30,1000,10,300,10,10,5,50,
                n("10"),n("100"),n("20"),n("100"),n("130"),
                500,10,n("5"),n("500"));
        assertEquals(Status.NO_REBALANCE,eval(book(),candidates(),snapshot(),
                history(),rights(),vegaCap).status());
        var thetaCap=new Policy(35,180,30,1000,10,300,10,10,5,50,
                n("10"),n("100"),n("20"),n("250"),n("50"),
                500,10,n("5"),n("500"));
        assertEquals(Status.NO_REBALANCE,eval(book(),candidates(),snapshot(),
                history(),rights(),thetaCap).status());
    }
    @Test void highActualAskPlusFeesCannotBeCalledAffordable(){
        var cap=new Policy(35,180,30,1000,10,300,10,10,5,50,
                n("10"),n("100"),n("20"),n("250"),n("130"),
                500,10,n("5"),n("50"));
        var d=eval(book(),candidates(),snapshot(),history(),rights(),cap);
        assertEquals(Status.NO_REBALANCE,d.status());
        noOrders(d);
    }
    @Test void minimumDeltaReductionOrTargetTightnessMayFailSafely(){
        var tough=new Policy(35,180,30,1000,10,300,10,10,5,50,
                n("100"),n("100"),n("20"),n("250"),n("130"),
                500,10,n("5"),n("500"));
        Decision d=eval(book(),candidates(),snapshot(),history(),rights(),tough);
        assertEquals(Status.NO_REBALANCE,d.status());
        noOrders(d);
        tough=new Policy(35,180,30,1000,10,300,10,10,5,50,
                n("10"),n("5"),n("20"),n("250"),n("130"),
                500,10,n("5"),n("500"));
        d=eval(book(),candidates(),snapshot(),history(),rights(),tough);
        assertEquals(Status.NO_REBALANCE,d.status());
        noOrders(d);
    }
    @Test void allPossibleOutcomesAlwaysDenyTradingRights(){
        for(Decision d:List.of(run(),eval(book(),candidates(),snapshot(),
                    history(true),rights(),policy()),eval(book(),candidates(),
                    snapshot(),history(),new Rights(false,true,true,true,true,true),policy())))
            noOrders(d);
    }
}
