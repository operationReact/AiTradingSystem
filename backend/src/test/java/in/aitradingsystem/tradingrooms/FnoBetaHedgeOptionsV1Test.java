package in.aitradingsystem.tradingrooms;

import static org.junit.jupiter.api.Assertions.*;
import static in.aitradingsystem.tradingrooms.FnoBetaHedgeOptionsV1.INDEX;
import in.aitradingsystem.tradingrooms.FnoBetaHedgeOptionsV1.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FnoBetaHedgeOptionsV1Test {
    private static final Instant NOW=Instant.parse("2026-11-20T05:00:00Z");
    private static final Instant EX=Instant.parse("2026-11-20T04:59:20Z");
    private static final Instant KNOWN=Instant.parse("2026-11-20T04:59:24Z");
    private static final Instant BROKER=Instant.parse("2026-11-19T05:00:00Z");
    private static final String SRC="UPSTOX_ANALYTICS";
    private static final String SHARE="NSE_EQ|RESEARCH_X";
    private static final String SHARE2="NSE_EQ|RESEARCH_Y";
    private static final String PUT="NSE_FO|NIFTY_20261127_100_PE";
    private static final String CALL="NSE_FO|NIFTY_20261127_100_CE";
    private static BigDecimal n(String s) { return new BigDecimal(s); }

    private static Rights rights() { return new Rights(true,true,true,true,true,true); }
    private static Policy policy() {
        return new Policy(65,180,60,500,10,300,20,10,1000,1000,
                n("0.05"),n("4"),n("10"));
    }
    private static OptionContract option(String type) {
        return new OptionContract("PE".equals(type)?PUT:CALL,INDEX,type,
                n("100"),n("0.05"),LocalDate.of(2026,11,1),
                LocalDate.of(2026,11,27),50,true);
    }
    private static OptionQuote optionQuote(String type,String delta,long depth,String provider,
                                           Instant known) {
        return new OptionQuote("PE".equals(type)?PUT:CALL,provider,n("2.95"),n("3"),
                n(delta),n("25"),200,depth,EX,known,true);
    }
    private static EquityQuote equityQuote(String key,String price,String provider,
                                           Instant exchangeAt,Instant firstKnownAt) {
        return new EquityQuote(key,provider,n(price),exchangeAt,firstKnownAt,true);
    }
    private static Snapshot snapshot(String type,String delta,long depth) {
        return snapshot(List.of(equityQuote(SHARE,"200",SRC,EX,KNOWN)),
                optionQuote(type,delta,depth,SRC,KNOWN));
    }
    private static Snapshot snapshot(List<EquityQuote> e,OptionQuote q) {
        return new Snapshot(SRC,INDEX,n("100"),e,q,EX,KNOWN,true);
    }
    private static List<Holding> holdings() {
        return List.of(new Holding(SHARE,100,BROKER,true));
    }
    private static Session session(LocalDate day,String provider,BigDecimal indexClose,
                                   List<EquityClose> equities) {
        Instant close=day.atTime(LocalTime.of(15,30))
                .atZone(ZoneId.of("Asia/Kolkata")).toInstant();
        return new Session(new IndexClose(day,provider,indexClose,close,
                close.plusSeconds(60),true),equities);
    }
    private static EquityClose eq(String key,String provider,BigDecimal close,LocalDate day) {
        Instant exchange=day.atTime(LocalTime.of(15,30))
                .atZone(ZoneId.of("Asia/Kolkata")).toInstant();
        return new EquityClose(key,provider,close,exchange,exchange.plusSeconds(60),true);
    }
    // Deliberately synthetic deterministic unit fixtures; not actual NSE marks.
    private static List<Session> history(boolean flatIndex,boolean flatEquity,
                                         boolean secondEquity) {
        var dates=new ArrayList<LocalDate>();
        LocalDate date=LocalDate.of(2026,11,19);
        while(dates.size()<70) {
            if(date.getDayOfWeek().getValue()<=5) dates.add(0,date);
            date=date.minusDays(1);
        }
        var bars=new ArrayList<Session>();
        for(int i=0;i<dates.size();i++) {
            LocalDate day=dates.get(i);
            BigDecimal index=flatIndex?n("100"):(i%2==0?n("100"):n("102"));
            BigDecimal price=flatEquity?n("200"):index.multiply(n("2"));
            var equities=new ArrayList<EquityClose>();
            equities.add(eq(SHARE,SRC,price,day));
            if(secondEquity) equities.add(eq(SHARE2,SRC,price,day));
            bars.add(session(day,SRC,index,equities));
        }
        return bars;
    }
    private static List<Session> history() { return history(false,false,false); }
    private static Decision eval() {
        return run(holdings(),option("PE"),snapshot("PE","-0.5",20),
                history(),rights(),policy(),NOW);
    }
    private static Decision run(List<Holding> h,OptionContract c,Snapshot s,
                                List<Session> bars,Rights r,Policy p,Instant asOf) {
        return FnoBetaHedgeOptionsV1.evaluate(h,c,s,bars,r,p,asOf);
    }
    private static void noOrders(Decision d) {
        assertEquals("strategy.STR-205",d.strategyId());
        assertTrue(d.currentWeightHistoricalProxyOnly());
        assertFalse(d.executable());
        assertFalse(d.orderPlaced());
        assertFalse(d.paperTradeAuthorized());
        assertFalse(d.liveTradeAuthorized());
    }

    @Test void sourceBetaOneYieldsLongEightPutsNotActualTrade() {
        Decision d=eval();
        assertEquals(Status.RESEARCH_CANDIDATE,d.status());
        assertEquals(69,d.completedReturnObservations());
        assertEquals(0,n("1").compareTo(d.observedPortfolioBeta()));
        assertEquals(0,n("20000").compareTo(d.portfolioValueInr()));
        assertEquals(0,n("20000").compareTo(d.observedBetaExposureInr()));
        assertEquals("PE",d.hypotheticalLongOptionType());
        assertEquals(8,d.hypotheticalBuyLots());
        assertEquals(0,n("-20000").compareTo(d.hypotheticalOptionDeltaExposureInr()));
        assertEquals(0,n("0").compareTo(d.hypotheticalResidualBetaExposureInr()));
        assertTrue(d.hypotheticalPurchaseCostInr().compareTo(n("1200"))>0);
        noOrders(d);
    }
    @Test void noLicensedHistoryRightBlocksImmediately() {
        var r=new Rights(true,true,false,true,true,true);
        Decision d=run(holdings(),option("PE"),snapshot("PE","-0.5",20),history(),r,policy(),NOW);
        assertEquals(Status.BLOCKED_RIGHTS,d.status());
        noOrders(d);
    }
    @Test void brokerHoldingsMustBeIndependentlyConfirmedAndOriginal() {
        var bad=List.of(new Holding(SHARE,100,BROKER,false));
        assertEquals(Status.BLOCKED_DATA,
                run(bad,option("PE"),snapshot("PE","-0.5",20),history(),rights(),policy(),NOW).status());
        bad=List.of(new Holding(SHARE,100,NOW,true));
        assertEquals(Status.BLOCKED_DATA,
                run(bad,option("PE"),snapshot("PE","-0.5",20),history(),rights(),policy(),NOW).status());
        bad=List.of(new Holding(SHARE,-100,BROKER,true));
        assertEquals(Status.BLOCKED_DATA,
                run(bad,option("PE"),snapshot("PE","-0.5",20),history(),rights(),policy(),NOW).status());
    }
    @Test void mismatchedProviderEquityQuoteCannotBeLaundered() {
        var equities=List.of(equityQuote(SHARE,"200","ANGEL_ONE_SMARTAPI",EX,KNOWN));
        var s=snapshot(equities,optionQuote("PE","-0.5",20,SRC,KNOWN));
        assertEquals(Status.BLOCKED_DATA,
                run(holdings(),option("PE"),s,history(),rights(),policy(),NOW).status());
    }
    @Test void perOptionProviderMustMatchOriginalIndexSource() {
        var quote=optionQuote("PE","-0.5",20,"ANGEL_ONE_SMARTAPI",KNOWN);
        var s=snapshot(List.of(equityQuote(SHARE,"200",SRC,EX,KNOWN)),quote);
        assertEquals(Status.BLOCKED_DATA,
                run(holdings(),option("PE"),s,history(),rights(),policy(),NOW).status());
    }
    @Test void staleAndFutureKnownOptionGreeksFailClosed() {
        var quote=optionQuote("PE","-0.5",20,SRC,NOW.plusSeconds(1));
        var s=snapshot(List.of(equityQuote(SHARE,"200",SRC,EX,KNOWN)),quote);
        assertEquals(Status.BLOCKED_DATA,
                run(holdings(),option("PE"),s,history(),rights(),policy(),NOW).status());
        var eqQuote=equityQuote(SHARE,"200",SRC,EX.minusSeconds(400),KNOWN);
        s=snapshot(List.of(eqQuote),optionQuote("PE","-0.5",20,SRC,KNOWN));
        assertEquals(Status.BLOCKED_DATA,
                run(holdings(),option("PE"),s,history(),rights(),policy(),NOW).status());
    }
    @Test void malformedOrWrongSignGreeksAreRejected() {
        assertEquals(Status.BLOCKED_DATA,
                run(holdings(),option("PE"),snapshot("PE","0.5",20),
                    history(),rights(),policy(),NOW).status());
        assertEquals(Status.BLOCKED_DATA,
                run(holdings(),option("CE"),snapshot("CE","-0.5",20),
                    history(),rights(),policy(),NOW).status());
    }
    @Test void wrongLongOptionSideIsNotMisrepresentedAsHedge() {
        var d=run(holdings(),option("CE"),snapshot("CE","0.5",20),
                history(),rights(),policy(),NOW);
        assertEquals(Status.NO_HEDGE,d.status());
        assertEquals(0,d.hypotheticalBuyLots());
        noOrders(d);
    }
    @Test void flatIndexCannotProduceReliableBeta() {
        var d=run(holdings(),option("PE"),snapshot("PE","-0.5",20),
                history(true,false,false),rights(),policy(),NOW);
        assertEquals(Status.BLOCKED_DATA,d.status());
        assertEquals("BETA_HEDGE_INDEX_VARIANCE_INSUFFICIENT",d.reasonCode());
        noOrders(d);
    }
    @Test void flatEquityHasNoMeaningfulIndexBetaHedge() {
        var d=run(holdings(),option("PE"),snapshot("PE","-0.5",20),
                history(false,true,false),rights(),policy(),NOW);
        assertEquals(Status.NO_HEDGE,d.status());
        assertEquals(0,d.hypotheticalBuyLots());
        noOrders(d);
    }
    @Test void minimumHistoricalObservationsCannotBeRelaxed() {
        var bars=history().subList(0,60);
        assertEquals(Status.BLOCKED_DATA,
                run(holdings(),option("PE"),snapshot("PE","-0.5",20),
                    bars,rights(),policy(),NOW).status());
    }
    @Test void duplicateAndMissingEquityHistoryAreNotAllowed() {
        var bars=new ArrayList<>(history());
        var first=bars.get(0);
        bars.set(0,session(first.index().session(),SRC,first.index().close(),
                List.of(first.equities().get(0),first.equities().get(0))));
        assertEquals(Status.BLOCKED_DATA,
                run(holdings(),option("PE"),snapshot("PE","-0.5",20),
                    bars,rights(),policy(),NOW).status());
        bars=new ArrayList<>(history());
        first=bars.get(0);
        bars.set(0,session(first.index().session(),SRC,first.index().close(),List.of()));
        assertEquals(Status.BLOCKED_DATA,
                run(holdings(),option("PE"),snapshot("PE","-0.5",20),
                    bars,rights(),policy(),NOW).status());
    }
    @Test void historicalCrossBrokerClosesFailEvenWhenIndexIsAuthentic() {
        var bars=new ArrayList<>(history());
        Session one=bars.get(12);
        var altered=eq(SHARE,"ANGEL_ONE_SMARTAPI",one.equities().get(0).close(),one.index().session());
        bars.set(12,session(one.index().session(),SRC,one.index().close(),List.of(altered)));
        assertEquals(Status.BLOCKED_DATA,
                run(holdings(),option("PE"),snapshot("PE","-0.5",20),
                    bars,rights(),policy(),NOW).status());
    }
    @Test void historicalFutureKnownEquityObservationIsBlocked() {
        var bars=new ArrayList<>(history());
        Session one=bars.get(12);
        EquityClose v=one.equities().get(0);
        var altered=new EquityClose(v.key(),v.provider(),v.close(),v.exchangeCloseAt(),NOW.plusSeconds(1),true);
        bars.set(12,session(one.index().session(),SRC,one.index().close(),List.of(altered)));
        assertEquals(Status.BLOCKED_DATA,
                run(holdings(),option("PE"),snapshot("PE","-0.5",20),
                    bars,rights(),policy(),NOW).status());
    }
    @Test void askSideDepthRestrictsHypotheticalPurchasedLotCount() {
        var d=run(holdings(),option("PE"),snapshot("PE","-0.5",3),
                history(),rights(),policy(),NOW);
        assertEquals(Status.RESEARCH_CANDIDATE,d.status());
        assertEquals(3,d.hypotheticalBuyLots());
        assertEquals(0,n("12500").compareTo(d.hypotheticalResidualBetaExposureInr()));
        noOrders(d);
        assertEquals(Status.BLOCKED_DATA,
                run(holdings(),option("PE"),snapshot("PE","-0.5",0),
                    history(),rights(),policy(),NOW).status());
    }
    @Test void strictMinimumRiskReductionCannotBeBypassed() {
        var policy=new Policy(65,180,60,500,10,300,20,10,1000,9900,
                n("0.05"),n("4"),n("10"));
        var d=run(holdings(),option("PE"),snapshot("PE","-0.5",3),
                history(),rights(),policy,NOW);
        assertEquals(Status.NO_HEDGE,d.status());
        assertEquals(0,d.hypotheticalBuyLots());
        noOrders(d);
    }
    @Test void premiumBudgetRejectsCostlyHedgeButReportsCost() {
        var policy=new Policy(65,180,60,500,10,300,20,10,50,1000,
                n("0.05"),n("4"),n("10"));
        var d=run(holdings(),option("PE"),snapshot("PE","-0.5",20),
                history(),rights(),policy,NOW);
        assertEquals(Status.NO_HEDGE,d.status());
        assertEquals(0,d.hypotheticalBuyLots());
        assertTrue(d.hypotheticalPurchaseCostInr().signum()>0);
        noOrders(d);
    }
    @Test void multipleIndependentHoldingsMakeOneFixedWeightResearchBasket() {
        var own=List.of(new Holding(SHARE,100,BROKER,true),
                new Holding(SHARE2,100,BROKER,true));
        var eq=List.of(equityQuote(SHARE,"200",SRC,EX,KNOWN),
                equityQuote(SHARE2,"200",SRC,EX,KNOWN));
        var s=snapshot(eq,optionQuote("PE","-0.5",20,SRC,KNOWN));
        var d=run(own,option("PE"),s,history(false,false,true),rights(),policy(),NOW);
        assertEquals(Status.RESEARCH_CANDIDATE,d.status());
        assertEquals(16,d.hypotheticalBuyLots());
        assertEquals(0,n("40000").compareTo(d.portfolioValueInr()));
        noOrders(d);
    }
    @Test void duplicateHoldingsKeysAreRejected() {
        var d=run(List.of(new Holding(SHARE,100,BROKER,true),
                    new Holding(SHARE,100,BROKER,true)),
                option("PE"),snapshot("PE","-0.5",20),history(),rights(),policy(),NOW);
        assertEquals(Status.BLOCKED_DATA,d.status());
    }
    @Test void optionExpiryAndTickIdentityAreMandatory() {
        var c=new OptionContract(PUT,INDEX,"PE",n("100.01"),n("0.05"),
                LocalDate.of(2026,11,1),LocalDate.of(2026,11,27),50,true);
        assertEquals(Status.BLOCKED_DATA,
                run(holdings(),c,snapshot("PE","-0.5",20),
                    history(),rights(),policy(),NOW).status());
        c=new OptionContract(PUT,INDEX,"PE",n("100"),n("0.05"),
                LocalDate.of(2026,11,1),LocalDate.of(2026,11,21),50,true);
        assertEquals(Status.BLOCKED_DATA,
                run(holdings(),c,snapshot("PE","-0.5",20),
                    history(),rights(),policy(),NOW).status());
    }
    @Test void missingOriginalIvAndExcessiveOptionSpreadBlockResearch() {
        var q=new OptionQuote(PUT,SRC,n("2.95"),n("3"),n("-0.5"),
                null,200,20,EX,KNOWN,true);
        var s=snapshot(List.of(equityQuote(SHARE,"200",SRC,EX,KNOWN)),q);
        assertEquals(Status.BLOCKED_DATA,
                run(holdings(),option("PE"),s,history(),rights(),policy(),NOW).status());
        q=new OptionQuote(PUT,SRC,n("0.5"),n("3"),n("-0.5"),
                n("25"),200,20,EX,KNOWN,true);
        s=snapshot(List.of(equityQuote(SHARE,"200",SRC,EX,KNOWN)),q);
        assertEquals(Status.BLOCKED_DATA,
                run(holdings(),option("PE"),s,history(),rights(),policy(),NOW).status());
    }
    @Test void onlyAsOfResearchNeverPaperOrLiveOrders() {
        for(Decision d:List.of(eval(),
                run(holdings(),option("CE"),snapshot("CE","0.5",20),
                        history(),rights(),policy(),NOW),
                run(holdings(),option("PE"),snapshot("PE","-0.5",20),
                        history(),new Rights(false,true,true,true,true,true),policy(),NOW))) {
            noOrders(d);
        }
    }
}
