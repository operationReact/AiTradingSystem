package in.aitradingsystem.tradingrooms;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Synthetic mock tests of historical source routing only. Original production
 * SQL rights, holdings and actual evaluators are exercised separately by
 * FnoOwnedEquityOptionsResearchServiceTest; no real market E2E is claimed.
 */
class Fno187188VerifiedHistoricalReplayServiceTest {
    private static final Instant AS_OF=Instant.parse("2026-11-20T05:00:00Z");
    private static final Clock CLOCK=Clock.fixed(
        Instant.parse("2026-11-22T05:00:00Z"),ZoneOffset.UTC);
    private static final String PUT="strategy.STR-187";
    private static final String CALL="strategy.STR-188";
    private final FnoOwnedEquityOptionsResearchService original=
        mock(FnoOwnedEquityOptionsResearchService.class);
    private Fno187188VerifiedHistoricalReplayService service() {
        return new Fno187188VerifiedHistoricalReplayService(original,CLOCK);
    }
    private static FnoFuturesResearchService.StrategyStatus row(
            String id,String state,String reason,String source,
            int bars,int contracts,int quotes) {
        return new FnoFuturesResearchService.StrategyStatus(
            id,state,reason,AS_OF,"UPSTOX_ANALYTICS",contracts,quotes,bars,source);
    }
    private static List<FnoFuturesResearchService.StrategyStatus> genuineSourceMock(){
        return List.of(
            row(PUT,"RESEARCH_CANDIDATE","PROTECTIVE_PUT_RESEARCH_ONLY",
                "SETTLED_EQUITY_PROTECTIVE_PUT_RESEARCH_NO_ORDER",35,1,2),
            row(CALL,"RESEARCH_CANDIDATE","COVERED_CALL_RESEARCH_ONLY",
                "SETTLED_EQUITY_COVERED_CALL_RESEARCH_NO_ORDER",35,1,2));
    }

    @Test void bothOriginalSourceProtectedAndCoveredStrategiesStayResearchOnly(){
        when(original.evaluateAll(AS_OF)).thenReturn(genuineSourceMock());
        for(String id:List.of(PUT,CALL)){
            var report=service().inspect(id,AS_OF);
            assertEquals(id,report.strategyId());
            assertEquals("ORIGINAL_OWNED_STOCK_EVALUATOR_RESEARCH_ONLY",report.state());
            assertEquals(35,report.verifiedOriginalEquitySessions());
            assertEquals(1,report.settledBrokerOwnedContracts());
            assertFalse(report.historicalMarketE2E());
            assertFalse(report.paperLedgerE2E());
            assertFalse(report.liveBrokerE2E());
            assertFalse(report.brokerOrdersPlaced());
            assertFalse(report.toString().contains("UPSTOX_ANALYTICS"));
            assertFalse(report.toString().contains("NSE_EQ"));
        }
        verify(original,times(2)).evaluateAll(AS_OF);
        verifyNoMoreInteractions(original);
    }

    @Test void unsupportedOrFutureDecisionMustNotReachPrivateBroker(){
        assertEquals("OWNED_OPTION_HISTORICAL_STRATEGY_UNSUPPORTED",
            service().inspect("strategy.STR-189",AS_OF).reasonCode());
        assertEquals("OWNED_OPTION_HISTORICAL_SCOPE_INVALID",
            service().inspect(PUT,null).reasonCode());
        assertEquals("OWNED_OPTION_HISTORICAL_SCOPE_INVALID",
            service().inspect(CALL,CLOCK.instant().plusSeconds(1)).reasonCode());
        assertEquals("OWNED_OPTION_HISTORICAL_SCOPE_INVALID",
            service().inspect(PUT,Instant.parse("2024-11-20T05:00:00Z")).reasonCode());
        verifyNoInteractions(original);
    }

    @Test void noActualSettledSharesNeverBecomesNakedCoveredCall(){
        when(original.evaluateAll(AS_OF)).thenReturn(List.of(
            row(PUT,"BLOCKED_DATA","OWNED_STOCK_BROKER_SETTLED_LOT_PROOF_MISSING",
                "OWNED_EQUITY_OPTION_SOURCE_BLOCKED",35,0,0),
            row(CALL,"BLOCKED_DATA","OWNED_STOCK_BROKER_SETTLED_LOT_PROOF_MISSING",
                "OWNED_EQUITY_OPTION_SOURCE_BLOCKED",35,0,0)));
        var out=service().inspect(CALL,AS_OF);
        assertEquals("BLOCKED_NOT_HISTORICAL_E2E",out.state());
        assertEquals("OWNED_STOCK_BROKER_SETTLED_LOT_PROOF_MISSING",out.reasonCode());
        assertEquals(0,out.settledBrokerOwnedContracts());
        assertEquals(0,out.verifiedOriginalEquitySessions());
        assertFalse(out.liveBrokerE2E());
    }

    @Test void forgedResearchStatusWithoutRealSourceStateOrEquityWindowBlocks(){
        when(original.evaluateAll(AS_OF)).thenReturn(List.of(
            row(PUT,"RESEARCH_CANDIDATE","PROTECTIVE_PUT_RESEARCH_ONLY",
                "OWNED_EQUITY_OPTION_SOURCE_BLOCKED",35,1,2),
            row(CALL,"RESEARCH_CANDIDATE","COVERED_CALL_RESEARCH_ONLY",
                "SETTLED_EQUITY_COVERED_CALL_RESEARCH_NO_ORDER",34,1,2)));
        assertEquals("BLOCKED_NOT_HISTORICAL_E2E",
            service().inspect(PUT,AS_OF).state());
        assertEquals("BLOCKED_NOT_HISTORICAL_E2E",
            service().inspect(CALL,AS_OF).state());
    }

    @Test void misdatedOrMissingOptionSourcePairNeverClaimsReadiness(){
        when(original.evaluateAll(AS_OF)).thenReturn(List.of(
            new FnoFuturesResearchService.StrategyStatus(
                PUT,"RESEARCH_CANDIDATE","PROTECTIVE_PUT_RESEARCH_ONLY",
                CLOCK.instant(),"UPSTOX_ANALYTICS",1,2,35,
                "SETTLED_EQUITY_PROTECTIVE_PUT_RESEARCH_NO_ORDER"),
            row(CALL,"NO_SIGNAL","COVERED_CALL_NO_TRIGGER",
                "SETTLED_EQUITY_COVERED_CALL_RESEARCH_NO_ORDER",35,1,2)));
        assertEquals("ORIGINAL_OWNED_OPTION_EVALUATOR_UNAVAILABLE",
            service().inspect(PUT,AS_OF).reasonCode());
        assertEquals("ORIGINAL_OWNED_STOCK_EVALUATOR_RESEARCH_ONLY",
            service().inspect(CALL,AS_OF).state());
        when(original.evaluateAll(AS_OF)).thenReturn(List.of(
            genuineSourceMock().getFirst()));
        assertEquals("ORIGINAL_OWNED_OPTION_PAIR_SOURCE_INCOMPLETE",
            service().inspect(PUT,AS_OF).reasonCode());
    }

    @Test void privateBrokerFailuresAreRedactedAndNeverPromoteTrades(){
        when(original.evaluateAll(AS_OF)).thenThrow(
            new IllegalStateException("SECRET ACCOUNT HOLDINGS and token"));
        var result=service().inspect(PUT,AS_OF);
        assertEquals("ORIGINAL_BROKER_OWNED_SOURCE_UNAVAILABLE",result.reasonCode());
        assertFalse(result.toString().contains("SECRET"));
        assertFalse(result.historicalMarketE2E());
        assertFalse(result.brokerOrdersPlaced());
    }
}
