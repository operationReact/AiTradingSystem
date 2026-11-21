package in.aitradingsystem.tradingrooms;

import static org.junit.jupiter.api.Assertions.*;
import in.aitradingsystem.tradingrooms.FnoVolatilityCrushV1.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FnoVolatilityCrushV1Test {
    static final Instant NOW=Instant.parse("2026-11-20T05:00:00Z");
    static final Instant PRE_TIME=Instant.parse("2026-11-20T03:45:00Z");
    static final Instant POST_TIME=Instant.parse("2026-11-20T04:59:20Z");
    static final Instant EVENT_START=Instant.parse("2026-11-20T04:00:00Z");
    static final Instant EVENT_END=Instant.parse("2026-11-20T04:15:00Z");
    static final Instant SCHEDULE_KNOWN=Instant.parse("2026-11-19T09:30:00Z");
    static final String PROVIDER="UPSTOX_ANALYTICS";
    static final String INDEX=FnoLongOptionCrossoverV1.UNDERLYING;
    static final Event EVENT=new Event("RBI_TEST_20261120","RBI_POLICY",
        "RBI_OFFICIAL_CALENDAR",SCHEDULE_KNOWN,EVENT_START,EVENT_END,true);
    static final Rights RIGHTS=new Rights(true,true,true,true,true);
    static final Assumptions A=new Assumptions(35,90,24,180,200,n("5"),1500,600);
    static BigDecimal n(String v){return new BigDecimal(v);}

    static List<CompletedCandle> bars(){
        var days=new ArrayList<LocalDate>();
        var day=LocalDate.of(2026,11,19);
        while(days.size()<40){
            if(day.getDayOfWeek().getValue()<=5)days.add(0,day);
            day=day.minusDays(1);
        }
        var result=new ArrayList<CompletedCandle>();
        for(var d:days){
            var closeAt=d.atTime(LocalTime.of(15,30))
                .atZone(ZoneId.of("Asia/Kolkata")).toInstant();
            result.add(new CompletedCandle(d,PROVIDER,n("100"),n("102"),n("98"),
                n("100"),closeAt,closeAt.plusSeconds(60),true));
        }
        return result;
    }
    static Observation pre(String iv,String spot){
        return new Observation(PROVIDER,INDEX,n(spot),n(iv),30,
            FnoVolatilityCrushV1.IV_METHOD,PRE_TIME,PRE_TIME.plusSeconds(10),true);
    }
    static Observation post(String iv,String spot){
        return new Observation(PROVIDER,INDEX,n(spot),n(iv),30,
            FnoVolatilityCrushV1.IV_METHOD,POST_TIME,POST_TIME.plusSeconds(10),true);
    }
    static Decision evaluate(Event e,List<CompletedCandle> history,Observation before,
                             Observation after,Rights rights,Assumptions a){
        return FnoVolatilityCrushV1.evaluate(e,history,before,after,rights,a,NOW);
    }
    static Decision run(){
        return evaluate(EVENT,bars(),pre("36","100"),post("22","102"),RIGHTS,A);
    }
    static void state(Decision d,Status expected,String reason) {
        assertEquals(expected,d.status());
        assertEquals(reason,d.reasonCode());
        assertEquals("strategy.STR-202",d.strategyId());
        assertTrue(d.retrospectiveOnly());
        assertFalse(d.executable());
        assertFalse(d.orderPlaced());
        assertFalse(d.liveTradeAuthorized());
    }
    @Test void verifiedEventObservedPrePostThirtyDayIvDropIsRetrospectiveOnly(){
        var d=run();
        state(d,Status.RESEARCH_CANDIDATE,"VOL_CRUSH_POST_EVENT_OBSERVATION_ONLY");
        assertEquals(EVENT.eventKey(),d.verifiedEventKey());
        assertEquals(PRE_TIME,d.preEventQuoteAt());
        assertEquals(POST_TIME,d.postEventQuoteAt());
        assertEquals(0,d.observedIvDropPoints().compareTo(n("14.0000")));
        assertEquals(0,d.relativeIvDropBps().compareTo(n("3888.8889")));
        assertEquals(0,d.observedAbsIndexMoveBps().compareTo(n("200.0000")));
        assertEquals(0,d.preEventIvPercent().compareTo(n("36")));
        assertEquals(0,d.postEventIvPercent().compareTo(n("22")));
    }
    @Test void fiveIndependentDataAndEventRightsAreRequired(){
        for(var bad:List.of(new Rights(false,true,true,true,true),
            new Rights(true,false,true,true,true),
            new Rights(true,true,false,true,true),
            new Rights(true,true,true,false,true),
            new Rights(true,true,true,true,false))){
            state(evaluate(EVENT,bars(),pre("36","100"),post("22","102"),bad,A),
                Status.BLOCKED_RIGHTS,"VOL_CRUSH_RIGHTS_UNVERIFIED");
        }
    }
    @Test void eventMustHaveAuthenticKnownScheduleBeforeReleaseAndCompletion(){
        for(var e:List.of(
            new Event(EVENT.eventKey(),EVENT.eventCategory(),EVENT.calendarProvider(),
                SCHEDULE_KNOWN,EVENT_START,EVENT_END,false),
            new Event(EVENT.eventKey(),"UNVERIFIED_RUMOR",EVENT.calendarProvider(),
                SCHEDULE_KNOWN,EVENT_START,EVENT_END,true),
            new Event(EVENT.eventKey(),EVENT.eventCategory(),"UNKNOWN_CALENDAR",
                SCHEDULE_KNOWN,EVENT_START,EVENT_END,true),
            new Event(EVENT.eventKey(),EVENT.eventCategory(),EVENT.calendarProvider(),
                EVENT_START.plusSeconds(1),EVENT_START,EVENT_END,true),
            new Event(EVENT.eventKey(),EVENT.eventCategory(),EVENT.calendarProvider(),
                SCHEDULE_KNOWN,EVENT_END,EVENT_START,true),
            new Event(EVENT.eventKey(),EVENT.eventCategory(),EVENT.calendarProvider(),
                SCHEDULE_KNOWN,EVENT_START,NOW.plusSeconds(1),true)
        )){
            state(evaluate(e,bars(),pre("36","100"),post("22","102"),RIGHTS,A),
                Status.BLOCKED_DATA,"VOL_CRUSH_EVENT_OR_ASSUMPTIONS_INVALID");
        }
    }
    @Test void historicalCompletedCandlesMustPrecedeAnnouncementToPreventLookahead(){
        var h=bars();var last=h.getLast();
        h.set(h.size()-1,new CompletedCandle(last.session(),PROVIDER,last.open(),
            last.high(),last.low(),last.close(),last.exchangeCloseAt(),
            EVENT_START.plusSeconds(1),true));
        state(evaluate(EVENT,h,pre("36","100"),post("22","102"),RIGHTS,A),
            Status.BLOCKED_DATA,"VOL_CRUSH_HISTORY_INVALID");
        h=bars();h.set(h.size()-1,h.get(h.size()-2));
        state(evaluate(EVENT,h,pre("36","100"),post("22","102"),RIGHTS,A),
            Status.BLOCKED_DATA,"VOL_CRUSH_HISTORY_INVALID");
    }
    @Test void independentlyVerifiedSameSourceAndValidOhlcClosesRequired(){
        var h=bars();var c=h.get(0);
        h.set(0,new CompletedCandle(c.session(),PROVIDER,c.open(),n("99"),c.low(),
            c.close(),c.exchangeCloseAt(),c.firstKnownAt(),true));
        state(evaluate(EVENT,h,pre("36","100"),post("22","102"),RIGHTS,A),
            Status.BLOCKED_DATA,"VOL_CRUSH_HISTORY_INVALID");
        h=bars();c=h.get(0);
        h.set(0,new CompletedCandle(c.session(),"ANGEL_ONE_SMARTAPI",c.open(),c.high(),
            c.low(),c.close(),c.exchangeCloseAt(),c.firstKnownAt(),true));
        state(evaluate(EVENT,h,pre("36","100"),post("22","102"),RIGHTS,A),
            Status.BLOCKED_DATA,"VOL_CRUSH_HISTORY_INVALID");
    }
    @Test void eventPreQuoteCannotBeOldFutureKnownOrAfterStart(){
        for(var bad:List.of(
            new Observation(PROVIDER,INDEX,n("100"),n("36"),30,FnoVolatilityCrushV1.IV_METHOD,
                EVENT_START.plusSeconds(1),EVENT_START.plusSeconds(2),true),
            new Observation(PROVIDER,INDEX,n("100"),n("36"),30,FnoVolatilityCrushV1.IV_METHOD,
                PRE_TIME,EVENT_START.plusSeconds(1),true),
            new Observation(PROVIDER,INDEX,n("100"),n("36"),30,FnoVolatilityCrushV1.IV_METHOD,
                Instant.parse("2026-11-18T04:00:00Z"),
                Instant.parse("2026-11-18T04:00:10Z"),true)
        )){
            state(evaluate(EVENT,bars(),bad,post("22","102"),RIGHTS,A),
                Status.BLOCKED_DATA,"VOL_CRUSH_SOURCE_IV_OR_TIMING_INVALID");
        }
    }
    @Test void eventScheduleMustAlreadyBeKnownAtThePreQuote(){
        var lateSchedule=new Event(EVENT.eventKey(),EVENT.eventCategory(),EVENT.calendarProvider(),
            PRE_TIME.plusSeconds(30),EVENT_START,EVENT_END,true);
        state(evaluate(lateSchedule,bars(),pre("36","100"),post("22","102"),RIGHTS,A),
            Status.BLOCKED_DATA,"VOL_CRUSH_SOURCE_IV_OR_TIMING_INVALID");
    }
    @Test void postQuoteCannotPrecedeEventCompletionOrBeFutureOrStale(){
        for(var bad:List.of(
            new Observation(PROVIDER,INDEX,n("102"),n("22"),30,FnoVolatilityCrushV1.IV_METHOD,
                EVENT_END.minusSeconds(1),EVENT_END,true),
            new Observation(PROVIDER,INDEX,n("102"),n("22"),30,FnoVolatilityCrushV1.IV_METHOD,
                POST_TIME,NOW.plusSeconds(1),true),
            new Observation(PROVIDER,INDEX,n("102"),n("22"),30,FnoVolatilityCrushV1.IV_METHOD,
                NOW.minusSeconds(200),NOW.minusSeconds(190),true)
        )){
            state(evaluate(EVENT,bars(),pre("36","100"),bad,RIGHTS,A),
                Status.BLOCKED_DATA,"VOL_CRUSH_SOURCE_IV_OR_TIMING_INVALID");
        }
    }
    @Test void bothIvPointsNeedSameLicensedThirtyDayAtmNormalization(){
        for(var bad:List.of(
            new Observation(PROVIDER,INDEX,n("102"),n("22"),14,FnoVolatilityCrushV1.IV_METHOD,
                POST_TIME,POST_TIME.plusSeconds(10),true),
            new Observation(PROVIDER,INDEX,n("102"),n("22"),30,"SYNTHETIC_VIX_PROXY",
                POST_TIME,POST_TIME.plusSeconds(10),true),
            new Observation(PROVIDER,INDEX,n("102"),n("0"),30,FnoVolatilityCrushV1.IV_METHOD,
                POST_TIME,POST_TIME.plusSeconds(10),true),
            new Observation(PROVIDER,INDEX,n("102"),n("201"),30,FnoVolatilityCrushV1.IV_METHOD,
                POST_TIME,POST_TIME.plusSeconds(10),true),
            new Observation(PROVIDER,INDEX,n("102"),n("22"),30,FnoVolatilityCrushV1.IV_METHOD,
                POST_TIME,POST_TIME.plusSeconds(10),false),
            new Observation("ANGEL_ONE_SMARTAPI",INDEX,n("102"),n("22"),30,
                FnoVolatilityCrushV1.IV_METHOD,POST_TIME,POST_TIME.plusSeconds(10),true)
        )){
            state(evaluate(EVENT,bars(),pre("36","100"),bad,RIGHTS,A),
                Status.BLOCKED_DATA,"VOL_CRUSH_SOURCE_IV_OR_TIMING_INVALID");
        }
    }
    @Test void ivIncreaseOrEqualIsNotClassifiedAsCrush(){
        for(var iv:List.of("36","40")){
            var d=evaluate(EVENT,bars(),pre("36","100"),post(iv,"102"),RIGHTS,A);
            state(d,Status.NO_SIGNAL,"VOL_CRUSH_NO_OBSERVED_IV_DROP");
        }
    }
    @Test void insufficientAbsoluteOrRelativeDropRejected(){
        state(evaluate(EVENT,bars(),pre("36","100"),post("33","102"),RIGHTS,A),
            Status.NO_SIGNAL,"VOL_CRUSH_DROP_TOO_SMALL");
        var strict=new Assumptions(35,90,24,180,200,n("5"),3000,600);
        state(evaluate(EVENT,bars(),pre("36","100"),post("30","102"),RIGHTS,strict),
            Status.NO_SIGNAL,"VOL_CRUSH_DROP_TOO_SMALL");
    }
    @Test void largeVerifiedIndexMoveCannotBeMisreportedAsQuietPostEventCrush(){
        var d=evaluate(EVENT,bars(),pre("36","100"),post("22","112"),RIGHTS,A);
        state(d,Status.NO_SIGNAL,"VOL_CRUSH_INDEX_SHOCK_EXCEEDS_RESEARCH_GATE");
        assertEquals(0,d.observedAbsIndexMoveBps().compareTo(n("1200.0000")));
    }
    @Test void minimumIndependentCompletedIndexSamplesCannotBeFaked(){
        var h=bars();
        state(evaluate(EVENT,h.subList(0,30),pre("36","100"),post("22","102"),RIGHTS,A),
            Status.BLOCKED_DATA,"VOL_CRUSH_HISTORY_INVALID");
        var strict=new Assumptions(100,90,24,180,200,n("5"),1500,600);
        state(evaluate(EVENT,bars(),pre("36","100"),post("22","102"),RIGHTS,strict),
            Status.BLOCKED_DATA,"VOL_CRUSH_HISTORY_INVALID");
    }
    @Test void invalidResearchThresholdAssumptionsBlockRatherThanTrade(){
        var invalid=new Assumptions(35,90,24,180,200,n("0"),1500,600);
        state(evaluate(EVENT,bars(),pre("36","100"),post("22","102"),RIGHTS,invalid),
            Status.BLOCKED_DATA,"VOL_CRUSH_EVENT_OR_ASSUMPTIONS_INVALID");
    }
    @Test void noExecutionForAnyStateAndDeterministicDecisions(){
        assertEquals(run(),run());
        state(run(),Status.RESEARCH_CANDIDATE,"VOL_CRUSH_POST_EVENT_OBSERVATION_ONLY");
        assertTrue(run().retrospectiveOnly());
        assertNull(evaluate(EVENT,bars(),pre("36","100"),post("22","102"),
            new Rights(false,true,true,true,true),A).verifiedEventKey());
    }
}
