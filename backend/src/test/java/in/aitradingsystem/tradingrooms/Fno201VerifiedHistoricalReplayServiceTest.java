package in.aitradingsystem.tradingrooms;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** Synthetic mocked source response; never independently verified market E2E. */
class Fno201VerifiedHistoricalReplayServiceTest {
    private static final Instant AS_OF=Instant.parse("2026-11-20T05:00:00Z");
    private static final Clock NOW=Clock.fixed(
        Instant.parse("2026-11-22T05:00:00Z"),ZoneOffset.UTC);
    private final FnoIvRankResearchService original=mock(FnoIvRankResearchService.class);
    private Fno201VerifiedHistoricalReplayService replay(){
        return new Fno201VerifiedHistoricalReplayService(original,NOW);
    }
    private static FnoFuturesResearchService.StrategyStatus status(
        String sourceStatus,int rows,int quotes,Instant time){
        return new FnoFuturesResearchService.StrategyStatus(
            "strategy.STR-201",sourceStatus,
            "IV_RANK_HIGH_IV_PULLBACK_RESEARCH_ONLY",time,
            "UPSTOX_ANALYTICS",0,quotes,rows,
            "ORIGINAL_30_DAY_ATM_IV_200_SOURCE_ALIGNED_CANDLES_NO_ORDER");
    }
    @Test void originalLicensed252SessionsCanOnlyBecomeResearchReplay(){
        when(original.evaluate(AS_OF)).thenReturn(
            status("RESEARCH_CANDIDATE",252,1,AS_OF));
        var result=replay().inspect(AS_OF);
        assertEquals("ORIGINAL_30_DAY_ATM_IV_EVALUATOR_RESEARCH_ONLY",result.state());
        assertEquals(252,result.verifiedOriginalSessions());
        assertEquals(1,result.originalCurrentIvSnapshots());
        assertFalse(result.independentlyAcceptedHistoricalE2E());
        assertFalse(result.paperLedgerE2E());
        assertFalse(result.liveBrokerE2E());
        assertFalse(result.brokerOrdersPlaced());
        verify(original).evaluate(AS_OF);
    }
    @Test void legitimateNoSignalIsNotPromotedToProfitability(){
        when(original.evaluate(AS_OF)).thenReturn(status("NO_SIGNAL",205,1,AS_OF));
        var result=replay().inspect(AS_OF);
        assertEquals("ORIGINAL_30_DAY_ATM_IV_EVALUATOR_RESEARCH_ONLY",result.state());
        assertFalse(result.independentlyAcceptedHistoricalE2E());
    }
    @Test void fakePartialHistoryOrNoOriginalQuoteBlocks() {
        when(original.evaluate(AS_OF)).thenReturn(status("RESEARCH_CANDIDATE",199,1,AS_OF));
        assertEquals("IV_RANK_ORIGINAL_200_SESSION_EVIDENCE_MISSING",
            replay().inspect(AS_OF).reasonCode());
        when(original.evaluate(AS_OF)).thenReturn(status("NO_SIGNAL",252,0,AS_OF));
        assertEquals("IV_RANK_ORIGINAL_200_SESSION_EVIDENCE_MISSING",
            replay().inspect(AS_OF).reasonCode());
    }
    @Test void deniedRightsStillPreventHistoricalE2e(){
        when(original.evaluate(AS_OF)).thenReturn(
            new FnoFuturesResearchService.StrategyStatus(
                "strategy.STR-201","BLOCKED_RIGHTS",
                "IV_RANK_ORIGINAL_HISTORY_OR_IV_RIGHTS_UNVERIFIED",
                AS_OF,"NONE",0,0,0,"IV_RANK_SOURCE_BLOCKED_NO_ORDER"));
        var result=replay().inspect(AS_OF);
        assertEquals("BLOCKED_NOT_HISTORICAL_E2E",result.state());
        assertEquals("IV_RANK_ORIGINAL_HISTORY_OR_IV_RIGHTS_UNVERIFIED",
            result.reasonCode());
        assertEquals(0,result.verifiedOriginalSessions());
        assertFalse(result.independentlyAcceptedHistoricalE2E());
    }
    @Test void scopeRejectedBeforeReadingSource(){
        assertEquals("IV_RANK_HISTORICAL_ASOF_SCOPE_INVALID",
            replay().inspect(NOW.instant().plusSeconds(1)).reasonCode());
        assertEquals("IV_RANK_HISTORICAL_ASOF_SCOPE_INVALID",
            replay().inspect(NOW.instant().minusSeconds(367L*86400)).reasonCode());
        assertEquals("IV_RANK_HISTORICAL_ASOF_SCOPE_INVALID",
            replay().inspect(null).reasonCode());
        verifyNoInteractions(original);
    }
    @Test void malformedProviderResultOrExceptionCannotLeakPrivateSource(){
        when(original.evaluate(AS_OF)).thenReturn(status("RESEARCH_CANDIDATE",252,1,
            AS_OF.plusSeconds(1)));
        assertEquals("IV_RANK_SOURCE_REPLAY_RESULT_INVALID",
            replay().inspect(AS_OF).reasonCode());
        when(original.evaluate(AS_OF)).thenThrow(
            new IllegalStateException("private token bearer 123"));
        var result=replay().inspect(AS_OF);
        assertEquals("IV_RANK_RIGHTS_JOIN_SOURCE_UNAVAILABLE",result.reasonCode());
        assertFalse(result.toString().contains("bearer"));
    }
}
