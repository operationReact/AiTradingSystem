package in.aitradingsystem.tradingrooms;

import static org.junit.jupiter.api.Assertions.*;
import in.aitradingsystem.tradingrooms.FnoPutSpreadCollarV1.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FnoPutSpreadCollarV1Test {
    private static final Instant NOW = Instant.parse("2026-11-20T05:00:00Z");
    private static final Instant QUOTED = Instant.parse("2026-11-20T04:59:20Z");
    private static final Instant FIRST = Instant.parse("2026-11-20T04:59:24Z");
    private static final Instant BROKER = Instant.parse("2026-11-19T05:00:00Z");
    private static final LocalDate EXPIRY = LocalDate.of(2026,11,27);
    private static final LocalDate LISTED = LocalDate.of(2026,11,1);
    private static final String INDEX = FnoLongOptionCrossoverV1.UNDERLYING;
    private static final String SRC = "UPSTOX_ANALYTICS";
    private static final String FUT = "NSE_FO|NIFTY_20261127_FUT";
    private static final String HIGH_PE = "NSE_FO|NIFTY_20261127_95_PE";
    private static final String LOW_PE = "NSE_FO|NIFTY_20261127_90_PE";
    private static final String CALL = "NSE_FO|NIFTY_20261127_110_CE";
    private static BigDecimal n(String x) {return new BigDecimal(x);}
    private static Rights rights() {return new Rights(true,true,true,true,true,true);}
    private static Policy policy() {
        return new Policy(180,30,1000,100,10,300,10,10,10,n("5"),n("250"),2);
    }
    private static FutureContract future() {
        return new FutureContract(FUT,INDEX,n("0.05"),LISTED,EXPIRY,50,true);
    }
    private static BrokerFuturePosition held(int lots) {
        return new BrokerFuturePosition(FUT,lots,n("100"),BROKER,true);
    }
    private static OptionContract putHigher() {
        return new OptionContract(HIGH_PE,INDEX,"PE",n("95"),n("0.05"),LISTED,EXPIRY,50,true);
    }
    private static OptionContract putLower() {
        return new OptionContract(LOW_PE,INDEX,"PE",n("90"),n("0.05"),LISTED,EXPIRY,50,true);
    }
    private static OptionContract call() {
        return new OptionContract(CALL,INDEX,"CE",n("110"),n("0.05"),LISTED,EXPIRY,50,true);
    }
    private static Quote quote(String key,String type,String bid,String ask,
                               long buyDepth,long sellDepth,String provider,
                               Instant exchangeAt,Instant firstKnown) {
        String delta="CE".equals(type)?"0.2":"-0.2";
        return new Quote(provider,key,n(bid),n(ask),n(delta),n("28"),
                1000,sellDepth,buyDepth,exchangeAt,firstKnown,true);
    }
    private static Quote boughtPut() {
        return quote(HIGH_PE,"PE","3.95","4",10,10,SRC,QUOTED,FIRST);
    }
    private static Quote writtenPut() {
        return quote(LOW_PE,"PE","1","1.05",10,10,SRC,QUOTED,FIRST);
    }
    private static Quote writtenCall() {
        return quote(CALL,"CE","3","3.05",10,10,SRC,QUOTED,FIRST);
    }
    private static Snapshot snapshot(Quote higher,Quote lower,Quote callQuote) {
        return new Snapshot(SRC,INDEX,FUT,n("100"),n("100"),n("100.05"),
                higher,lower,callQuote,QUOTED,FIRST,true);
    }
    private static Snapshot snapshot() {
        return snapshot(boughtPut(),writtenPut(),writtenCall());
    }
    private static List<CompletedClose> history() {
        var days=new ArrayList<LocalDate>();
        LocalDate day=LocalDate.of(2026,11,19);
        while(days.size()<35) {
            if(day.getDayOfWeek().getValue()<=5)days.add(0,day);
            day=day.minusDays(1);
        }
        var out=new ArrayList<CompletedClose>();
        for(LocalDate d:days) {
            Instant close=d.atTime(LocalTime.of(15,30))
                    .atZone(ZoneId.of("Asia/Kolkata")).toInstant();
            out.add(new CompletedClose(d,SRC,n("100"),close,close.plusSeconds(60),true));
        }
        return out;
    }
    private static Decision run(BrokerFuturePosition position,FutureContract future,
                                OptionContract high,OptionContract low,OptionContract call,
                                Snapshot snapshot,List<CompletedClose> closes,
                                Rights rights,Policy policy,Instant asOf) {
        return FnoPutSpreadCollarV1.evaluate(position,future,high,low,call,
                snapshot,closes,rights,policy,asOf);
    }
    private static Decision run() {
        return run(held(1),future(),putHigher(),putLower(),call(),
                snapshot(),history(),rights(),policy(),NOW);
    }
    private static void noOrder(Decision decision) {
        assertEquals("strategy.STR-207",decision.strategyId());
        assertTrue(decision.expiryScenarioOnly());
        assertFalse(decision.executable());
        assertFalse(decision.orderPlaced());
        assertFalse(decision.paperTradeAuthorized());
        assertFalse(decision.liveTradeAuthorized());
    }

    @Test void authenticStructureHasDownsideGapAndCoveredUpsideCap() {
        Decision d=run();
        assertEquals(Status.RESEARCH_CANDIDATE,d.status());
        assertEquals(35,d.originalCompletedCloses());
        assertEquals(1,d.brokerConfirmedLongFutureLots());
        assertEquals(1,d.hypotheticalOptionStructureLots());
        assertEquals(0,n("250").compareTo(d.hypotheticalPutSpreadProtectionWidthInr()));
        assertTrue(d.hypotheticalNetOptionDebitInr().signum()>0);
        assertEquals(0,n("5.4").compareTo(d.hypotheticalNetOptionDebitInr()));
        assertEquals(0,n("-4755.4").compareTo(d.hypotheticalPayoffAtIndexZeroInr()));
        assertEquals(0,n("-255.4").compareTo(d.hypotheticalPayoffAtLowerPutStrikeInr()));
        assertEquals(0,n("-255.4").compareTo(d.hypotheticalPayoffAtHigherPutStrikeInr()));
        assertEquals(0,n("494.6").compareTo(d.hypotheticalUpsideCapAtCallStrikeInr()));
        assertTrue(d.hypotheticalPayoffAtIndexZeroInr()
                .compareTo(d.hypotheticalPayoffAtLowerPutStrikeInr())<0);
        noOrder(d);
    }
    @Test void noPositionRightsMeansNoResearchPermission() {
        var r=new Rights(false,true,true,true,true,true);
        Decision d=run(held(1),future(),putHigher(),putLower(),call(),
                snapshot(),history(),r,policy(),NOW);
        assertEquals(Status.BLOCKED_RIGHTS,d.status());
        noOrder(d);
    }
    @Test void brokerPositionMustBeVerifiedPositiveAndOriginallyKnown() {
        for (BrokerFuturePosition p:List.of(
                new BrokerFuturePosition(FUT,1,n("100"),BROKER,false),
                new BrokerFuturePosition(FUT,0,n("100"),BROKER,true),
                new BrokerFuturePosition(FUT,-1,n("100"),BROKER,true),
                new BrokerFuturePosition(FUT,1,n("100"),NOW,true))) {
            assertEquals(Status.BLOCKED_DATA,
                    run(p,future(),putHigher(),putLower(),call(),snapshot(),
                            history(),rights(),policy(),NOW).status());
        }
    }
    @Test void futureQuoteAndUnderlyingAreSeparateSourceListedInstruments() {
        var mismatch=new FutureContract("NSE_FO|OTHER",INDEX,n("0.05"),LISTED,EXPIRY,50,true);
        assertEquals(Status.BLOCKED_DATA,
                run(held(1),mismatch,putHigher(),putLower(),call(),snapshot(),
                        history(),rights(),policy(),NOW).status());
        mismatch=new FutureContract(FUT,"NSE_INDEX|Other",n("0.05"),LISTED,EXPIRY,50,true);
        assertEquals(Status.BLOCKED_DATA,
                run(held(1),mismatch,putHigher(),putLower(),call(),snapshot(),
                        history(),rights(),policy(),NOW).status());
    }
    @Test void everyOptionMustMatchGenuineFutureExpiryAndOfficialLot() {
        var shifted=new OptionContract(HIGH_PE,INDEX,"PE",n("95"),n("0.05"),
                LISTED,EXPIRY.plusDays(7),50,true);
        assertEquals(Status.BLOCKED_DATA,
                run(held(1),future(),shifted,putLower(),call(),snapshot(),
                        history(),rights(),policy(),NOW).status());
        shifted=new OptionContract(HIGH_PE,INDEX,"PE",n("95"),n("0.05"),
                LISTED,EXPIRY,75,true);
        assertEquals(Status.BLOCKED_DATA,
                run(held(1),future(),shifted,putLower(),call(),snapshot(),
                        history(),rights(),policy(),NOW).status());
    }
    @Test void noUncoveredShortCallAllowedWithoutMatchingBrokerLongFuture() {
        var p=new BrokerFuturePosition(FUT,0,n("100"),BROKER,true);
        assertEquals(Status.BLOCKED_DATA,
                run(p,future(),putHigher(),putLower(),call(),snapshot(),
                        history(),rights(),policy(),NOW).status());
    }
    @Test void putWingAndCallOrderMustFollowTrueIndexSpot() {
        var high=new OptionContract(HIGH_PE,INDEX,"PE",n("89"),n("0.05"),
                LISTED,EXPIRY,50,true);
        assertEquals(Status.BLOCKED_DATA,
                run(held(1),future(),high,putLower(),call(),snapshot(),
                        history(),rights(),policy(),NOW).status());
        var newCall=new OptionContract(CALL,INDEX,"CE",n("99"),n("0.05"),
                LISTED,EXPIRY,50,true);
        assertEquals(Status.BLOCKED_DATA,
                run(held(1),future(),putHigher(),putLower(),newCall,snapshot(),
                        history(),rights(),policy(),NOW).status());
    }
    @Test void enforcePutWingMinimumWidthFromResearchPolicy() {
        var p=new Policy(180,30,1000,100,10,300,10,10,10,n("5"),n("250"),6);
        assertEquals(Status.BLOCKED_DATA,
                run(held(1),future(),putHigher(),putLower(),call(),
                        snapshot(),history(),rights(),p,NOW).status());
    }
    @Test void buyPutRequiresRealAskDepthForAllCoveredLots() {
        var bad=quote(HIGH_PE,"PE","3.95","4",1,10,SRC,QUOTED,FIRST);
        var s=snapshot(bad,writtenPut(),writtenCall());
        assertEquals(Status.BLOCKED_DATA,
                run(held(2),future(),putHigher(),putLower(),call(),
                        s,history(),rights(),policy(),NOW).status());
    }
    @Test void bothShortLegsRequireRealBidDepthForAllCoveredLots() {
        var lower=quote(LOW_PE,"PE","1","1.05",10,1,SRC,QUOTED,FIRST);
        var s=snapshot(boughtPut(),lower,writtenCall());
        assertEquals(Status.BLOCKED_DATA,
                run(held(2),future(),putHigher(),putLower(),call(),
                        s,history(),rights(),policy(),NOW).status());
        var highCall=quote(CALL,"CE","3","3.05",10,1,SRC,QUOTED,FIRST);
        s=snapshot(boughtPut(),writtenPut(),highCall);
        assertEquals(Status.BLOCKED_DATA,
                run(held(2),future(),putHigher(),putLower(),call(),
                        s,history(),rights(),policy(),NOW).status());
    }
    @Test void allQuoteProvidersMustMatchOriginalIndexSource() {
        var q=quote(HIGH_PE,"PE","3.95","4",10,10,"ANGEL_ONE_SMARTAPI",QUOTED,FIRST);
        assertEquals(Status.BLOCKED_DATA,
                run(held(1),future(),putHigher(),putLower(),call(),
                        snapshot(q,writtenPut(),writtenCall()),
                        history(),rights(),policy(),NOW).status());
    }
    @Test void originalFirstKnownQuoteAfterSnapshotIsInvalid() {
        var q=quote(HIGH_PE,"PE","3.95","4",10,10,SRC,QUOTED,NOW);
        assertEquals(Status.BLOCKED_DATA,
                run(held(1),future(),putHigher(),putLower(),call(),
                        snapshot(q,writtenPut(),writtenCall()),
                        history(),rights(),policy(),NOW).status());
    }
    @Test void originalGreekDirectionAndIvMustBeVerified() {
        var q=new Quote(SRC,HIGH_PE,n("3.95"),n("4"),n("0.2"),n("28"),1000,
                10,10,QUOTED,FIRST,true);
        assertEquals(Status.BLOCKED_DATA,
                run(held(1),future(),putHigher(),putLower(),call(),
                        snapshot(q,writtenPut(),writtenCall()),
                        history(),rights(),policy(),NOW).status());
        q=new Quote(SRC,HIGH_PE,n("3.95"),n("4"),n("-0.2"),null,1000,
                10,10,QUOTED,FIRST,true);
        assertEquals(Status.BLOCKED_DATA,
                run(held(1),future(),putHigher(),putLower(),call(),
                        snapshot(q,writtenPut(),writtenCall()),
                        history(),rights(),policy(),NOW).status());
    }
    @Test void genuineBidAskSpreadAndOiMustPass() {
        var q=quote(HIGH_PE,"PE","0.01","4",10,10,SRC,QUOTED,FIRST);
        assertEquals(Status.BLOCKED_DATA,
                run(held(1),future(),putHigher(),putLower(),call(),
                        snapshot(q,writtenPut(),writtenCall()),
                        history(),rights(),policy(),NOW).status());
        q=new Quote(SRC,HIGH_PE,n("3.95"),n("4"),n("-0.2"),n("28"),0,
                10,10,QUOTED,FIRST,true);
        assertEquals(Status.BLOCKED_DATA,
                run(held(1),future(),putHigher(),putLower(),call(),
                        snapshot(q,writtenPut(),writtenCall()),
                        history(),rights(),policy(),NOW).status());
    }
    @Test void staleQuoteAndWrongOptionKeyFailClosed() {
        var q=quote(HIGH_PE,"PE","3.95","4",10,10,SRC,
                QUOTED.minusSeconds(200),FIRST.minusSeconds(200));
        assertEquals(Status.BLOCKED_DATA,
                run(held(1),future(),putHigher(),putLower(),call(),
                        snapshot(q,writtenPut(),writtenCall()),
                        history(),rights(),policy(),NOW).status());
        q=quote("NSE_FO|NOT_THE_PUT","PE","3.95","4",10,10,SRC,QUOTED,FIRST);
        assertEquals(Status.BLOCKED_DATA,
                run(held(1),future(),putHigher(),putLower(),call(),
                        snapshot(q,writtenPut(),writtenCall()),
                        history(),rights(),policy(),NOW).status());
    }
    @Test void insufficientHistoryAndCrossProviderHistoryFail() {
        assertEquals(Status.BLOCKED_DATA,
                run(held(1),future(),putHigher(),putLower(),call(),snapshot(),
                        history().subList(0,34),rights(),policy(),NOW).status());
        var bars=new ArrayList<>(history());
        var b=bars.get(8);
        bars.set(8,new CompletedClose(b.session(),"ANGEL_ONE_SMARTAPI",b.close(),
                b.exchangeCloseAt(),b.firstKnownAt(),true));
        assertEquals(Status.BLOCKED_DATA,
                run(held(1),future(),putHigher(),putLower(),call(),snapshot(),
                        bars,rights(),policy(),NOW).status());
    }
    @Test void futureKnownHistoricalBarsNeverCountAsAvailable() {
        var bars=new ArrayList<>(history());
        var b=bars.get(8);
        bars.set(8,new CompletedClose(b.session(),b.provider(),b.close(),
                b.exchangeCloseAt(),NOW.plusSeconds(1),true));
        assertEquals(Status.BLOCKED_DATA,
                run(held(1),future(),putHigher(),putLower(),call(),snapshot(),
                        bars,rights(),policy(),NOW).status());
    }
    @Test void excessiveAfterFeeDebitIsNotAResearchCandidate() {
        var p=new Policy(180,30,1000,100,10,300,10,10,10,n("5"),n("1"),2);
        var d=run(held(1),future(),putHigher(),putLower(),call(),snapshot(),
                history(),rights(),p,NOW);
        assertEquals(Status.NO_STRUCTURE,d.status());
        assertEquals(0,d.hypotheticalOptionStructureLots());
        assertTrue(d.hypotheticalNetOptionDebitInr().compareTo(n("1"))>0);
        noOrder(d);
    }
    @Test void twoCoveredFuturesLotsProduceTwoEqualStructures() {
        var d=run(held(2),future(),putHigher(),putLower(),call(),snapshot(),
                history(),rights(),policy(),NOW);
        assertEquals(Status.RESEARCH_CANDIDATE,d.status());
        assertEquals(2,d.hypotheticalOptionStructureLots());
        assertEquals(0,n("500").compareTo(d.hypotheticalPutSpreadProtectionWidthInr()));
        noOrder(d);
    }
    @Test void fullySourceVerifiedStillCannotPlacePaperOrLiveOrders() {
        var outcomes=List.of(run(),
                run(held(0),future(),putHigher(),putLower(),call(),snapshot(),
                        history(),rights(),policy(),NOW),
                run(held(1),future(),putHigher(),putLower(),call(),snapshot(),
                        history(),new Rights(false,true,true,true,true,true),policy(),NOW));
        outcomes.forEach(FnoPutSpreadCollarV1Test::noOrder);
    }
}
