package in.aitradingsystem.tradingrooms;

import static org.junit.jupiter.api.Assertions.*;

import in.aitradingsystem.tradingrooms.FnoEquityCollarV1.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FnoEquityCollarV1Test {
    static final Instant NOW = Instant.parse("2026-11-20T05:00:00Z");
    static final Instant TICK = NOW.minusSeconds(35);
    static final String PROVIDER = "UPSTOX_ANALYTICS";
    static final String EQ = "NSE_EQ|EXAMPLECO";
    static final String PE = "NSE_FO|EXAMPLECO_20261127_95_PE";
    static final String CE = "NSE_FO|EXAMPLECO_20261127_110_CE";
    static final LocalDate EXPIRY = LocalDate.of(2026, 11, 27);
    static final Contract PUT = new Contract(PE,EQ,"PE",n("95"),n("0.05"),
        LocalDate.of(2026,11,1),EXPIRY,50,true,true);
    static final Contract CALL = new Contract(CE,EQ,"CE",n("110"),n("0.05"),
        LocalDate.of(2026,11,1),EXPIRY,50,true,true);
    static final Holding HOLD = new Holding(PROVIDER,EQ,50,n("90"),NOW.minusSeconds(45),true,true);
    static final Quote EQUITY = new Quote(PROVIDER,EQ,n("94"),n("94.05"),
        100,100,100000,TICK,TICK.plusSeconds(10));
    static final Quote PUT_Q = new Quote(PROVIDER,PE,n("2.00"),n("2.05"),
        10,10,1000,TICK,TICK.plusSeconds(10));
    static final Quote CALL_Q = new Quote(PROVIDER,CE,n("2.40"),n("2.45"),
        10,10,1000,TICK,TICK.plusSeconds(10));
    static final Rights RIGHTS = new Rights(true,true,true,true);
    static final Assumptions A = new Assumptions(2000,300,50,50,300,2,50,2000,
        n("15"),n("10000"),10000,3000);
    static BigDecimal n(String v){return new BigDecimal(v);}
    static List<Bar> candles(String close){
        var dates=new ArrayList<LocalDate>();
        var day=LocalDate.of(2026,11,19);
        while(dates.size()<35){
            if(day.getDayOfWeek().getValue()<=5)dates.add(0,day);
            day=day.minusDays(1);
        }
        var result=new ArrayList<Bar>();
        for(int i=0;i<dates.size();i++){
            var at=dates.get(i).atTime(LocalTime.of(15,30))
                .atZone(ZoneId.of("Asia/Kolkata")).toInstant();
            result.add(new Bar(dates.get(i),PROVIDER,n(i==34?close:"100"),
                at,at.plusSeconds(60),true));
        }
        return result;
    }
    static Decision eval(Contract put,Contract call,Holding hold,List<Bar> bars,
                         Quote spot,Quote putQuote,Quote callQuote,Rights rights,Assumptions a){
        return FnoEquityCollarV1.evaluate(put,call,hold,bars,spot,putQuote,callQuote,rights,a,NOW);
    }
    static Decision run(){
        return eval(PUT,CALL,HOLD,candles("94"),EQUITY,PUT_Q,CALL_Q,RIGHTS,A);
    }
    static void expect(Decision d,Status status,String reason){
        assertEquals(status,d.status());
        assertEquals(reason,d.reasonCode());
        assertFalse(d.executable());
        assertFalse(d.orderPlaced());
        assertFalse(d.liveTradeAuthorized());
    }
    @Test void provenThreeLegCollarHasPayoffFloorAndCeilingButNeverOrders(){
        var d=run();
        assertEquals(Status.RESEARCH_CANDIDATE,d.status());
        assertEquals("strategy.STR-189",d.strategyId());
        assertEquals(EQ,d.underlyingKey());
        assertEquals(PE,d.longPutKey());
        assertEquals(CE,d.shortCallKey());
        assertTrue(d.theoreticalExpiryFloorValue().compareTo(
            d.theoreticalExpiryCeilingValue())<0);
        assertEquals(n("750.0000"),d.theoreticalExpiryCeilingValue()
            .subtract(d.theoreticalExpiryFloorValue()));
        assertTrue(d.hypotheticalCollarExpiryValue().compareTo(
            d.hypotheticalEquityOnlyExpiryValue())>0);
        assertFalse(d.executable());
        assertFalse(d.orderPlaced());
        assertFalse(d.liveTradeAuthorized());
    }
    @Test void noCompletedSessionDrawdownMeansNoNewResearchSignal(){
        expect(eval(PUT,CALL,HOLD,candles("100"),EQUITY,PUT_Q,CALL_Q,RIGHTS,A),
            Status.NO_SIGNAL,"COLLAR_RISK_TRIGGER_ABSENT");
    }
    @Test void independentHoldingsAndOptionResearchRightsRequired(){
        expect(eval(PUT,CALL,HOLD,candles("94"),EQUITY,PUT_Q,CALL_Q,
            new Rights(true,true,true,false),A),
            Status.BLOCKED_RIGHTS,"COLLAR_RIGHTS_UNVERIFIED");
    }
    @Test void unmatchedContractExpiryLotAndOptionTypesRejected(){
        var invalid=new Contract(CE,EQ,"PE",n("110"),n("0.05"),
            CALL.listedFrom(),EXPIRY,50,true,true);
        expect(eval(PUT,invalid,HOLD,candles("94"),EQUITY,PUT_Q,CALL_Q,RIGHTS,A),
            Status.BLOCKED_DATA,"COLLAR_CONTRACT_SHARES_INVALID");
        invalid=new Contract(CE,EQ,"CE",n("110"),n("0.05"),
            CALL.listedFrom(),EXPIRY,25,true,true);
        expect(eval(PUT,invalid,HOLD,candles("94"),EQUITY,PUT_Q,CALL_Q,RIGHTS,A),
            Status.BLOCKED_DATA,"COLLAR_CONTRACT_SHARES_INVALID");
        invalid=new Contract(CE,EQ,"CE",n("110"),n("0.05"),
            CALL.listedFrom(),EXPIRY.plusDays(7),50,true,true);
        expect(eval(PUT,invalid,HOLD,candles("94"),EQUITY,PUT_Q,CALL_Q,RIGHTS,A),
            Status.BLOCKED_DATA,"COLLAR_CONTRACT_SHARES_INVALID");
    }
    @Test void ownershipMustBeSettledUnrestrictedAndExactLot(){
        for(var h:List.of(
            new Holding(PROVIDER,EQ,25,n("90"),NOW.minusSeconds(45),true,true),
            new Holding(PROVIDER,EQ,50,n("90"),NOW.minusSeconds(45),true,false),
            new Holding(PROVIDER,EQ,50,n("90"),NOW.minusSeconds(45),false,true),
            new Holding(PROVIDER,EQ,50,n("90"),NOW.minusSeconds(450),true,true))){
            expect(eval(PUT,CALL,h,candles("94"),EQUITY,PUT_Q,CALL_Q,RIGHTS,A),
                Status.BLOCKED_DATA,"COLLAR_CONTRACT_SHARES_INVALID");
        }
    }
    @Test void indexPutOrUnverifiedPhysicalMappingRejected(){
        var proxy=new Contract(PE,"NSE_INDEX|Nifty_50","PE",n("95"),n("0.05"),
            PUT.listedFrom(),EXPIRY,50,true,true);
        expect(eval(proxy,CALL,HOLD,candles("94"),EQUITY,PUT_Q,CALL_Q,RIGHTS,A),
            Status.BLOCKED_DATA,"COLLAR_CONTRACT_SHARES_INVALID");
        proxy=new Contract(PE,EQ,"PE",n("95"),n("0.05"),
            PUT.listedFrom(),EXPIRY,50,true,false);
        expect(eval(proxy,CALL,HOLD,candles("94"),EQUITY,PUT_Q,CALL_Q,RIGHTS,A),
            Status.BLOCKED_DATA,"COLLAR_CONTRACT_SHARES_INVALID");
    }
    @Test void crossedOrThinOrCrossProviderOptionQuotesBlocked(){
        for(var q:List.of(
            new Quote(PROVIDER,PE,n("2.5"),n("2.0"),10,10,1000,TICK,TICK.plusSeconds(10)),
            new Quote(PROVIDER,PE,n("2.0"),n("2.05"),10,0,1000,TICK,TICK.plusSeconds(10)),
            new Quote(PROVIDER,PE,n("2.0"),n("2.05"),10,10,0,TICK,TICK.plusSeconds(10)),
            new Quote("ANGEL_ONE_SMARTAPI",PE,n("2.0"),n("2.05"),10,10,1000,TICK,TICK.plusSeconds(10)))){
            expect(eval(PUT,CALL,HOLD,candles("94"),EQUITY,q,CALL_Q,RIGHTS,A),
                Status.BLOCKED_DATA,"COLLAR_QUOTES_OR_ASOF_INVALID");
        }
    }
    @Test void staleAndFutureFirstKnownLegSnapshotsRejected(){
        var old=new Quote(PROVIDER,CE,n("2.4"),n("2.45"),10,10,1000,
            NOW.minusSeconds(200),NOW.minusSeconds(190));
        expect(eval(PUT,CALL,HOLD,candles("94"),EQUITY,PUT_Q,old,RIGHTS,A),
            Status.BLOCKED_DATA,"COLLAR_QUOTES_OR_ASOF_INVALID");
        var future=new Quote(PROVIDER,CE,n("2.4"),n("2.45"),10,10,1000,
            TICK,NOW.plusSeconds(1));
        expect(eval(PUT,CALL,HOLD,candles("94"),EQUITY,PUT_Q,future,RIGHTS,A),
            Status.BLOCKED_DATA,"COLLAR_QUOTES_OR_ASOF_INVALID");
    }
    @Test void historyLookaheadDuplicateSessionAndProviderChangesBlocked(){
        var b=candles("94");
        var l=b.getLast();
        b.set(34,new Bar(l.session(),PROVIDER,l.close(),l.exchangeCloseAt(),
            NOW.plusSeconds(1),true));
        expect(eval(PUT,CALL,HOLD,b,EQUITY,PUT_Q,CALL_Q,RIGHTS,A),
            Status.BLOCKED_DATA,"COLLAR_COMPLETED_EQUITY_HISTORY_INVALID");
        b=candles("94");b.set(34,b.get(33));
        expect(eval(PUT,CALL,HOLD,b,EQUITY,PUT_Q,CALL_Q,RIGHTS,A),
            Status.BLOCKED_DATA,"COLLAR_COMPLETED_EQUITY_HISTORY_INVALID");
    }
    @Test void feesAndStockNotionalBudgetFailClosed(){
        var bad=new Assumptions(2000,300,50,50,300,2,50,2000,
            n("0"),n("10000"),10000,3000);
        expect(eval(PUT,CALL,HOLD,candles("94"),EQUITY,PUT_Q,CALL_Q,RIGHTS,bad),
            Status.BLOCKED_DATA,"COLLAR_CONTRACT_SHARES_INVALID");
        var tiny=new Assumptions(2000,300,50,50,300,2,50,2000,
            n("15"),n("1000"),10000,3000);
        expect(eval(PUT,CALL,HOLD,candles("94"),EQUITY,PUT_Q,CALL_Q,RIGHTS,tiny),
            Status.NO_SIGNAL,"COLLAR_STOCK_NOTIONAL_EXCEEDS_BUDGET");
    }
    @Test void excessiveNetDebitCannotBeApproved(){
        var costly=new Quote(PROVIDER,PE,n("20"),n("20.2"),10,10,1000,
            TICK,TICK.plusSeconds(10));
        var a=new Assumptions(2000,300,50,50,300,2,50,2000,
            n("15"),n("10000"),10000,100);
        expect(eval(PUT,CALL,HOLD,candles("94"),EQUITY,costly,CALL_Q,RIGHTS,a),
            Status.NO_SIGNAL,"COLLAR_COST_EXCEEDS_BUDGET");
    }
    @Test void resultIsDeterministicAndCannotAuthorizeLiveTrading(){
        assertEquals(run(),run());
        assertFalse(run().liveTradeAuthorized());
    }
}
