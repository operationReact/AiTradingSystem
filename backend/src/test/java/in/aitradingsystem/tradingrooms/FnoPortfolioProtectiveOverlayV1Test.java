package in.aitradingsystem.tradingrooms;

import static org.junit.jupiter.api.Assertions.*;
import in.aitradingsystem.tradingrooms.FnoPortfolioProtectiveOverlayV1.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FnoPortfolioProtectiveOverlayV1Test {
    private static final Instant NOW=Instant.parse("2026-11-20T05:00:00Z");
    private static final Instant EX=Instant.parse("2026-11-20T04:59:20Z");
    private static final Instant KNOWN=Instant.parse("2026-11-20T04:59:24Z");
    private static final Instant BROKER=Instant.parse("2026-11-19T04:00:00Z");
    private static final String SRC="UPSTOX_ANALYTICS";
    private static final String INDEX="NSE_INDEX|Nifty 50";
    private static final String EQUITY="NSE_EQ|FIXTURE_EQUITY";
    private static final String EXTRA="NSE_EQ|FIXTURE_EXTRA";
    private static final String HIGH="NSE_FO|NIFTY_FIXTURE_99_PE";
    private static final String LOW="NSE_FO|NIFTY_FIXTURE_98_PE";
    private static final LocalDate EXPIRY=LocalDate.of(2026,11,27);
    private static BigDecimal n(String v){return new BigDecimal(v);}
    private static Policy policy(){
        return new Policy(65,180,30,500,10,300,10,10,n("5"),
                1000,100,5000,500,45);
    }
    private static Rights rights(){return new Rights(true,true,true,true,true,true);}
    private static List<Holding> held(){return List.of(new Holding(EQUITY,100,BROKER,true));}
    private static PutContract put(String key,String strike){
        return new PutContract(key,INDEX,n(strike),n("0.05"),
                LocalDate.of(2026,11,1),EXPIRY,50,true);
    }
    private static List<PutContract> contracts(){
        return List.of(put(LOW,"98"),put(HIGH,"99"));
    }
    private static PutQuote quote(String key,String bid,String ask,long depth,
                                  String provider,Instant exchange,Instant firstKnown){
        return new PutQuote(key,provider,n(bid),n(ask),n("-0.3"),n("25"),
                200,depth,exchange,firstKnown,true);
    }
    private static PutQuote lowQuote(){return quote(LOW,"0.165","0.17",10,SRC,EX,KNOWN);}
    private static PutQuote highQuote(){return quote(HIGH,"0.24","0.25",10,SRC,EX,KNOWN);}
    private static EquityMark mark(String key,String provider,Instant time,Instant known){
        return new EquityMark(key,provider,n("200"),time,known,true);
    }
    private static Snapshot snap(List<EquityMark> equities,List<PutQuote> quotes){
        return new Snapshot(SRC,INDEX,n("100"),equities,quotes,EX,KNOWN,true);
    }
    private static Snapshot snapshot(){
        return snap(List.of(mark(EQUITY,SRC,EX,KNOWN)),List.of(lowQuote(),highQuote()));
    }
    /** Only fictional deterministic fixture sessions; no actual market-data source. */
    private static List<CompletedDay> history(boolean rising,boolean flatEquity){
        var days=new ArrayList<LocalDate>();
        LocalDate date=LocalDate.of(2026,11,19);
        while(days.size()<70){
            if(date.getDayOfWeek().getValue()<=5)days.add(0,date);
            date=date.minusDays(1);
        }
        var out=new ArrayList<CompletedDay>();
        for(int i=0;i<days.size();i++){
            LocalDate d=days.get(i);
            Instant close=d.atTime(LocalTime.of(15,30))
                    .atZone(ZoneId.of("Asia/Kolkata")).toInstant();
            BigDecimal index=rising?n(Integer.toString(100+i)) : (i%2==0?n("100"):n("105"));
            BigDecimal stock=flatEquity?n("200") : (i%2==0?n("200"):n("220"));
            var equity=new EquityClose(EQUITY,SRC,stock,close,close.plusSeconds(60),true);
            out.add(new CompletedDay(d,SRC,index,close,close.plusSeconds(60),
                    List.of(equity),true));
        }
        return out;
    }
    private static List<CompletedDay> history(){return history(false,false);}
    private static Decision evaluate(List<Holding> h,List<PutContract> c,Snapshot s,
                                     List<CompletedDay> bars,Rights r,Policy p){
        return FnoPortfolioProtectiveOverlayV1.evaluate(h,c,s,bars,r,p,NOW);
    }
    private static Decision run(){
        return evaluate(held(),contracts(),snapshot(),history(),rights(),policy());
    }
    private static void noOrders(Decision d){
        assertEquals("strategy.STR-210",d.strategyId());
        assertTrue(d.cashEquityIndexBasisRiskUnmodeled());
        assertTrue(d.shockHeldToOptionExpiryAssumption());
        assertFalse(d.executable());
        assertFalse(d.orderPlaced());
        assertFalse(d.paperTradeAuthorized());
        assertFalse(d.liveTradeAuthorized());
    }

    @Test void twoVerifiedPutsComparedOverThreeSameDayPortfolioShocks(){
        Decision d=run();
        assertEquals(Status.RESEARCH_CANDIDATE,d.status());
        assertEquals(70,d.originalCompletedCloses());
        assertEquals(3,d.distinctTailScenarios());
        assertEquals(0,n("20000").compareTo(d.verifiedCurrentLongEquityValueInr()));
        assertTrue(d.worstObservedNiftyDropBps().compareTo(n("470"))>0);
        assertTrue(d.worstObservedNiftyDropBps().compareTo(n("480"))<0);
        assertEquals(HIGH,d.hypotheticalSelectedPut());
        assertEquals(4,d.hypotheticalBoughtLots()); // verified 20,000 INR / (100 index x 50 lot)
        assertTrue(d.hypotheticalPutDebitInr().signum()>0);
        assertTrue(d.sumThreeScenarioUnhedgedLossesInr()
                .compareTo(d.sumThreeScenarioAfterOverlayLossesInr())>0);
        assertTrue(d.aggregateScenarioLossReductionBps().compareTo(n("500"))>0);
        noOrders(d);
    }
    @Test void premiumIsChargedOnEveryIndependentExpiryStressAndNotionalIsCapped(){
        Decision d=run();
        assertEquals(Status.RESEARCH_CANDIDATE,d.status());
        assertEquals(HIGH,d.hypotheticalSelectedPut());
        // No hypothetical overlay greater than the entire genuine portfolio.
        assertTrue(n("100").multiply(n("50"))
                .multiply(BigDecimal.valueOf(d.hypotheticalBoughtLots()))
                .compareTo(d.verifiedCurrentLongEquityValueInr())<=0);

        // Fixture's three worst matched down sessions are 105 -> 100.
        // All three are alternative scenarios, not realized simultaneous days.
        var mc=java.math.MathContext.DECIMAL128;
        BigDecimal expiryIndex=n("100").multiply(n("100").divide(n("105"),mc),mc);
        BigDecimal oneScenarioIntrinsic=n("99").subtract(expiryIndex,mc)
                .multiply(n("50"),mc)
                .multiply(BigDecimal.valueOf(d.hypotheticalBoughtLots()),mc);
        BigDecimal expected=d.sumThreeScenarioUnhedgedLossesInr()
                .add(d.hypotheticalPutDebitInr().multiply(n("3"),mc),mc)
                .subtract(oneScenarioIntrinsic.multiply(n("3"),mc),mc);
        assertEquals(0,expected.setScale(4,java.math.RoundingMode.HALF_UP)
                .compareTo(d.sumThreeScenarioAfterOverlayLossesInr()));
        noOrders(d);
    }
    @Test void noSourcePermissionMeansNoOverlayAtAll(){
        var r=new Rights(true,true,true,false,true,true);
        Decision d=evaluate(held(),contracts(),snapshot(),history(),r,policy());
        assertEquals(Status.BLOCKED_RIGHTS,d.status());
        noOrders(d);
    }
    @Test void brokerHoldingNeedsRealPositiveLongSharesAndOriginalVerification(){
        for(Holding h:List.of(new Holding(EQUITY,0,BROKER,true),
                new Holding(EQUITY,-10,BROKER,true),
                new Holding(EQUITY,100,BROKER,false),
                new Holding(EQUITY,100,NOW,true))){
            assertEquals(Status.BLOCKED_DATA,
                    evaluate(List.of(h),contracts(),snapshot(),history(),
                            rights(),policy()).status());
        }
    }
    @Test void duplicatedHoldingMustNotDoubleCountPortfolioLoss(){
        var h=List.of(new Holding(EQUITY,100,BROKER,true),
                new Holding(EQUITY,100,BROKER,true));
        assertEquals(Status.BLOCKED_DATA,evaluate(h,contracts(),snapshot(),
                history(),rights(),policy()).status());
    }
    @Test void currentEquityQuoteMustMatchOriginalProvider(){
        var s=snap(List.of(mark(EQUITY,"ANGEL_ONE_SMARTAPI",EX,KNOWN)),
                List.of(lowQuote(),highQuote()));
        assertEquals(Status.BLOCKED_DATA,evaluate(held(),contracts(),s,
                history(),rights(),policy()).status());
    }
    @Test void futureKnownOrStaleUnderlyingMarkCannotPass(){
        var s=snap(List.of(mark(EQUITY,SRC,EX,NOW)),List.of(lowQuote(),highQuote()));
        assertEquals(Status.BLOCKED_DATA,evaluate(held(),contracts(),s,
                history(),rights(),policy()).status());
        s=snap(List.of(mark(EQUITY,SRC,EX.minusSeconds(70),KNOWN.minusSeconds(70))),
                List.of(lowQuote(),highQuote()));
        assertEquals(Status.BLOCKED_DATA,evaluate(held(),contracts(),s,
                history(),rights(),policy()).status());
    }
    @Test void everyOptionMustBeActuallyListedOtmSameExpiryAndLot(){
        var bad=put(HIGH,"101");
        assertEquals(Status.BLOCKED_DATA,evaluate(held(),List.of(put(LOW,"98"),bad),
                snapshot(),history(),rights(),policy()).status());
        bad=new PutContract(HIGH,INDEX,n("99"),n("0.05"),
                LocalDate.of(2026,11,1),EXPIRY.plusDays(7),50,true);
        assertEquals(Status.BLOCKED_DATA,evaluate(held(),List.of(put(LOW,"98"),bad),
                snapshot(),history(),rights(),policy()).status());
        bad=new PutContract(HIGH,INDEX,n("99"),n("0.05"),
                LocalDate.of(2026,11,1),EXPIRY,50,false);
        assertEquals(Status.BLOCKED_DATA,evaluate(held(),List.of(put(LOW,"98"),bad),
                snapshot(),history(),rights(),policy()).status());
    }
    @Test void candidatePutsMustShareActuallyListedOfficialLot(){
        var differentLot=new PutContract(HIGH,INDEX,n("99"),n("0.05"),
                LocalDate.of(2026,11,1),EXPIRY,75,true);
        assertEquals(Status.BLOCKED_DATA,evaluate(held(),
                List.of(put(LOW,"98"),differentLot),snapshot(),
                history(),rights(),policy()).status());
    }
    @Test void uniqueAuthenticatedPutCandidatesRequired(){
        assertEquals(Status.BLOCKED_DATA,evaluate(held(),
                List.of(put(HIGH,"99"),put(HIGH,"99")),snapshot(),
                history(),rights(),policy()).status());
        assertEquals(Status.BLOCKED_DATA,evaluate(held(),
                List.of(put(HIGH,"99")),snapshot(),
                history(),rights(),policy()).status());
    }
    @Test void missingOrExtraPutQuoteCannotBeUsed(){
        var s=snap(List.of(mark(EQUITY,SRC,EX,KNOWN)),List.of(lowQuote()));
        assertEquals(Status.BLOCKED_DATA,evaluate(held(),contracts(),s,
                history(),rights(),policy()).status());
        s=snap(List.of(mark(EQUITY,SRC,EX,KNOWN)),
                List.of(lowQuote(),lowQuote()));
        assertEquals(Status.BLOCKED_DATA,evaluate(held(),contracts(),s,
                history(),rights(),policy()).status());
    }
    @Test void trueAskDepthLimitsHypotheticalBoughtOptionLots(){
        var q=quote(HIGH,"0.24","0.25",1,SRC,EX,KNOWN);
        var s=snap(List.of(mark(EQUITY,SRC,EX,KNOWN)),List.of(lowQuote(),q));
        var d=evaluate(held(),contracts(),s,history(),rights(),policy());
        assertEquals(Status.RESEARCH_CANDIDATE,d.status());
        assertTrue(d.hypotheticalBoughtLots()<=10);
        noOrders(d);
        q=quote(HIGH,"0.24","0.25",0,SRC,EX,KNOWN);
        s=snap(List.of(mark(EQUITY,SRC,EX,KNOWN)),List.of(lowQuote(),q));
        assertEquals(Status.BLOCKED_DATA,evaluate(held(),contracts(),s,
                history(),rights(),policy()).status());
    }
    @Test void originalGreekIvAndQuoteProviderCannotBeSynthesized(){
        var q=quote(HIGH,"0.24","0.25",10,"ANGEL_ONE_SMARTAPI",EX,KNOWN);
        var s=snap(List.of(mark(EQUITY,SRC,EX,KNOWN)),List.of(lowQuote(),q));
        assertEquals(Status.BLOCKED_DATA,evaluate(held(),contracts(),s,
                history(),rights(),policy()).status());
        q=new PutQuote(HIGH,SRC,n("0.24"),n("0.25"),n("0.3"),n("25"),
                200,10,EX,KNOWN,true);
        s=snap(List.of(mark(EQUITY,SRC,EX,KNOWN)),List.of(lowQuote(),q));
        assertEquals(Status.BLOCKED_DATA,evaluate(held(),contracts(),s,
                history(),rights(),policy()).status());
    }
    @Test void sourceTimestampAndSpreadAreHardGates(){
        var q=quote(HIGH,"0.24","0.25",10,SRC,EX,NOW.plusSeconds(1));
        var s=snap(List.of(mark(EQUITY,SRC,EX,KNOWN)),List.of(lowQuote(),q));
        assertEquals(Status.BLOCKED_DATA,evaluate(held(),contracts(),s,
                history(),rights(),policy()).status());
        q=quote(HIGH,"0.01","0.25",10,SRC,EX,KNOWN);
        s=snap(List.of(mark(EQUITY,SRC,EX,KNOWN)),List.of(lowQuote(),q));
        assertEquals(Status.BLOCKED_DATA,evaluate(held(),contracts(),s,
                history(),rights(),policy()).status());
    }
    @Test void requiresSixtyFiveActualCompletedOriginalMarketSessions(){
        assertEquals(Status.BLOCKED_DATA,evaluate(held(),contracts(),
                snapshot(),history().subList(0,64),rights(),policy()).status());
    }
    @Test void missingOrCrossProviderEquityOnOneSessionIsBlocked(){
        var bars=new ArrayList<>(history());
        var b=bars.get(20);
        bars.set(20,new CompletedDay(b.session(),b.provider(),b.niftyClose(),
                b.exchangeCloseAt(),b.firstKnownAt(),List.of(),true));
        assertEquals(Status.BLOCKED_DATA,evaluate(held(),contracts(),snapshot(),
                bars,rights(),policy()).status());
        bars=new ArrayList<>(history());
        b=bars.get(20);
        var e=b.equities().get(0);
        var cross=new EquityClose(e.key(),"ANGEL_ONE_SMARTAPI",e.close(),
                e.exchangeCloseAt(),e.firstKnownAt(),true);
        bars.set(20,new CompletedDay(b.session(),b.provider(),b.niftyClose(),
                b.exchangeCloseAt(),b.firstKnownAt(),List.of(cross),true));
        assertEquals(Status.BLOCKED_DATA,evaluate(held(),contracts(),snapshot(),
                bars,rights(),policy()).status());
    }
    @Test void futureKnownHistoryNeverEntersTailRanking(){
        var bars=new ArrayList<>(history());
        var b=bars.get(20);
        bars.set(20,new CompletedDay(b.session(),b.provider(),b.niftyClose(),
                b.exchangeCloseAt(),NOW.plusSeconds(1),b.equities(),true));
        assertEquals(Status.BLOCKED_DATA,evaluate(held(),contracts(),snapshot(),
                bars,rights(),policy()).status());
    }
    @Test void cannotClaimThreeTailShocksWhenIndexOnlyRises(){
        var d=evaluate(held(),contracts(),snapshot(),history(true,false),
                rights(),policy());
        assertEquals(Status.NO_OVERLAY,d.status());
        assertEquals(0,d.hypotheticalBoughtLots());
        noOrders(d);
    }
    @Test void noPortfolioLossOnMatchedWorstIndexDaysMeansNoOverlay(){
        var d=evaluate(held(),contracts(),snapshot(),history(false,true),
                rights(),policy());
        assertEquals(Status.NO_OVERLAY,d.status());
        assertEquals(0,d.hypotheticalBoughtLots());
        noOrders(d);
    }
    @Test void sourceObservedDownsideMustBeWithinConfiguredShockBand(){
        var strict=new Policy(65,180,30,500,10,300,10,10,n("5"),
                1000,500,5000,500,45);
        var d=evaluate(held(),contracts(),snapshot(),history(),rights(),strict);
        assertEquals(Status.NO_OVERLAY,d.status());
        noOrders(d);
    }
    @Test void premiumBudgetNeverAllowsImpliedFreeProtection(){
        var strict=new Policy(65,180,30,500,10,300,10,10,n("1000"),
                1,100,5000,500,45);
        var d=evaluate(held(),contracts(),snapshot(),history(),rights(),strict);
        assertEquals(Status.NO_OVERLAY,d.status());
        assertEquals(0,d.hypotheticalBoughtLots());
        noOrders(d);
    }
    @Test void cannotAssumeExpiryIntrinsicWhenStressStrikeIsNotReached(){
        var putA=put(LOW,"90");
        var putB=put(HIGH,"91");
        var d=evaluate(held(),List.of(putA,putB),snapshot(),history(),
                rights(),policy());
        assertEquals(Status.NO_OVERLAY,d.status());
        noOrders(d);
    }
    @Test void allResearchOutcomesStayPermanentlyNonExecutable(){
        var cases=List.of(run(),evaluate(held(),contracts(),snapshot(),
                history(true,false),rights(),policy()),evaluate(held(),
                contracts(),snapshot(),history(),new Rights(false,true,true,true,true,true),
                policy()));
        cases.forEach(FnoPortfolioProtectiveOverlayV1Test::noOrders);
    }
}
