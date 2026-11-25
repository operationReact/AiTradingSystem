package in.aitradingsystem.tradingrooms;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import in.aitradingsystem.marketdata.FnoVerifiedCompletedOhlcvOiStore;
import in.aitradingsystem.marketdata.FnoHistoricalSessionCalendar;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class Fno176VerifiedHistoricalReplayServiceTest {
    private static final String KEY = "NSE_FO|FUT_DEC_176";
    private static final String HASH = "a".repeat(64);
    private static final Instant AS_OF = Instant.parse("2026-11-20T11:00:00Z");
    private static final ZoneId IST=ZoneId.of("Asia/Kolkata");
    private static final Clock NOW=Clock.fixed(
        Instant.parse("2026-11-22T05:00:00Z"),ZoneOffset.UTC);
    private static final LocalDate[] DATES={
        LocalDate.of(2026,11,11),LocalDate.of(2026,11,12),
        LocalDate.of(2026,11,13),LocalDate.of(2026,11,16),
        LocalDate.of(2026,11,17),LocalDate.of(2026,11,18),
        LocalDate.of(2026,11,19)
    };
    private final FnoVerifiedCompletedOhlcvOiStore source =
        mock(FnoVerifiedCompletedOhlcvOiStore.class);
    private final FnoHistoricalSessionCalendar calendar=mock(FnoHistoricalSessionCalendar.class);

    private Fno176VerifiedHistoricalReplayService service(
            boolean research, boolean history, boolean discovery) {
        return new Fno176VerifiedHistoricalReplayService(source,calendar,NOW,
            research,history,discovery);
    }

    private static Instant close(LocalDate day) {
        return day.atTime(LocalTime.of(15,40)).atZone(IST).toInstant();
    }
    private static FnoVerifiedCompletedOhlcvOiStore.ReplayProofBar bar(
            LocalDate day,int idx) {
        BigDecimal price=new BigDecimal(
            new String[]{"100","101","102","103","104","105","111"}[idx]);
        var row=new FnoVerifiedCompletedOhlcvOiStore.VerifiedBar(
            "UPSTOX_ANALYTICS",KEY,"NSE_INDEX|Nifty 50",
            LocalDate.of(2026,11,1),LocalDate.of(2026,12,24),
            75,day,price.subtract(BigDecimal.ONE),
            price.add(new BigDecimal(".5")),
            price.subtract(new BigDecimal("1.5")),price,
            idx < 4 ? 30000L : 50000L,
            new long[]{100000,105000,110000,116000,122000,130000,140000}[idx],
            close(day).plusSeconds(150),close(day).plusSeconds(900),close(day));
        return new FnoVerifiedCompletedOhlcvOiStore.ReplayProofBar(
            row,HASH,HASH,"b".repeat(64));
    }
    private static List<FnoVerifiedCompletedOhlcvOiStore.ReplayProofBar> sourceBars(){
        var x=new ArrayList<FnoVerifiedCompletedOhlcvOiStore.ReplayProofBar>();
        for(int i=0;i<7;i++)x.add(bar(DATES[i],i));
        return List.copyOf(x);
    }
    private void eligible(List<FnoVerifiedCompletedOhlcvOiStore.ReplayProofBar> bars) {
        when(source.readExactReplayContract(eq(KEY),any(),any(),eq(AS_OF)))
            .thenReturn(bars);
        when(calendar.isTradingDay(any(LocalDate.class),eq(AS_OF))).thenAnswer(call->{
            LocalDate day=call.getArgument(0);
            return day.getDayOfWeek()!=DayOfWeek.SATURDAY
                && day.getDayOfWeek()!=DayOfWeek.SUNDAY;
        });
    }

    @Test void sourceRightsMissingDoesNotEvenTouchSqlOrCalendar(){
        for(int denied=0;denied<3;denied++){
            boolean[] flags={true,true,true};
            flags[denied]=false;
            var result=service(flags[0],flags[1],flags[2]).inspect(KEY,AS_OF);
            assertEquals("STR176_SOURCE_RIGHTS_OR_LISTING_UNVERIFIED",result.reasonCode());
            assertNull(result.replay());
            assertFalse(result.independentlyVerifiedHistoricalE2E());
        }
        verifyNoInteractions(source,calendar);
    }

    @Test void verifiedSourceRowsExecuteActualMomentumEvaluatorWithoutTrading(){
        eligible(sourceBars());
        var result=service(true,true,true).inspect(KEY,AS_OF);
        assertEquals("SOURCE_JOINED_EVALUATOR_RAN",result.state());
        assertEquals(7,result.originalRows());
        assertEquals(7,result.sessionWindow());
        var replay=result.replay();
        assertNotNull(replay);
        assertEquals("EVALUATOR_REPLAY_RESEARCH_ONLY_NOT_E2E",replay.state());
        assertEquals("RESEARCH_CANDIDATE",replay.decisions().getFirst().result());
        assertEquals("strategy.STR-176",replay.strategyId());
        assertEquals(1,replay.researchCandidates());
        assertEquals(64,replay.decisions().getFirst().inputReceiptHash().length());
        assertFalse(replay.independentlyVerifiedMarketE2E());
        assertFalse(result.independentlyVerifiedHistoricalE2E());
        assertFalse(result.paperTradingReady());
        assertFalse(result.liveTradingReady());
        assertFalse(result.brokerOrdersPlaced());
        verify(source).readExactReplayContract(eq(KEY),any(),any(),eq(AS_OF));
    }

    @Test void originalFirstKnownAfterAsOfIsRejected(){
        var rows=new ArrayList<>(sourceBars());
        var last=rows.getLast();
        var b=last.bar();
        var bad=new FnoVerifiedCompletedOhlcvOiStore.VerifiedBar(
            b.provider(),b.instrumentKey(),b.underlyingKey(),
            b.listedFrom(),b.expiry(),b.lotSize(),b.sessionDate(),
            b.open(),b.high(),b.low(),b.close(),
            b.tradedVolumeUnits(),b.openInterestUnits(),
            AS_OF.plusSeconds(1),b.oiFirstAvailableAt(),
            b.verifiedSessionCloseAt());
        rows.set(6,new FnoVerifiedCompletedOhlcvOiStore.ReplayProofBar(
            bad,HASH,HASH,"b".repeat(64)));
        eligible(rows);
        var result=service(true,true,true).inspect(KEY,AS_OF);
        assertEquals("STR176_ORIGINAL_CONTRACT_OR_CUSTODY_INCONSISTENT",result.reasonCode());
        assertNull(result.replay());
    }

    @Test void mixedProviderDuplicateOrBadOriginalDigestNeverReplays(){
        var original=sourceBars();
        var duplicate=new ArrayList<>(original);
        duplicate.set(5,original.get(4));
        eligible(duplicate);
        assertEquals("STR176_ORIGINAL_CONTRACT_OR_CUSTODY_INCONSISTENT",
            service(true,true,true).inspect(KEY,AS_OF).reasonCode());
        var untrusted=new ArrayList<>(original);
        var first=original.getFirst();
        untrusted.set(0,new FnoVerifiedCompletedOhlcvOiStore.ReplayProofBar(
            first.bar(),"not_sha",HASH,"b".repeat(64)));
        eligible(untrusted);
        assertEquals("STR176_ORIGINAL_CONTRACT_OR_CUSTODY_INCONSISTENT",
            service(true,true,true).inspect(KEY,AS_OF).reasonCode());
    }

    @Test void missingVerifiedTradingDayCannotBeCalledSevenConsecutiveSessions(){
        var orig=sourceBars();
        var missed=new ArrayList<FnoVerifiedCompletedOhlcvOiStore.ReplayProofBar>();
        missed.add(bar(LocalDate.of(2026,11,10),0));
        missed.addAll(orig.subList(0,4));
        missed.addAll(orig.subList(5,7)); // skip 17 Nov, a verified trading day
        eligible(missed);
        assertEquals("STR176_VERIFIED_CALENDAR_SESSION_GAP",
            service(true,true,true).inspect(KEY,AS_OF).reasonCode());
    }

    @Test void missingMostRecentCompletedSessionCannotReplayStaleBreakout(){
        var rows=new ArrayList<FnoVerifiedCompletedOhlcvOiStore.ReplayProofBar>();
        rows.add(bar(LocalDate.of(2026,11,10),0));
        for (int i=0;i<6;i++) rows.add(bar(DATES[i],i+1));
        eligible(rows);
        var missing=LocalDate.of(2026,11,19);
        var blocked=service(true,true,true).inspect(KEY,AS_OF);
        assertEquals("STR176_VERIFIED_CALENDAR_SESSION_GAP",blocked.reasonCode());
        assertNull(blocked.replay());
        assertFalse(blocked.brokerOrdersPlaced());
        when(calendar.isTradingDay(missing,AS_OF)).thenReturn(false);
        var holiday=service(true,true,true).inspect(KEY,AS_OF);
        assertEquals("SOURCE_JOINED_EVALUATOR_RAN",holiday.state());
        assertFalse(holiday.independentlyVerifiedHistoricalE2E());
        when(calendar.isTradingDay(missing,AS_OF)).thenThrow(
            new IllegalStateException("calendar unavailable"));
        var unavailable=service(true,true,true).inspect(KEY,AS_OF);
        assertEquals("STR176_VERIFIED_CALENDAR_UNAVAILABLE",unavailable.reasonCode());
        assertNull(unavailable.replay());
    }

    @Test void calendarOutageInSourceOrMissingSessionCheckIsFailClosed(){
        eligible(sourceBars());
        when(calendar.isTradingDay(DATES[0],AS_OF))
            .thenThrow(new IllegalStateException("private calendar source unavailable"));
        var sourceOutage=service(true,true,true).inspect(KEY,AS_OF);
        assertEquals("STR176_VERIFIED_CALENDAR_UNAVAILABLE",sourceOutage.reasonCode());
        assertEquals("BLOCKED_NOT_HISTORICAL_E2E",sourceOutage.state());
        assertNull(sourceOutage.replay());
        assertFalse(sourceOutage.independentlyVerifiedHistoricalE2E());
        assertFalse(sourceOutage.paperTradingReady());
        assertFalse(sourceOutage.liveTradingReady());
        assertFalse(sourceOutage.brokerOrdersPlaced());

        reset(calendar);
        eligible(sourceBars());
        // The first actual gap is Saturday 14 Nov, between Friday and Monday.
        // A calendar outage here must not be interpreted as "non-trading".
        when(calendar.isTradingDay(LocalDate.of(2026,11,14),AS_OF))
            .thenThrow(new IllegalStateException("calendar unavailable on gap"));
        var gapOutage=service(true,true,true).inspect(KEY,AS_OF);
        assertEquals("STR176_VERIFIED_CALENDAR_UNAVAILABLE",gapOutage.reasonCode());
        assertEquals("BLOCKED_NOT_HISTORICAL_E2E",gapOutage.state());
        assertNull(gapOutage.replay());
        assertFalse(gapOutage.brokerOrdersPlaced());
    }

    @Test void rejectedScopeAndUnavailableSqlCannotLeakBrokerErrors(){
        assertEquals("STR176_DECISION_SCOPE_INVALID",
            service(true,true,true).inspect("NSE_EQ|NOT_FNO",AS_OF).reasonCode());
        assertEquals("STR176_DECISION_SCOPE_INVALID",
            service(true,true,true).inspect(KEY,NOW.instant().plusSeconds(1)).reasonCode());
        verifyNoInteractions(source);
        when(source.readExactReplayContract(eq(KEY),any(),any(),eq(AS_OF)))
            .thenThrow(new IllegalStateException("private database password in upstream body"));
        var result=service(true,true,true).inspect(KEY,AS_OF);
        assertEquals("STR176_ORIGINAL_SOURCE_READ_UNAVAILABLE",result.reasonCode());
        assertFalse(result.toString().contains("password"));
    }
}
