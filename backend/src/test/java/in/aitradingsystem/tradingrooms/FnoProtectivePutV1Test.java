package in.aitradingsystem.tradingrooms;

import static org.junit.jupiter.api.Assertions.*;

import in.aitradingsystem.tradingrooms.FnoProtectivePutV1.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FnoProtectivePutV1Test {
    static final Instant NOW = Instant.parse("2026-11-20T05:00:00Z");
    static final Instant TICK = NOW.minusSeconds(35);
    static final String SOURCE = "UPSTOX_ANALYTICS";
    static final String EQ = "NSE_EQ|EXAMPLECO";
    static final String PE = "NSE_FO|EXAMPLECO_20261127_95_PE";
    static final LocalDate EXPIRY = LocalDate.of(2026,11,27);
    static final PutContract CONTRACT = new PutContract(PE, EQ,"PE",n("95"),n("0.05"),
        LocalDate.of(2026,11,1),EXPIRY,50,true,true);
    static final Holding HOLD = new Holding(SOURCE,EQ,50,n("90"),NOW.minusSeconds(45),true,true);
    static final EquityQuote SPOT = new EquityQuote(SOURCE,EQ,n("94"),
        TICK,TICK.plusSeconds(10));
    static final PutQuote PUT = new PutQuote(SOURCE,PE,n("1.95"),n("2.00"),
        20,1200,TICK,TICK.plusSeconds(10));
    static final SourceRights RIGHTS = new SourceRights(true,true,true,true);
    static final Assumptions A = new Assumptions(2000,300,50,300,2,50,1000,
        n("10"),n("10000"),3000,100);
    static BigDecimal n(String v) { return new BigDecimal(v); }
    static List<CompletedClose> bars(String last) {
        List<LocalDate> days = new ArrayList<>();
        var d = LocalDate.of(2026,11,19);
        while(days.size() < 35) {
            if(d.getDayOfWeek().getValue()<=5) days.add(0,d);
            d=d.minusDays(1);
        }
        List<CompletedClose> b = new ArrayList<>();
        for(int i=0;i<days.size();i++) {
            Instant close = days.get(i).atTime(LocalTime.of(15,30))
                .atZone(ZoneId.of("Asia/Kolkata")).toInstant();
            b.add(new CompletedClose(days.get(i),SOURCE,n(i==34?last:"100"),
                close,close.plusSeconds(60),true));
        }
        return b;
    }
    static Decision evaluate(PutContract c,Holding h,List<CompletedClose> b,
                             EquityQuote spot,PutQuote put,SourceRights r,Assumptions a) {
        return FnoProtectivePutV1.evaluate(c,h,b,spot,put,r,a,NOW);
    }
    static Decision run() { return evaluate(CONTRACT,HOLD,bars("94"),SPOT,PUT,RIGHTS,A); }
    static void expect(Decision d,Status s,String code) {
        assertEquals(s,d.status());
        assertEquals(code,d.reasonCode());
        assertFalse(d.executable());
        assertFalse(d.orderPlaced());
        assertFalse(d.liveTradeAuthorized());
    }
    @Test void protectedSharesProduceResearchOnlyCandidate() {
        var d=run();
        assertEquals(Status.RESEARCH_CANDIDATE,d.status());
        assertEquals(EQ,d.underlyingKey());
        assertEquals(PE,d.putKey());
        assertEquals(n("4700.0000"),d.currentHoldingValue());
        assertTrue(d.putPremiumAndFees().signum()>0);
        assertTrue(d.hypotheticalProtectedExpiryValue()
            .compareTo(d.hypotheticalUnhedgedExpiryValue())>0);
        assertTrue(d.hypotheticalHedgeBenefit().compareTo(n("100"))>0);
        assertFalse(d.executable());
        assertFalse(d.orderPlaced());
        assertFalse(d.liveTradeAuthorized());
    }
    @Test void noDrawdownDoesNotProposeHedge() {
        expect(evaluate(CONTRACT,HOLD,bars("100"),SPOT,PUT,RIGHTS,A),
            Status.NO_SIGNAL,"PROTECTIVE_PUT_DRAWDOWN_TRIGGER_ABSENT");
    }
    @Test void missingOwnershipAndResearchRightsFailClosed() {
        expect(evaluate(CONTRACT,HOLD,bars("94"),SPOT,PUT,
            new SourceRights(true,true,true,false),A),
            Status.BLOCKED_RIGHTS,"PROTECTIVE_PUT_RIGHTS_UNVERIFIED");
        expect(evaluate(CONTRACT,new Holding(SOURCE,EQ,50,n("90"),
            NOW.minusSeconds(45),false,true),bars("94"),SPOT,PUT,RIGHTS,A),
            Status.BLOCKED_DATA,"PROTECTIVE_PUT_OWNERSHIP_CONTRACT_INVALID");
    }
    @Test void partialLotOrSyntheticIndexBasisRejected() {
        expect(evaluate(CONTRACT,new Holding(SOURCE,EQ,25,n("90"),
            NOW.minusSeconds(45),true,true),bars("94"),SPOT,PUT,RIGHTS,A),
            Status.BLOCKED_DATA,"PROTECTIVE_PUT_OWNERSHIP_CONTRACT_INVALID");
        var proxy=new PutContract(PE,"NSE_INDEX|Nifty_50","PE",n("95"),n("0.05"),
            CONTRACT.listedFrom(),EXPIRY,50,true,true);
        expect(evaluate(proxy,HOLD,bars("94"),SPOT,PUT,RIGHTS,A),
            Status.BLOCKED_DATA,"PROTECTIVE_PUT_OWNERSHIP_CONTRACT_INVALID");
    }
    @Test void callsAndUnverifiedPhysicalDeliveryCannotProtectShares() {
        var ce=new PutContract(PE,EQ,"CE",n("95"),n("0.05"),
            CONTRACT.listedFrom(),EXPIRY,50,true,true);
        expect(evaluate(ce,HOLD,bars("94"),SPOT,PUT,RIGHTS,A),
            Status.BLOCKED_DATA,"PROTECTIVE_PUT_OWNERSHIP_CONTRACT_INVALID");
        var mismatch=new PutContract(PE,EQ,"PE",n("95"),n("0.05"),
            CONTRACT.listedFrom(),EXPIRY,50,true,false);
        expect(evaluate(mismatch,HOLD,bars("94"),SPOT,PUT,RIGHTS,A),
            Status.BLOCKED_DATA,"PROTECTIVE_PUT_OWNERSHIP_CONTRACT_INVALID");
    }
    @Test void staleHoldingAndStaleOrForwardKnownQuoteRejected() {
        var stale=new Holding(SOURCE,EQ,50,n("90"),NOW.minusSeconds(500),true,true);
        expect(evaluate(CONTRACT,stale,bars("94"),SPOT,PUT,RIGHTS,A),
            Status.BLOCKED_DATA,"PROTECTIVE_PUT_OWNERSHIP_CONTRACT_INVALID");
        var bad=new PutQuote(SOURCE,PE,n("1.95"),n("2"),20,1200,
            NOW.minusSeconds(200),NOW.minusSeconds(195));
        expect(evaluate(CONTRACT,HOLD,bars("94"),SPOT,bad,RIGHTS,A),
            Status.BLOCKED_DATA,"PROTECTIVE_PUT_QUOTES_ASOF_INVALID");
        bad=new PutQuote(SOURCE,PE,n("1.95"),n("2"),20,1200,TICK,NOW.plusSeconds(1));
        expect(evaluate(CONTRACT,HOLD,bars("94"),SPOT,bad,RIGHTS,A),
            Status.BLOCKED_DATA,"PROTECTIVE_PUT_QUOTES_ASOF_INVALID");
    }
    @Test void staleCrossProviderMissingDepthAndSpreadRejected() {
        for(var q:List.of(
            new PutQuote("ANGEL_ONE_SMARTAPI",PE,n("1.95"),n("2"),20,1200,TICK,TICK.plusSeconds(10)),
            new PutQuote(SOURCE,PE,n("1.95"),n("2"),0,1200,TICK,TICK.plusSeconds(10)),
            new PutQuote(SOURCE,PE,n("1.95"),n("2"),20,0,TICK,TICK.plusSeconds(10)),
            new PutQuote(SOURCE,PE,n("1.00"),n("2"),20,1200,TICK,TICK.plusSeconds(10)))) {
            expect(evaluate(CONTRACT,HOLD,bars("94"),SPOT,q,RIGHTS,A),
                Status.BLOCKED_DATA,"PROTECTIVE_PUT_QUOTES_ASOF_INVALID");
        }
    }
    @Test void futureKnownAndDuplicateCompletedSessionRejected() {
        var b=bars("94");
        var last=b.getLast();
        b.set(34,new CompletedClose(last.session(),SOURCE,n("94"),
            last.exchangeCloseAt(),NOW.plusSeconds(1),true));
        expect(evaluate(CONTRACT,HOLD,b,SPOT,PUT,RIGHTS,A),
            Status.BLOCKED_DATA,"PROTECTIVE_PUT_COMPLETED_HISTORY_INVALID");
        b=bars("94");
        b.set(34,b.get(33));
        expect(evaluate(CONTRACT,HOLD,b,SPOT,PUT,RIGHTS,A),
            Status.BLOCKED_DATA,"PROTECTIVE_PUT_COMPLETED_HISTORY_INVALID");
    }
    @Test void excessivePremiumFailsBudgetWithoutOrder() {
        var a=new Assumptions(2000,300,50,300,2,50,1000,
            n("10"),n("100"),100,100);
        expect(evaluate(CONTRACT,HOLD,bars("94"),SPOT,PUT,RIGHTS,a),
            Status.NO_SIGNAL,"PROTECTIVE_PUT_PREMIUM_BUDGET_EXCEEDED");
    }
    @Test void scenarioBenefitIsNotPredictedReturn() {
        var a=new Assumptions(100,300,50,300,2,50,1000,
            n("10"),n("10000"),3000,100);
        expect(evaluate(CONTRACT,HOLD,bars("94"),SPOT,PUT,RIGHTS,a),
            Status.NO_SIGNAL,"PROTECTIVE_PUT_DOWNSIDE_SCENARIO_BENEFIT_TOO_LOW");
    }
    @Test void zeroFeesAndMalformedAssumptionsFailClosed() {
        var a=new Assumptions(2000,300,50,300,2,50,1000,
            n("0"),n("10000"),3000,100);
        expect(evaluate(CONTRACT,HOLD,bars("94"),SPOT,PUT,RIGHTS,a),
            Status.BLOCKED_DATA,"PROTECTIVE_PUT_OWNERSHIP_CONTRACT_INVALID");
    }
    @Test void catalogStrategyIdentityIsProvenByEvaluatorOutput() {
        assertEquals("strategy.STR-187", FnoProtectivePutV1.STRATEGY_ID);
        assertEquals(FnoProtectivePutV1.STRATEGY_ID, run().strategyId());
        assertFalse(run().executable());
        assertFalse(run().orderPlaced());
        assertFalse(run().liveTradeAuthorized());
    }
    @Test void repeatEvaluationIsDeterministicAndNonExecutable() {
        assertEquals(run(),run());
        assertFalse(run().liveTradeAuthorized());
    }
}
