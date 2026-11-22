package in.aitradingsystem.tradingrooms;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Tests structural replay only, not independently verified market backtests. */
class Fno181182VerifiedHistoricalReplayServiceTest {
    private static final String CE="NSE_FO|NIFTY_20261127_125_CE";
    private static final String PE="NSE_FO|NIFTY_20261127_75_PE";
    private static final String CALL="strategy.STR-181";
    private static final String PUT="strategy.STR-182";
    private static final Instant DECISION=Instant.parse("2026-11-20T05:00:00Z");
    private static final Instant NEXT=Instant.parse("2026-11-23T05:00:00Z");
    private static final Clock CLOCK=Clock.fixed(
        Instant.parse("2026-11-23T07:00:00Z"),ZoneOffset.UTC);
    private final FnoOptionsResearchService original=mock(FnoOptionsResearchService.class);
    private Fno181182VerifiedHistoricalReplayService service(){
        return new Fno181182VerifiedHistoricalReplayService(original,CLOCK,CE,PE);
    }
    private static FnoFuturesResearchService.StrategyStatus status(
            String id,Instant at,String evaluation,String state,int closes){
        return new FnoFuturesResearchService.StrategyStatus(
            id,evaluation,"LONG_OPTION_SOURCE_REASON",
            at,"UPSTOX_ANALYTICS",1,2,closes,state);
    }
    private static String state(String id) {
        return CALL.equals(id)?"VERIFIED_NIFTY_CE_SOURCE_RESEARCH_ONLY"
            :"VERIFIED_NIFTY_PE_SOURCE_RESEARCH_ONLY";
    }

    @Test
    void originalTypedCePeEvidenceRunsIndependentlyWithoutTrading(){
        when(original.evaluateOne(CALL,DECISION)).thenReturn(
            status(CALL,DECISION,"RESEARCH_CANDIDATE",state(CALL),35));
        when(original.evaluateOne(PUT,DECISION)).thenReturn(
            status(PUT,DECISION,"NO_SIGNAL",state(PUT),35));
        for(String id:List.of(CALL,PUT)){
            var r=service().inspect(id,CALL.equals(id)?CE:PE,DECISION);
            assertEquals("SOURCE_V106_EVALUATOR_RESEARCH_ONLY",r.state());
            assertEquals(35,r.originalCompletedCloses());
            assertEquals(2,r.originalQuotes());
            assertFalse(r.independentHistoricalE2E());
            assertFalse(r.paperLedgerE2E());
            assertFalse(r.liveBrokerE2E());
            assertFalse(r.brokerOrdersPlaced());
            assertFalse(r.toString().contains(CE));
            assertFalse(r.toString().contains(PE));
        }
        verify(original).evaluateOne(CALL,DECISION);
        verify(original).evaluateOne(PUT,DECISION);
        verifyNoMoreInteractions(original);
    }

    @Test
    void arbitraryContractWrongSideAndFutureAsOfNeverCallOriginalEvaluator(){
        var svc=service();
        for(String key:List.of(PE,"NSE_EQ|STOCK","NSE_FO|BAD/../")){
            assertEquals("HISTORICAL_OPTION_SCOPE_OR_CONTRACT_INVALID",
                svc.inspect(CALL,key,DECISION).reason());
        }
        assertEquals("HISTORICAL_OPTION_SCOPE_OR_CONTRACT_INVALID",
            svc.inspect(PUT,CE,DECISION).reason());
        assertEquals("HISTORICAL_OPTION_SCOPE_OR_CONTRACT_INVALID",
            svc.inspect("strategy.STR-190",CE,DECISION).reason());
        assertEquals("HISTORICAL_OPTION_SCOPE_OR_CONTRACT_INVALID",
            svc.inspect(CALL,CE,CLOCK.instant().plusSeconds(1)).reason());
        verifyNoInteractions(original);
    }

    @Test
    void forgedCandidateMissingSourceOrWrongRowCountIsNeverAccepted(){
        when(original.evaluateOne(CALL,DECISION)).thenReturn(
            status(CALL,DECISION,"RESEARCH_CANDIDATE","IMAGINARY_SOURCE",35));
        assertEquals("ORIGINAL_SOURCE_OR_EVALUATOR_GATES_UNMET",
            service().inspect(CALL,CE,DECISION).reason());
        when(original.evaluateOne(CALL,DECISION)).thenReturn(
            status(CALL,DECISION,"RESEARCH_CANDIDATE",state(CALL),34));
        assertEquals("BLOCKED_NOT_HISTORICAL_E2E",
            service().inspect(CALL,CE,DECISION).state());
        when(original.evaluateOne(CALL,DECISION)).thenReturn(
            status(CALL,DECISION,"BLOCKED_RIGHTS",state(CALL),35));
        assertEquals("BLOCKED_NOT_HISTORICAL_E2E",
            service().inspect(CALL,CE,DECISION).state());
    }

    @Test
    void sourceExceptionsAreSanitizedAndNoBrokerDataEscapes(){
        when(original.evaluateOne(CALL,DECISION))
            .thenThrow(new IllegalStateException("secret broker access token"));
        var result=service().inspect(CALL,CE,DECISION);
        assertEquals("ORIGINAL_OPTION_SOURCE_UNAVAILABLE",result.reason());
        assertFalse(result.toString().contains("broker access token"));
        assertFalse(result.brokerOrdersPlaced());
    }

    @Test
    void boundedHistoricalChronologicalReplayIsDeterministicAndNotMarketE2e(){
        when(original.evaluateOne(CALL,DECISION)).thenReturn(
            status(CALL,DECISION,"RESEARCH_CANDIDATE",state(CALL),35));
        when(original.evaluateOne(CALL,NEXT)).thenReturn(
            status(CALL,NEXT,"NO_SIGNAL",state(CALL),40));
        var rows=List.of(DECISION,NEXT);
        var first=service().replay(CALL,CE,rows);
        var second=service().replay(CALL,CE,rows);
        assertEquals(first,second);
        assertEquals("EVALUATOR_REPLAY_RESEARCH_ONLY_NOT_E2E",first.state());
        assertEquals(2,first.decisions());
        assertEquals(1,first.researchCandidates());
        assertEquals(1,first.noSignals());
        assertEquals(0,first.blocked());
        assertEquals(64,first.deterministicDigest().length());
        assertFalse(first.independentHistoricalE2E());
        assertFalse(first.paperLedgerE2E());
        assertFalse(first.liveBrokerE2E());
        assertFalse(first.brokerOrdersPlaced());
    }

    @Test
    void duplicateTimeOutOfOrderAndUnboundedReplayFailClosed(){
        var svc=service();
        for(var dates:List.of(List.of(DECISION,DECISION),List.of(NEXT,DECISION),
                List.of(CLOCK.instant().plusSeconds(1)),List.<Instant>of())){
            assertEquals("BLOCKED_NOT_HISTORICAL_E2E",
                svc.replay(CALL,CE,dates).state());
        }
        assertEquals("REPLAY_WINDOW_SCOPE_INVALID",
            svc.replay(CALL,CE,java.util.Collections.nCopies(65,DECISION)).reason());
        verifyNoInteractions(original);
    }

    @Test
    void missingOneOriginalWindowNeverCountsAsSuccess(){
        when(original.evaluateOne(PUT,DECISION)).thenReturn(
            status(PUT,DECISION,"NO_SIGNAL",state(PUT),35));
        var report=service().replay(PUT,PE,List.of(DECISION,NEXT));
        assertEquals("INCOMPLETE_RIGHTS_ATTESTED_HISTORICAL_REPLAY",report.state());
        assertEquals(1,report.noSignals());
        assertEquals(1,report.blocked());
        assertFalse(report.independentHistoricalE2E());
    }
}
