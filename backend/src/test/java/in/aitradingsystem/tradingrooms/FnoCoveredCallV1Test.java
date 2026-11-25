package in.aitradingsystem.tradingrooms;

import static org.junit.jupiter.api.Assertions.*;

import in.aitradingsystem.tradingrooms.FnoCoveredCallV1.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FnoCoveredCallV1Test {
    static final Instant NOW = Instant.parse("2026-11-20T05:00:00Z");
    static final Instant TICK = NOW.minusSeconds(35);
    static final String PROVIDER = "UPSTOX_ANALYTICS";
    static final String EQ = "NSE_EQ|EXAMPLECO";
    static final String CE = "NSE_FO|EXAMPLECO_20261127_110_CE";
    static final CallContract CONTRACT = new CallContract(CE, EQ, "CE", n("110"),
        n("0.05"),LocalDate.of(2026,11,1),LocalDate.of(2026,11,27),50,true,true);
    static final Holding HOLD = new Holding(PROVIDER,EQ,50,n("90"),
        NOW.minusSeconds(45),true,true);
    static final EquityQuote SPOT = new EquityQuote(PROVIDER,EQ,n("104"),
        TICK,TICK.plusSeconds(10));
    static final CallQuote CALL = new CallQuote(PROVIDER,CE,n("2.50"),n("2.55"),
        20,1200,TICK,TICK.plusSeconds(10));
    static final SourceRights RIGHTS = new SourceRights(true,true,true,true);
    static final Assumptions ASSUMPTIONS = new Assumptions(1500,500,50,300,
        2,50,1000,n("10"),n("10000"),10000,50);
    static BigDecimal n(String v) {return new BigDecimal(v);}
    static List<CompletedClose> candles(String lastClose) {
        var dates=new ArrayList<LocalDate>();
        var day=LocalDate.of(2026,11,19);
        while(dates.size()<35) {
            if(day.getDayOfWeek().getValue()<=5)dates.add(0,day);
            day=day.minusDays(1);
        }
        var bars=new ArrayList<CompletedClose>();
        for(int i=0;i<dates.size();i++) {
            var close=dates.get(i).atTime(LocalTime.of(15,30))
                .atZone(ZoneId.of("Asia/Kolkata")).toInstant();
            bars.add(new CompletedClose(dates.get(i),PROVIDER,
                n(i==34?lastClose:"100"),close,close.plusSeconds(60),true));
        }
        return bars;
    }
    static Decision eval(CallContract c,Holding h,List<CompletedClose> b,
                          EquityQuote spot,CallQuote quote,SourceRights r,Assumptions a){
        return FnoCoveredCallV1.evaluate(c,h,b,spot,quote,r,a,NOW);
    }
    static Decision run(){
        return eval(CONTRACT,HOLD,candles("104"),SPOT,CALL,RIGHTS,ASSUMPTIONS);
    }
    static void expect(Decision d,Status s,String reason){
        assertEquals(s,d.status());
        assertEquals(reason,d.reasonCode());
        assertFalse(d.executable());
        assertFalse(d.orderPlaced());
        assertFalse(d.liveTradeAuthorized());
    }
    @Test void candidateModelsCappedUpsideAndExposedEquityDownside(){
        var d=run();
        assertEquals(Status.RESEARCH_CANDIDATE,d.status());
        assertEquals("strategy.STR-188",d.strategyId());
        assertEquals(EQ,d.underlyingKey());
        assertEquals(CE,d.callKey());
        assertEquals(n("5200.0000"),d.currentHoldingValue());
        assertTrue(d.callCreditAfterFees().signum()>0);
        assertTrue(d.hypotheticalStockOnlyExpiryValue()
            .compareTo(d.hypotheticalCoveredExpiryValue())>0);
        assertEquals(d.callCreditAfterFees(),d.hypotheticalCoveredExpiryValueAtZero());
        assertTrue(d.hypotheticalCoveredExpiryValueAtZero()
            .compareTo(d.currentHoldingValue())<0);
        assertFalse(d.executable());
    }
    @Test void noVerifiedOwnedGainProducesNoSignal(){
        expect(eval(CONTRACT,HOLD,candles("92"),SPOT,CALL,RIGHTS,ASSUMPTIONS),
            Status.NO_SIGNAL,"COVERED_CALL_OWNED_GAIN_TRIGGER_ABSENT");
    }
    @Test void independentHoldingsRightsRequired(){
        expect(eval(CONTRACT,HOLD,candles("104"),SPOT,CALL,
            new SourceRights(true,true,true,false),ASSUMPTIONS),
            Status.BLOCKED_RIGHTS,"COVERED_CALL_RIGHTS_UNVERIFIED");
    }
    @Test void actualSettledDeliverableLotRequired(){
        for(Holding h:List.of(
            new Holding(PROVIDER,EQ,25,n("90"),NOW.minusSeconds(45),true,true),
            new Holding(PROVIDER,EQ,50,n("90"),NOW.minusSeconds(45),false,true),
            new Holding(PROVIDER,EQ,50,n("90"),NOW.minusSeconds(500),true,true),
            new Holding(PROVIDER,EQ,50,n("90"),NOW.minusSeconds(45),true,false))) {
            expect(eval(CONTRACT,h,candles("104"),SPOT,CALL,RIGHTS,ASSUMPTIONS),
                Status.BLOCKED_DATA,"COVERED_CALL_OWNERSHIP_CONTRACT_INVALID");
        }
    }
    @Test void nakedOrIndexProxyOrWrongTypeNotCovered(){
        for(CallContract c:List.of(
            new CallContract(CE,EQ,"PE",n("110"),n("0.05"),
                CONTRACT.listedFrom(),CONTRACT.expiry(),50,true,true),
            new CallContract(CE,"NSE_INDEX|Nifty_50","CE",n("110"),n("0.05"),
                CONTRACT.listedFrom(),CONTRACT.expiry(),50,true,true),
            new CallContract(CE,EQ,"CE",n("110"),n("0.05"),
                CONTRACT.listedFrom(),CONTRACT.expiry(),50,true,false))) {
            expect(eval(c,HOLD,candles("104"),SPOT,CALL,RIGHTS,ASSUMPTIONS),
                Status.BLOCKED_DATA,"COVERED_CALL_OWNERSHIP_CONTRACT_INVALID");
        }
    }
    @Test void providerMismatchInsufficientBidDepthAndCrossedQuoteBlocked(){
        for(CallQuote q:List.of(
            new CallQuote("ANGEL_ONE_SMARTAPI",CE,n("2.5"),n("2.55"),20,1200,TICK,TICK.plusSeconds(10)),
            new CallQuote(PROVIDER,CE,n("2.5"),n("2.55"),0,1200,TICK,TICK.plusSeconds(10)),
            new CallQuote(PROVIDER,CE,n("2.6"),n("2.55"),20,1200,TICK,TICK.plusSeconds(10)),
            new CallQuote(PROVIDER,CE,n("2.5"),n("2.55"),20,0,TICK,TICK.plusSeconds(10)))) {
            expect(eval(CONTRACT,HOLD,candles("104"),SPOT,q,RIGHTS,ASSUMPTIONS),
                Status.BLOCKED_DATA,"COVERED_CALL_QUOTES_ASOF_INVALID");
        }
    }
    @Test void staleAndFutureKnownQuoteBlocked(){
        for(CallQuote q:List.of(
            new CallQuote(PROVIDER,CE,n("2.5"),n("2.55"),20,1200,NOW.minusSeconds(200),NOW.minusSeconds(195)),
            new CallQuote(PROVIDER,CE,n("2.5"),n("2.55"),20,1200,TICK,NOW.plusSeconds(1)))) {
            expect(eval(CONTRACT,HOLD,candles("104"),SPOT,q,RIGHTS,ASSUMPTIONS),
                Status.BLOCKED_DATA,"COVERED_CALL_QUOTES_ASOF_INVALID");
        }
    }
    @Test void lookaheadAndDuplicateHistoricalSessionsBlocked(){
        var bars=candles("104");var last=bars.getLast();
        bars.set(34,new CompletedClose(last.session(),PROVIDER,n("104"),
            last.exchangeCloseAt(),NOW.plusSeconds(1),true));
        expect(eval(CONTRACT,HOLD,bars,SPOT,CALL,RIGHTS,ASSUMPTIONS),
            Status.BLOCKED_DATA,"COVERED_CALL_COMPLETED_HISTORY_INVALID");
        bars=candles("104");bars.set(34,bars.get(33));
        expect(eval(CONTRACT,HOLD,bars,SPOT,CALL,RIGHTS,ASSUMPTIONS),
            Status.BLOCKED_DATA,"COVERED_CALL_COMPLETED_HISTORY_INVALID");
    }
    @Test void afterFeesCreditMustBeMeaningfullyPositive(){
        var poor=new CallQuote(PROVIDER,CE,n("0.10"),n("0.102"),
            20,1200,TICK,TICK.plusSeconds(10));
        expect(eval(CONTRACT,HOLD,candles("104"),SPOT,poor,RIGHTS,ASSUMPTIONS),
            Status.NO_SIGNAL,"COVERED_CALL_AFTER_COST_CREDIT_INSUFFICIENT");
    }
    @Test void fullOwnedStockValueNotPremiumMustFitNotionalBudget(){
        var a=new Assumptions(1500,500,50,300,2,50,1000,n("10"),n("1000"),1000,50);
        expect(eval(CONTRACT,HOLD,candles("104"),SPOT,CALL,RIGHTS,a),
            Status.NO_SIGNAL,"COVERED_CALL_OWNED_NOTIONAL_BUDGET_EXCEEDED");
    }
    @Test void feeRightsAndScenarioInputsFailClosed(){
        var a=new Assumptions(1500,500,50,300,2,50,1000,n("0"),n("10000"),10000,50);
        expect(eval(CONTRACT,HOLD,candles("104"),SPOT,CALL,RIGHTS,a),
            Status.BLOCKED_DATA,"COVERED_CALL_OWNERSHIP_CONTRACT_INVALID");
        assertEquals(run(),run());
        assertFalse(run().liveTradeAuthorized());
    }
}
