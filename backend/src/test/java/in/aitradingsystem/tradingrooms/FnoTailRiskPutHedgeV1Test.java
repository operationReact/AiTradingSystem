package in.aitradingsystem.tradingrooms;

import static org.junit.jupiter.api.Assertions.*;
import static in.aitradingsystem.tradingrooms.FnoTailRiskPutHedgeV1.INDEX;
import in.aitradingsystem.tradingrooms.FnoTailRiskPutHedgeV1.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FnoTailRiskPutHedgeV1Test {
    private static final Instant NOW = Instant.parse("2026-11-20T05:00:00Z");
    private static final Instant QUOTED = Instant.parse("2026-11-20T04:59:20Z");
    private static final Instant KNOWN = Instant.parse("2026-11-20T04:59:24Z");
    private static final Instant BROKER = Instant.parse("2026-11-19T04:00:00Z");
    private static final String PROVIDER = "UPSTOX_ANALYTICS";
    private static final String STOCK = "NSE_EQ|FICTIONAL_A";
    private static final String STOCK_B = "NSE_EQ|FICTIONAL_B";
    private static final String PUT_KEY = "NSE_FO|NIFTY_20261127_99_PE";
    private static BigDecimal n(String v) { return new BigDecimal(v); }

    private static Policy policy() {
        return new Policy(65,180,60,500,10,300,20,10,1000,100,5000,500,n("5"));
    }
    private static Rights rights() { return new Rights(true,true,true,true,true,true); }
    private static List<Holding> owned() {
        return List.of(new Holding(STOCK,100,BROKER,true));
    }
    private static PutContract contract() {
        return new PutContract(PUT_KEY,INDEX,n("99"),n("0.05"),
                LocalDate.of(2026,11,1),LocalDate.of(2026,11,27),50,true);
    }
    private static PutQuote putQuote(String provider, String delta, long depth,
                                     Instant at, Instant firstKnown) {
        return new PutQuote(PUT_KEY,provider,n("0.099"),n("0.100"),n(delta),
                n("25"),500,depth,at,firstKnown,true);
    }
    private static EquityQuote currentEquity(String key,String provider,
                                             String price,Instant quoted,
                                             Instant known) {
        return new EquityQuote(key,provider,n(price),quoted,known,true);
    }
    private static Snapshot snap(List<EquityQuote> equities,PutQuote quote) {
        return new Snapshot(PROVIDER,INDEX,n("100"),equities,quote,QUOTED,KNOWN,true);
    }
    private static Snapshot snapshot(long depth) {
        return snap(List.of(currentEquity(STOCK,PROVIDER,"200",QUOTED,KNOWN)),
                putQuote(PROVIDER,"-0.3",depth,QUOTED,KNOWN));
    }
    private static Snapshot snapshot() { return snapshot(10); }
    private static Instant closeAt(LocalDate date) {
        return date.atTime(LocalTime.of(15,30))
                .atZone(ZoneId.of("Asia/Kolkata")).toInstant();
    }
    private static CompletedSession session(LocalDate date,String indexPrice,
                                            String equityPrice,boolean twoEquities) {
        Instant close = closeAt(date);
        var index = new IndexClose(date,PROVIDER,n(indexPrice),
                close,close.plusSeconds(60),true);
        var equity = new ArrayList<EquityClose>();
        equity.add(new EquityClose(STOCK,PROVIDER,n(equityPrice),
                close,close.plusSeconds(60),true));
        if (twoEquities) equity.add(new EquityClose(STOCK_B,PROVIDER,
                n(equityPrice),close,close.plusSeconds(60),true));
        return new CompletedSession(index,equity);
    }
    /** All numbers and instrument keys below are synthetic unit-test fixtures. */
    private static List<CompletedSession> history(boolean upOnly,
                                                   boolean flatEquities,
                                                   boolean twoEquities) {
        var sessions = new ArrayList<LocalDate>();
        LocalDate day=LocalDate.of(2026,11,19);
        while(sessions.size()<70){
            if(day.getDayOfWeek().getValue()<=5) sessions.add(0,day);
            day=day.minusDays(1);
        }
        var result=new ArrayList<CompletedSession>();
        for(int i=0;i<sessions.size();i++){
            String idx=upOnly?Integer.toString(100+i):((i%2)==0?"100":"102");
            String eq=flatEquities?"200":(upOnly?Integer.toString(200+2*i):
                    ((i%2)==0?"200":"204"));
            result.add(session(sessions.get(i),idx,eq,twoEquities));
        }
        return result;
    }
    private static List<CompletedSession> history() { return history(false,false,false); }
    private static Decision eval() {
        return run(owned(),contract(),snapshot(),history(),rights(),policy(),NOW);
    }
    private static Decision run(List<Holding> h,PutContract p,Snapshot s,
                                List<CompletedSession> history,Rights r,Policy rules,
                                Instant asOf) {
        return FnoTailRiskPutHedgeV1.evaluate(h,p,s,history,r,rules,asOf);
    }
    private static void noOrders(Decision d) {
        assertEquals("strategy.STR-206",d.strategyId());
        assertTrue(d.hindsightReplayOnly());
        assertFalse(d.executable());
        assertFalse(d.orderPlaced());
        assertFalse(d.paperTradeAuthorized());
        assertFalse(d.liveTradeAuthorized());
    }

    @Test void historicalWorstSameDayReplayProducesOnlyHypotheticalLongPut() {
        Decision d=eval();
        assertEquals(Status.RESEARCH_CANDIDATE,d.status());
        assertEquals(69,d.completedReturnObservations());
        assertTrue(d.worstObservedIndexDropBps().compareTo(n("190"))>0);
        assertTrue(d.worstObservedIndexDropBps().compareTo(n("200"))<0);
        assertEquals(0,n("20000").compareTo(d.currentPortfolioValueInr()));
        assertTrue(d.scenarioIndexAtHypotheticalExpiry().compareTo(n("99"))<0);
        assertTrue(d.hypotheticalUnhedgedPortfolioLossInr().signum()>0);
        assertEquals(10,d.hypotheticalBoughtPutLots());
        assertTrue(d.hypotheticalPutPurchaseDebitInr().signum()>0);
        assertTrue(d.hypotheticalPutIntrinsicAtExpiryInr()
                .compareTo(d.hypotheticalPutPurchaseDebitInr())>0);
        assertEquals(0, d.hypotheticalResidualScenarioLossInr().signum());
        noOrders(d);
    }
    @Test void minimumRightsRequiredEvenForRetrospectiveResearch() {
        Decision d=run(owned(),contract(),snapshot(),history(),
                new Rights(true,true,true,false,true,true),policy(),NOW);
        assertEquals(Status.BLOCKED_RIGHTS,d.status());
        noOrders(d);
    }
    @Test void brokerSharesMustBePositiveVerifiedAndKnownBeforeSnapshot() {
        for(Holding bad:List.of(
                new Holding(STOCK,100,BROKER,false),
                new Holding(STOCK,100,NOW,true),
                new Holding(STOCK,-20,BROKER,true))) {
            Decision d=run(List.of(bad),contract(),snapshot(),history(),rights(),policy(),NOW);
            assertEquals(Status.BLOCKED_DATA,d.status());
            noOrders(d);
        }
    }
    @Test void onlyLicensedNseEquityIdentitiesAndUniqueKeys() {
        Decision d=run(List.of(new Holding("XYZ",100,BROKER,true)),
                contract(),snapshot(),history(),rights(),policy(),NOW);
        assertEquals(Status.BLOCKED_DATA,d.status());
        d=run(List.of(new Holding(STOCK,100,BROKER,true),
                            new Holding(STOCK,100,BROKER,true)),
                contract(),snapshot(),history(),rights(),policy(),NOW);
        assertEquals(Status.BLOCKED_DATA,d.status());
    }
    @Test void equityQuoteProviderMismatchFailsEvenWithValidIndex() {
        var q=currentEquity(STOCK,"ANGEL_ONE_SMARTAPI","200",QUOTED,KNOWN);
        Decision d=run(owned(),contract(),snap(List.of(q),
                putQuote(PROVIDER,"-0.3",10,QUOTED,KNOWN)),
                history(),rights(),policy(),NOW);
        assertEquals(Status.BLOCKED_DATA,d.status());
    }
    @Test void originalPutQuoteProviderAndDeltaSignMustBeAuthentic() {
        var current=List.of(currentEquity(STOCK,PROVIDER,"200",QUOTED,KNOWN));
        var s=snap(current,putQuote("ANGEL_ONE_SMARTAPI","-0.3",10,QUOTED,KNOWN));
        assertEquals(Status.BLOCKED_DATA,run(owned(),contract(),s,history(),
                rights(),policy(),NOW).status());
        s=snap(current,putQuote(PROVIDER,"0.3",10,QUOTED,KNOWN));
        assertEquals(Status.BLOCKED_DATA,run(owned(),contract(),s,history(),
                rights(),policy(),NOW).status());
    }
    @Test void futureKnownOrStaleSourceObservationFailsClosed() {
        var current=List.of(currentEquity(STOCK,PROVIDER,"200",QUOTED,KNOWN));
        var s=snap(current,putQuote(PROVIDER,"-0.3",10,QUOTED,NOW.plusSeconds(1)));
        assertEquals(Status.BLOCKED_DATA,run(owned(),contract(),s,history(),
                rights(),policy(),NOW).status());
        s=snap(current,putQuote(PROVIDER,"-0.3",10,QUOTED.minusSeconds(400),KNOWN));
        assertEquals(Status.BLOCKED_DATA,run(owned(),contract(),s,history(),
                rights(),policy(),NOW).status());
    }
    @Test void genuinePutAskDepthIsHardConstraintAndNotSynthetic() {
        var d=run(owned(),contract(),snapshot(1),history(),rights(),policy(),NOW);
        assertEquals(Status.RESEARCH_CANDIDATE,d.status());
        assertEquals(1,d.hypotheticalBoughtPutLots());
        noOrders(d);
        assertEquals(Status.BLOCKED_DATA,run(owned(),contract(),snapshot(0),
                history(),rights(),policy(),NOW).status());
    }
    @Test void noIntrinsicAtExpiryUnderChosenShockMeansNoHedge() {
        var put=new PutContract(PUT_KEY,INDEX,n("98"),n("0.05"),
                LocalDate.of(2026,11,1),LocalDate.of(2026,11,27),50,true);
        var d=run(owned(),put,snapshot(),history(),rights(),policy(),NOW);
        assertEquals(Status.NO_HEDGE,d.status());
        assertEquals("TAIL_PUT_SCENARIO_STRIKE_NOT_REACHED",d.reasonCode());
        assertEquals(0,d.hypotheticalBoughtPutLots());
        noOrders(d);
    }
    @Test void realPremiumCapPreventsUnaffordableResearchLots() {
        var strict=new Policy(65,180,60,500,10,300,20,10,1,100,5000,500,n("5"));
        var d=run(owned(),contract(),snapshot(),history(),rights(),strict,NOW);
        assertEquals(Status.NO_HEDGE,d.status());
        assertEquals(0,d.hypotheticalBoughtPutLots());
        noOrders(d);
    }
    @Test void insufficientStressProtectionIsNoHedgeNotCandidate() {
        var strict=new Policy(65,180,60,500,10,300,20,10,1000,100,5000,9900,n("5"));
        var d=run(owned(),contract(),snapshot(1),history(),rights(),strict,NOW);
        assertEquals(Status.NO_HEDGE,d.status());
        noOrders(d);
    }
    @Test void tailShockThresholdMustBeBasedOnObservedSourceReturns() {
        var strict=new Policy(65,180,60,500,10,300,20,10,1000,300,5000,500,n("5"));
        var d=run(owned(),contract(),snapshot(),history(),rights(),strict,NOW);
        assertEquals(Status.NO_HEDGE,d.status());
        assertEquals("TAIL_PUT_OBSERVED_SHOCK_OUTSIDE_POLICY",d.reasonCode());
        noOrders(d);
    }
    @Test void cannotCallMonotonicallyRisingIndexACrash() {
        var d=run(owned(),contract(),snapshot(),history(true,false,false),
                rights(),policy(),NOW);
        assertEquals(Status.NO_HEDGE,d.status());
        assertEquals(0,d.hypotheticalBoughtPutLots());
        noOrders(d);
    }
    @Test void historicalBasketThatDidNotLoseOnNiftyWorstDayNeedsNoHedge() {
        var d=run(owned(),contract(),snapshot(),history(false,true,false),
                rights(),policy(),NOW);
        assertEquals(Status.NO_HEDGE,d.status());
        assertEquals("TAIL_PUT_HISTORICAL_PORTFOLIO_PROXY_NOT_LOSS",d.reasonCode());
        noOrders(d);
    }
    @Test void requiresSixtyPlusOriginalFullyCompletedHistoricalReturns() {
        var d=run(owned(),contract(),snapshot(),history().subList(0,60),
                rights(),policy(),NOW);
        assertEquals(Status.BLOCKED_DATA,d.status());
    }
    @Test void noForwardKnownCloseOrCrossProviderDayMaySneakIn() {
        var series=new ArrayList<>(history());
        var original=series.get(20);
        var equity=original.equities().get(0);
        series.set(20,new CompletedSession(original.index(),List.of(
                new EquityClose(STOCK,PROVIDER,equity.price(),
                        equity.exchangeCloseAt(),NOW.plusSeconds(1),true))));
        assertEquals(Status.BLOCKED_DATA,run(owned(),contract(),snapshot(),series,
                rights(),policy(),NOW).status());

        series=new ArrayList<>(history());
        original=series.get(20);
        equity=original.equities().get(0);
        series.set(20,new CompletedSession(original.index(),List.of(
                new EquityClose(STOCK,"ANGEL_ONE_SMARTAPI",equity.price(),
                        equity.exchangeCloseAt(),equity.firstKnownAt(),true))));
        assertEquals(Status.BLOCKED_DATA,run(owned(),contract(),snapshot(),series,
                rights(),policy(),NOW).status());
    }
    @Test void missingOrDuplicatedSecuritySessionIsHardBlocked() {
        var series=new ArrayList<>(history());
        var old=series.get(2);
        series.set(2,new CompletedSession(old.index(),List.of()));
        assertEquals(Status.BLOCKED_DATA,run(owned(),contract(),snapshot(),series,
                rights(),policy(),NOW).status());
        series=new ArrayList<>(history());
        old=series.get(2);
        series.set(2,new CompletedSession(old.index(),List.of(
                old.equities().get(0),old.equities().get(0))));
        assertEquals(Status.BLOCKED_DATA,run(owned(),contract(),snapshot(),series,
                rights(),policy(),NOW).status());
    }
    @Test void optionMustBeAuthenticOutOfMoneyUnexpiredAndTickAligned() {
        var itm=new PutContract(PUT_KEY,INDEX,n("101"),n("0.05"),
                LocalDate.of(2026,11,1),LocalDate.of(2026,11,27),50,true);
        assertEquals(Status.BLOCKED_DATA,run(owned(),itm,snapshot(),history(),
                rights(),policy(),NOW).status());
        var tick=new PutContract(PUT_KEY,INDEX,n("99.01"),n("0.05"),
                LocalDate.of(2026,11,1),LocalDate.of(2026,11,27),50,true);
        assertEquals(Status.BLOCKED_DATA,run(owned(),tick,snapshot(),history(),
                rights(),policy(),NOW).status());
        var exp=new PutContract(PUT_KEY,INDEX,n("99"),n("0.05"),
                LocalDate.of(2026,11,1),LocalDate.of(2026,11,21),50,true);
        assertEquals(Status.BLOCKED_DATA,run(owned(),exp,snapshot(),history(),
                rights(),policy(),NOW).status());
    }
    @Test void multipleBrokerHoldingsUseTheSameObservedTailSession() {
        var holdings=List.of(new Holding(STOCK,100,BROKER,true),
                new Holding(STOCK_B,100,BROKER,true));
        var q=List.of(currentEquity(STOCK,PROVIDER,"200",QUOTED,KNOWN),
                currentEquity(STOCK_B,PROVIDER,"200",QUOTED,KNOWN));
        var s=snap(q,putQuote(PROVIDER,"-0.3",10,QUOTED,KNOWN));
        var d=run(holdings,contract(),s,history(false,false,true),
                rights(),policy(),NOW);
        assertEquals(Status.RESEARCH_CANDIDATE,d.status());
        assertEquals(0,n("40000").compareTo(d.currentPortfolioValueInr()));
        assertTrue(d.hypotheticalUnhedgedPortfolioLossInr()
                .compareTo(n("700"))>0);
        noOrders(d);
    }
    @Test void modeledFeesAndPremiumAreRequiredBeforeCallingProtection() {
        var expensive=new Policy(65,180,60,500,10,300,20,10,1000,100,5000,500,n("1000"));
        var d=run(owned(),contract(),snapshot(),history(),rights(),expensive,NOW);
        assertEquals(Status.NO_HEDGE,d.status());
        noOrders(d);
    }
    @Test void noAuthorizationCanBeDerivedFromAnyResearchOutcome() {
        var all=List.of(eval(),
                run(owned(),contract(),snapshot(),history(),
                    new Rights(false,true,true,true,true,true),policy(),NOW),
                run(owned(),contract(),snapshot(),history(false,true,false),
                    rights(),policy(),NOW));
        all.forEach(FnoTailRiskPutHedgeV1Test::noOrders);
    }
}
