package in.aitradingsystem.tradingrooms;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import in.aitradingsystem.marketdata.FnoVerifiedNiftyVolCrushSourceStore;
import in.aitradingsystem.marketdata.FnoVerifiedNiftyVolCrushSourceStore.*;
import in.aitradingsystem.marketdata.MarketSessionCalendar;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Original-shape synthetic fixtures, NOT original event/replay E2E evidence. */
class FnoVolatilityCrushResearchServiceTest {
    static final Instant NOW=FnoVolatilityCrushV1Test.NOW;
    static final String KEY=FnoVolatilityCrushV1Test.EVENT.eventKey();
    static final String PROVIDER=FnoVolatilityCrushV1Test.PROVIDER;
    static MarketSessionCalendar calendar(){
        var c=mock(MarketSessionCalendar.class);
        when(c.isTradingDay(any(LocalDate.class))).thenReturn(true);
        return c;
    }
    static Source evidence(){
        var event=FnoVolatilityCrushV1Test.EVENT;
        var pre=FnoVolatilityCrushV1Test.pre("36","100");
        var post=FnoVolatilityCrushV1Test.post("22","102");
        var h=new ArrayList<Historical>();
        for(var b:FnoVolatilityCrushV1Test.bars())
            h.add(new Historical(b.session(),b.provider(),b.open(),b.high(),
                b.low(),b.close(),b.exchangeCloseAt(),b.firstKnownAt()));
        return new Source(new Event(17,PROVIDER,event.eventKey(),event.eventCategory(),
            event.calendarProvider(),event.scheduleFirstKnownAt(),event.eventStart(),
            event.eventEnd(),NOW.minusSeconds(25)),
            new Snapshot(PROVIDER,pre.indexSpot(),pre.atmIvPercent(),
                pre.exchangeAt(),pre.firstKnownAt()),
            new Snapshot(PROVIDER,post.indexSpot(),post.atmIvPercent(),
                post.exchangeAt(),post.firstKnownAt()),List.copyOf(h));
    }
    static FnoVerifiedNiftyVolCrushSourceStore source(){
        var s=mock(FnoVerifiedNiftyVolCrushSourceStore.class);
        when(s.oneCurrent(KEY,NOW)).thenReturn(Optional.of(evidence()));
        return s;
    }
    static FnoVolatilityCrushResearchService service(
        FnoVerifiedNiftyVolCrushSourceStore store,boolean enabled){
        return new FnoVolatilityCrushResearchService(store,calendar(),
            enabled,enabled,enabled,enabled,enabled,KEY);
    }
    @Test void officialScheduleBeforeEventAndGenuinePrePostIvIsRetrospectiveOnly(){
        var store=source();var result=service(store,true).evaluate(NOW);
        assertEquals("strategy.STR-202",result.strategyId());
        assertEquals("RESEARCH_CANDIDATE",result.status());
        assertEquals("VOL_CRUSH_POST_EVENT_OBSERVATION_ONLY",result.reasonCode());
        assertEquals(2,result.freshQuotes());
        assertEquals(40,result.historySessions());
        assertEquals("ORIGINAL_OFFICIAL_EVENT_PRE_POST_30D_IV_OBSERVATION_RETROSPECTIVE_NO_ORDER",
            result.inputState());
        verify(store).oneCurrent(KEY,NOW);
        var disabled=FnoVolatilityCrushV1.evaluate(null,List.of(),null,null,
            new FnoVolatilityCrushV1.Rights(false,false,false,false,false),null,NOW);
        assertTrue(disabled.retrospectiveOnly());
        assertFalse(disabled.orderPlaced());assertFalse(disabled.executable());
        assertFalse(disabled.liveTradeAuthorized());
    }
    @Test void rightsDeniedNeverReadsOfficialEventOrPrivateProvider(){
        var store=mock(FnoVerifiedNiftyVolCrushSourceStore.class);
        var row=service(store,false).evaluate(NOW);
        assertEquals("BLOCKED_RIGHTS",row.status());
        assertEquals("VOL_CRUSH_ORIGINAL_EVENT_AND_IV_RIGHTS_UNVERIFIED",row.reasonCode());
        verifyNoInteractions(store);
    }
    @Test void absentOriginalEventHistoryOrPrePostIvCannotBeInvented(){
        var store=source();
        when(store.oneCurrent(KEY,NOW)).thenReturn(Optional.empty());
        assertEquals("VOL_CRUSH_EVENT_PRE_POST_IV_AND_OHLC_MISSING",
            service(store,true).evaluate(NOW).reasonCode());
        var p=evidence();
        when(store.oneCurrent(KEY,NOW)).thenReturn(Optional.of(new Source(
            p.event(),p.pre(),p.post(),p.history().subList(0,34))));
        assertEquals("VOL_CRUSH_EVENT_SOURCE_OR_TIMING_INVALID",
            service(store,true).evaluate(NOW).reasonCode());
    }
    @Test void eventScheduleDisclosedAfterEventIsBlocked(){
        var store=source();var p=evidence();var e=p.event();
        when(store.oneCurrent(KEY,NOW)).thenReturn(Optional.of(new Source(
            new Event(e.evidenceId(),e.provider(),e.eventKey(),e.category(),
                e.calendarProvider(),e.startAt().plusSeconds(1),
                e.startAt(),e.endAt(),e.firstKnownAt()),
            p.pre(),p.post(),p.history())));
        assertEquals("VOL_CRUSH_EVENT_SOURCE_OR_TIMING_INVALID",
            service(store,true).evaluate(NOW).reasonCode());
    }
    @Test void postEventObservationCannotBeRetrospectivelyRelabeledPreEvent(){
        var store=source();var p=evidence();var q=p.post();
        when(store.oneCurrent(KEY,NOW)).thenReturn(Optional.of(new Source(
            p.event(),p.pre(),new Snapshot(q.provider(),q.spot(),
                q.annualized30DayIv(),p.event().startAt(),
                q.firstKnownAt()),p.history())));
        assertEquals("VOL_CRUSH_EVENT_SOURCE_OR_TIMING_INVALID",
            service(store,true).evaluate(NOW).reasonCode());
    }
    @Test void preEventProviderMismatchAndMissingOriginalIvFailClosed(){
        var store=source();var p=evidence();var q=p.pre();
        when(store.oneCurrent(KEY,NOW)).thenReturn(Optional.of(new Source(
            p.event(),new Snapshot("ANGEL_ONE_SMARTAPI",q.spot(),
                q.annualized30DayIv(),q.exchangeAt(),q.firstKnownAt()),
            p.post(),p.history())));
        assertEquals("VOL_CRUSH_EVENT_SOURCE_OR_TIMING_INVALID",
            service(store,true).evaluate(NOW).reasonCode());
        when(store.oneCurrent(KEY,NOW)).thenReturn(Optional.of(new Source(
            p.event(),new Snapshot(PROVIDER,q.spot(),BigDecimal.ZERO,
                q.exchangeAt(),q.firstKnownAt()),p.post(),p.history())));
        assertEquals("VOL_CRUSH_EVENT_SOURCE_OR_TIMING_INVALID",
            service(store,true).evaluate(NOW).reasonCode());
    }
    @Test void runtimeSourceExceptionIsRedactedAndCannotAuthorizeTrade(){
        var store=source();
        when(store.oneCurrent(KEY,NOW)).thenThrow(new IllegalStateException("secret SSM token"));
        var row=service(store,true).evaluate(NOW);
        assertEquals("VOL_CRUSH_ORIGINAL_EVENT_SOURCE_READ_FAILED",row.reasonCode());
        assertFalse(row.toString().contains("secret"));
    }
}
