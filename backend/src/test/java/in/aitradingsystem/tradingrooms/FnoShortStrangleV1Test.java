package in.aitradingsystem.tradingrooms;

import static org.junit.jupiter.api.Assertions.*;
import in.aitradingsystem.tradingrooms.FnoShortStrangleV1.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FnoShortStrangleV1Test {
    static final Instant NOW=Instant.parse("2026-11-20T05:00:00Z");
    static final Instant TICK=NOW.minusSeconds(40);
    static final String PROVIDER="UPSTOX_ANALYTICS";
    static final String INDEX=FnoLongOptionCrossoverV1.UNDERLYING;
    static final String CE="NSE_FO|NIFTY_20261127_105_CE";
    static final String PE="NSE_FO|NIFTY_20261127_95_PE";
    static final LocalDate EXPIRY=LocalDate.of(2026,11,27);
    static final Contract CALL=new Contract(CE,INDEX,"CE",n("105"),n("0.05"),
        LocalDate.of(2026,11,1),EXPIRY,50,true);
    static final Contract PUT=new Contract(PE,INDEX,"PE",n("95"),n("0.05"),
        LocalDate.of(2026,11,1),EXPIRY,50,true);
    static final Quote SPOT=new Quote(PROVIDER,INDEX,n("100"),n("100.05"),
        null,100,100000,TICK,TICK.plusSeconds(10));
    static final Quote CQ=new Quote(PROVIDER,CE,n("1.95"),n("2.00"),
        n("5"),10,1000,TICK,TICK.plusSeconds(10));
    static final Quote PQ=new Quote(PROVIDER,PE,n("1.95"),n("2.00"),
        n("5"),10,1000,TICK,TICK.plusSeconds(10));
    static final Rights RIGHTS=new Rights(true,true,true,true);
    static final Assumptions A=new Assumptions(50,300,2,50,1500,300,
        n("15"),n("10000"),5000,10000);
    static BigDecimal n(String v){return new BigDecimal(v);}
    static List<CompletedClose> candles() {
        var days=new ArrayList<LocalDate>();
        var d=LocalDate.of(2026,11,19);
        while(days.size()<35) {
            if(d.getDayOfWeek().getValue()<=5)days.add(0,d);
            d=d.minusDays(1);
        }
        var ret=new ArrayList<CompletedClose>();
        for(var day:days) {
            var close=day.atTime(LocalTime.of(15,30))
                .atZone(ZoneId.of("Asia/Kolkata")).toInstant();
            ret.add(new CompletedClose(day,PROVIDER,n("100"),close,close.plusSeconds(60),true));
        }
        return ret;
    }
    static Decision eval(Contract c,Contract p,List<CompletedClose> candles,
                         Quote spot,Quote cq,Quote pq,Rights rights,Assumptions a) {
        return FnoShortStrangleV1.evaluate(c,p,candles,spot,cq,pq,rights,a,NOW);
    }
    static Decision run(){return eval(CALL,PUT,candles(),SPOT,CQ,PQ,RIGHTS,A);}
    static void assertState(Decision d,Status st,String reason) {
        assertEquals(st,d.status());
        assertEquals(reason,d.reasonCode());
        assertEquals("strategy.STR-195",d.strategyId());
        assertTrue(d.unlimitedTheoreticalUpsideLoss());
        assertFalse(d.executable());
        assertFalse(d.orderPlaced());
        assertFalse(d.liveTradeAuthorized());
    }
    @Test void matchingOtmCallPutShortPremiumCarriesUnboundedUpsideLoss() {
        var d=run();
        assertState(d,Status.RESEARCH_CANDIDATE,"SHORT_STRANGLE_UNBOUNDED_RISK_RESEARCH_ONLY");
        assertEquals(CE,d.callKey());assertEquals(PE,d.putKey());
        assertTrue(d.theoreticalMaximumDownsideExpiryLossInr().compareTo(d.netCollectedCreditPerLotInr())>0);
        assertTrue(d.lowerExpiryBreakeven().compareTo(n("95"))<0);
        assertTrue(d.upperExpiryBreakeven().compareTo(n("105"))>0);
        assertTrue(d.expectedMoveOverCreditBps().compareTo(n("10000"))<0);
    }
    @Test void allFourIndependentRightsAreNeeded() {
        assertState(eval(CALL,PUT,candles(),SPOT,CQ,PQ,
            new Rights(true,true,true,false),A),Status.BLOCKED_RIGHTS,
            "SHORT_STRANGLE_RIGHTS_UNVERIFIED");
    }
    @Test void bothOptionsMustBeDistinctVerifiedOtmLegsWithSameLotAndExpiry() {
        for(var bad:List.of(
            new Contract(PE,INDEX,"CE",n("95"),n("0.05"),PUT.listedFrom(),EXPIRY,50,true),
            new Contract(PE,INDEX,"PE",n("105"),n("0.05"),PUT.listedFrom(),EXPIRY,50,true),
            new Contract(PE,INDEX,"PE",n("95"),n("0.05"),PUT.listedFrom(),EXPIRY,25,true),
            new Contract(PE,INDEX,"PE",n("95"),n("0.05"),PUT.listedFrom(),EXPIRY.plusDays(7),50,true),
            new Contract(PE,INDEX,"PE",n("95"),n("0.05"),PUT.listedFrom(),EXPIRY,50,false))) {
            assertState(eval(CALL,bad,candles(),SPOT,CQ,PQ,RIGHTS,A),
                Status.BLOCKED_DATA,"SHORT_STRANGLE_CONTRACT_OR_COST_INVALID");
        }
    }
    @Test void strikesMustEncloseObservedSpotAndBothBeStrictlyOtm() {
        for(var value:List.of("105","95")) {
            var spot=new Quote(PROVIDER,INDEX,n(value),n("110"),
                null,100,100000,TICK,TICK.plusSeconds(10));
            assertState(eval(CALL,PUT,candles(),spot,CQ,PQ,RIGHTS,A),
                Status.BLOCKED_DATA,"SHORT_STRANGLE_QUOTES_OR_IV_INVALID");
        }
    }
    @Test void missingIvOrOutOfRangeIvCannotBecomeSignal() {
        for(var iv:List.of(n("0"),n("301"),n("-1"))) {
            var bad=new Quote(PROVIDER,CE,n("1.95"),n("2.00"),iv,10,1000,TICK,TICK.plusSeconds(10));
            assertState(eval(CALL,PUT,candles(),SPOT,bad,PQ,RIGHTS,A),
                Status.BLOCKED_DATA,"SHORT_STRANGLE_QUOTES_OR_IV_INVALID");
        }
    }
    @Test void staleCrossProviderThinAndCrossedQuotesBlock() {
        for(var bad:List.of(
            new Quote(PROVIDER,CE,n("1.95"),n("2.00"),n("80"),0,1000,TICK,TICK.plusSeconds(10)),
            new Quote(PROVIDER,CE,n("1.95"),n("2.00"),n("80"),10,0,TICK,TICK.plusSeconds(10)),
            new Quote(PROVIDER,CE,n("2.10"),n("2.00"),n("5"),10,1000,TICK,TICK.plusSeconds(10)),
            new Quote("ANGEL_ONE_SMARTAPI",CE,n("1.95"),n("2.00"),n("5"),10,1000,TICK,TICK.plusSeconds(10)),
            new Quote(PROVIDER,CE,n("1.95"),n("2.00"),n("5"),10,1000,NOW.minusSeconds(200),NOW.minusSeconds(190)),
            new Quote(PROVIDER,CE,n("1.95"),n("2.00"),n("5"),10,1000,TICK,NOW.plusSeconds(1)))) {
            assertState(eval(CALL,PUT,candles(),SPOT,bad,PQ,RIGHTS,A),
                Status.BLOCKED_DATA,"SHORT_STRANGLE_QUOTES_OR_IV_INVALID");
        }
    }
    @Test void pointInTimeHistoricalCandleMustAlreadyBePublished() {
        var bars=candles();var last=bars.getLast();
        bars.set(34,new CompletedClose(last.session(),PROVIDER,last.close(),
            last.exchangeCloseAt(),NOW.plusSeconds(1),true));
        assertState(eval(CALL,PUT,bars,SPOT,CQ,PQ,RIGHTS,A),
            Status.BLOCKED_DATA,"SHORT_STRANGLE_HISTORY_INVALID");
        bars=candles();bars.set(34,bars.get(33));
        assertState(eval(CALL,PUT,bars,SPOT,CQ,PQ,RIGHTS,A),
            Status.BLOCKED_DATA,"SHORT_STRANGLE_HISTORY_INVALID");
    }
    @Test void creditSanityLimitRejectsInconsistentPremiumWithoutOrdering() {
        var a=new Assumptions(50,300,2,50,1500,300,n("15"),n("10000"),100,10000);
        assertState(eval(CALL,PUT,candles(),SPOT,CQ,PQ,RIGHTS,a),
            Status.NO_SIGNAL,"SHORT_STRANGLE_CREDIT_SANITY_CAP_EXCEEDED");
    }
    @Test void largeIvExpectedMoveRelativeToCreditRejectsShortVolatility() {
        var highIvCall=new Quote(PROVIDER,CE,n("1.95"),n("2.00"),n("80"),10,1000,TICK,TICK.plusSeconds(10));
        var highIvPut=new Quote(PROVIDER,PE,n("1.95"),n("2.00"),n("80"),10,1000,TICK,TICK.plusSeconds(10));
        assertState(eval(CALL,PUT,candles(),SPOT,highIvCall,highIvPut,RIGHTS,A),
            Status.NO_SIGNAL,"SHORT_STRANGLE_IV_MOVE_EXCEEDS_CREDIT");
    }
    @Test void missingNetCreditNeverCountsAsAcceptableShortPremium() {
        var lowCall=new Quote(PROVIDER,CE,n("0.10"),n("0.102"),n("5"),10,1000,TICK,TICK.plusSeconds(10));
        var lowPut=new Quote(PROVIDER,PE,n("0.10"),n("0.102"),n("5"),10,1000,TICK,TICK.plusSeconds(10));
        assertState(eval(CALL,PUT,candles(),SPOT,lowCall,lowPut,RIGHTS,A),
            Status.NO_SIGNAL,"SHORT_STRANGLE_AFTER_COST_CREDIT_NONPOSITIVE");
    }
    @Test void zeroFeesAndInvalidSlippageFailClosed() {
        var a=new Assumptions(50,300,2,50,1500,300,n("0"),n("10000"),5000,10000);
        assertState(eval(CALL,PUT,candles(),SPOT,CQ,PQ,RIGHTS,a),
            Status.BLOCKED_DATA,"SHORT_STRANGLE_CONTRACT_OR_COST_INVALID");
    }
    @Test void deterministicNoOrderAndCallUpsideLossIsUnbounded() {
        assertEquals(run(),run());
        assertTrue(run().theoreticalMaximumDownsideExpiryLossInr().signum()>0);
        assertFalse(run().liveTradeAuthorized());
    }
}
