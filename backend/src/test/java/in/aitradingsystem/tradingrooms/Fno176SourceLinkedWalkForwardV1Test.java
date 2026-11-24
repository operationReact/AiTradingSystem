package in.aitradingsystem.tradingrooms;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import in.aitradingsystem.marketdata.MarketSessionCalendar;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Fixture tests of real source-service invocation dates; never a market E2E receipt. */
class Fno176SourceLinkedWalkForwardV1Test {
    private static final String STRATEGY="strategy.STR-176";
    private static final String KEY="NSE_FO|FUT_TEST_176";
    private static final String HASH="a".repeat(64);
    private static final ZoneId IST=ZoneId.of("Asia/Kolkata");
    private static final LocalDate FIRST=LocalDate.of(2026,11,19);
    private static final LocalDate LAST=LocalDate.of(2026,11,24);
    private static final LocalDate TRAIN_END=LocalDate.of(2026,11,20);
    private static final Clock NOW=Clock.fixed(
        Instant.parse("2026-11-26T10:00:00Z"),ZoneOffset.UTC);

    private final Fno176VerifiedHistoricalReplayService source =
        mock(Fno176VerifiedHistoricalReplayService.class);
    private final MarketSessionCalendar calendar=mock(MarketSessionCalendar.class);
    private final Fno176SourceLinkedWalkForwardV1 service=
        new Fno176SourceLinkedWalkForwardV1(source,calendar,NOW);

    private static Instant scheduled(LocalDate day) {
        return day.atTime(LocalTime.of(9,0)).atZone(IST).toInstant();
    }

    private static Fno176VerifiedHistoricalReplayService.Evidence proof(
            Instant at, boolean candidate) {
        String decision=candidate?"RESEARCH_CANDIDATE":"NO_SIGNAL";
        var row=new FnoFuturesHistoricalReplayBridgeV1.DecisionRow(
            at,STRATEGY,decision,"FUT_MOM_HISTORICAL_RESEARCH",7,HASH);
        var replay=new FnoFuturesHistoricalReplayBridgeV1.ReplayReport(
            FnoFuturesHistoricalReplayBridgeV1.CONTRACT,STRATEGY,
            "EVALUATOR_REPLAY_RESEARCH_ONLY_NOT_E2E",
            "AFTER_COST_OOS_AND_INDEPENDENT_SOURCE_PROOF_MISSING",
            1,1,candidate?1:0,candidate?0:1,HASH,
            false,false,false,false,List.of(row));
        return new Fno176VerifiedHistoricalReplayService.Evidence(
            STRATEGY,"SOURCE_JOINED_EVALUATOR_RAN","FUT_MOM_SIGNAL",
            7,7,replay,false,false,false,false);
    }

    private void available() {
        when(calendar.isTradingDay(any(LocalDate.class))).thenAnswer(call->{
            LocalDate d=call.getArgument(0);
            return d.getDayOfWeek()!=DayOfWeek.SATURDAY
                && d.getDayOfWeek()!=DayOfWeek.SUNDAY;
        });
        when(source.inspect(eq(KEY),any(Instant.class))).thenAnswer(call->{
            Instant at=call.getArgument(1);
            LocalDate d=at.atZone(IST).toLocalDate();
            return proof(at,d.equals(LocalDate.of(2026,11,20))
                || d.equals(LocalDate.of(2026,11,23)));
        });
    }

    @Test void callsActualSourceAtEveryOriginalDecisionNotFinalRetrospectiveAsOf() {
        available();
        var result=service.inspect(KEY,FIRST,LAST,TRAIN_END,null);
        assertEquals("SOURCE_LINKED_WALK_FORWARD_NO_ORIGINAL_FILLS_NOT_E2E",
            result.state());
        assertEquals(4,result.historicalDecisions());
        assertEquals(2,result.trainingDecisions());
        assertEquals(2,result.outOfSampleDecisions());
        assertEquals(2,result.researchCandidateDecisions());
        assertEquals(0,result.matchedCompletedTrades());
        assertEquals(List.of(
                scheduled(LocalDate.of(2026,11,19)),
                scheduled(LocalDate.of(2026,11,20)),
                scheduled(LocalDate.of(2026,11,23)),
                scheduled(LocalDate.of(2026,11,24))),
            result.decisions().stream().map(
                Fno176SourceLinkedWalkForwardV1.DecisionEvidence::asOf).toList());
        assertEquals(List.of(false,false,true,true),
            result.decisions().stream().map(
                Fno176SourceLinkedWalkForwardV1.DecisionEvidence::outOfSample).toList());
        assertNull(result.afterCost());
        assertFalse(result.independentlyVerifiedHistoricalE2E());
        assertFalse(result.paperOrdersPlaced());
        assertFalse(result.liveOrdersPlaced());
        verify(source,times(4)).inspect(eq(KEY),any(Instant.class));
        verify(source,never()).inspect(eq(KEY),eq(NOW.instant()));
    }

    private static FnoHistoricalAfterCostWalkForwardV1.CompletedTrade trade(
            LocalDate day,long pnl) {
        Instant entry=scheduled(day);
        return new FnoHistoricalAfterCostWalkForwardV1.CompletedTrade(
            STRATEGY,KEY,entry,entry.minusSeconds(15*60),
            entry.plusSeconds(10*60),entry.plusSeconds(3*3600),
            entry.plusSeconds(2*3600),entry.plusSeconds(3*3600+600),
            HASH,HASH,pnl,300,150,300,250,0,500000);
    }

    @Test void trainingAndOosCandidateFillsLinkIntoActualAfterCostAccounting() {
        available();
        var result=service.inspect(KEY,FIRST,LAST,TRAIN_END,
            List.of(trade(LocalDate.of(2026,11,20),10000),
                    trade(LocalDate.of(2026,11,23),-5000)));
        assertEquals("SOURCE_LINKED_AFTER_COST_RESEARCH_ONLY_NOT_E2E",result.state());
        assertEquals(4,result.historicalDecisions());
        assertEquals(2,result.matchedCompletedTrades());
        assertNotNull(result.afterCost());
        assertEquals(1,result.afterCost().trainingTrades());
        assertEquals(1,result.afterCost().outOfSampleTrades());
        assertEquals(-5000,result.afterCost().outOfSampleGrossPnlPaise());
        assertEquals(1000,result.afterCost().outOfSampleCostsPaise());
        assertEquals(-6000,result.afterCost().outOfSampleNetPnlPaise());
        assertEquals(6000,result.afterCost().outOfSampleMaxDrawdownPaise());
        assertFalse(result.afterCost().independentHistoricalMarketE2E());
        assertFalse(result.independentlyVerifiedHistoricalE2E());
    }

    @Test void noSignalDatesCannotBeBackfilledWithImaginaryProfitableFills() {
        available();
        var report=service.inspect(KEY,FIRST,LAST,TRAIN_END,
            List.of(trade(LocalDate.of(2026,11,19),12000)));
        assertEquals("STR176_COMPLETED_FILLS_NOT_LINKED_TO_RESEARCH_CANDIDATES",
            report.reasonCode());
        assertTrue(report.decisions().isEmpty());
        assertNull(report.afterCost());
    }

    @Test void aSingleMissingTradingDaySourceBlocksEntireRunNotJustThatDay() {
        available();
        when(source.inspect(eq(KEY),
                eq(scheduled(LocalDate.of(2026,11,23)))))
            .thenReturn(new Fno176VerifiedHistoricalReplayService.Evidence(
                STRATEGY,"BLOCKED_NOT_HISTORICAL_E2E",
                "STR176_SEVEN_SOURCE_SESSIONS_MISSING",
                0,0,null,false,false,false,false));
        var report=service.inspect(KEY,FIRST,LAST,TRAIN_END,null);
        assertEquals("STR176_INDEPENDENT_DAILY_SOURCE_REPLAY_INCOMPLETE",
            report.reasonCode());
        assertEquals(0,report.historicalDecisions());
        assertFalse(report.independentlyVerifiedHistoricalE2E());
    }

    @Test void failedTradingCalendarNeverCountsAsHolidayOrMissingData() {
        available();
        when(calendar.isTradingDay(LocalDate.of(2026,11,21)))
            .thenThrow(new IllegalStateException("private calendar credentials"));
        var report=service.inspect(KEY,FIRST,LAST,TRAIN_END,null);
        assertEquals("STR176_ORIGINAL_TRADING_CALENDAR_UNAVAILABLE",
            report.reasonCode());
        assertFalse(report.toString().contains("credentials"));
        assertFalse(report.liveOrdersPlaced());
    }

    @Test void futureOrUnboundedOrSingleGroupWindowNeverQueriesSource() {
        assertEquals("STR176_OOS_REPLAY_SCOPE_INVALID",service.inspect(
            KEY,FIRST,LAST,FIRST.minusDays(1),null).reasonCode());
        assertEquals("STR176_OOS_REPLAY_SCOPE_INVALID",service.inspect(
            KEY,FIRST,FIRST,FIRST,null).reasonCode());
        assertEquals("STR176_OOS_REPLAY_SCOPE_INVALID",service.inspect(
            KEY,FIRST,FIRST.plusDays(28),FIRST.plusDays(7),null).reasonCode());
        verifyNoInteractions(source,calendar);
    }

    @Test void actualFutureDecisionIsRejectedBeforeSourceRead() {
        available();
        var futureFirst=LocalDate.of(2026,11,27);
        var futureLast=LocalDate.of(2026,11,28);
        var report=service.inspect(KEY,futureFirst,futureLast,futureFirst,null);
        assertEquals("STR176_FUTURE_DECISION_NOT_OBSERVED",report.reasonCode());
        assertEquals(0,report.historicalDecisions());
        verifyNoInteractions(source);
    }

    @Test void overlappingOrUnverifiedCompletedFillIsNotAcceptedAsAnOosProfit() {
        available();
        var one=trade(LocalDate.of(2026,11,20),1000);
        var duplicate=trade(LocalDate.of(2026,11,20),2000);
        var result=service.inspect(KEY,FIRST,LAST,TRAIN_END,
            List.of(one,duplicate));
        assertEquals("STR176_COMPLETED_FILLS_NOT_LINKED_TO_RESEARCH_CANDIDATES",
            result.reasonCode());
        assertFalse(result.independentlyVerifiedHistoricalE2E());
    }
}
