package in.aitradingsystem.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.aitradingsystem.mind.MindReadinessService;
import in.aitradingsystem.mind.MindEvidenceLedgerService;
import in.aitradingsystem.mind.MindStrategyReplayService;
import in.aitradingsystem.mind.MindMaWalkForwardService;
import in.aitradingsystem.mind.MindProspectiveCohortService;
import java.util.List;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class CustomerMindControllerTest {
    @Test
    void authenticatedCustomerReadIsNonCacheableAndCannotAuthorizeTrading() {
        var service = mock(MindReadinessService.class);
        var evidence = MindReadinessService.Evidence.unavailable("NO_VERIFIED_MARKET_SNAPSHOT");
        var expected = new MindReadinessService.Overview(
            "mind.overview.v1", Instant.parse("2026-11-09T09:00:00Z"),
            evidence, evidence, "ABSTAIN", false, false);
        when(service.inspect()).thenReturn(expected);

        var response = new CustomerMindController(service, mock(MindEvidenceLedgerService.class), mock(MindStrategyReplayService.class), mock(MindMaWalkForwardService.class), mock(MindProspectiveCohortService.class)).overview();
        assertEquals(200, response.getStatusCode().value());
        assertEquals(expected, response.getBody());
        assertFalse(response.getBody().executionEnabled());
        assertEquals("no-store", response.getHeaders().getCacheControl());
    }
    @Test
    void evidenceReadIsBatchedNonCacheableAndHasNoExecutionAuthority() {
        var readiness = mock(MindReadinessService.class);
        var evidence = mock(MindEvidenceLedgerService.class);
        var now = Instant.parse("2026-11-09T09:00:00Z");
        var expected = new MindEvidenceLedgerService.Ledger(
            "mind.evidence.v1", now, "UNAVAILABLE",
            null, null, null, List.of(),
            List.of("NO_COMPLETED_RESEARCH_SCAN"), 0,
            "ABSTAIN", false, false);
        when(evidence.inspect(10)).thenReturn(expected);

        var response = new CustomerMindController(readiness, evidence, mock(MindStrategyReplayService.class), mock(MindMaWalkForwardService.class), mock(MindProspectiveCohortService.class)).evidence(10);
        assertEquals(200, response.getStatusCode().value());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertEquals(expected, response.getBody());
        assertFalse(response.getBody().executionEnabled());
        org.mockito.Mockito.verify(evidence).inspect(10);
    }

    @Test
    void deterministicStrategyReplayIsNoStoreAndAlwaysNonExecutable() {
        var service = mock(MindStrategyReplayService.class);
        var expected = new MindStrategyReplayService.Replay(
            "mind.strategy-replay.v1", Instant.parse("2026-11-09T09:00:00Z"),
            null, "MA_RECLAIM_V1", "1", null, null, null, null, null,
            "UNAVAILABLE", "ABSTAIN", List.of("NO_COMPLETED_RESEARCH_SCAN"),
            false, "NOT_ENROLLED", false, false);
        when(service.inspect()).thenReturn(expected);
        var response = new CustomerMindController(
            mock(MindReadinessService.class),
            mock(MindEvidenceLedgerService.class), service,
            mock(MindMaWalkForwardService.class), mock(MindProspectiveCohortService.class)).strategyReplay();
        assertEquals(200, response.getStatusCode().value());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertEquals(expected, response.getBody());
        assertFalse(response.getBody().paperOrderSubmitted());
        assertFalse(response.getBody().executionEnabled());
        org.mockito.Mockito.verify(service).inspect();
    }

    @Test
    void fixedCostWalkForwardIsNoStoreAndAlwaysResearchOnly() {
        var walkForward = mock(MindMaWalkForwardService.class);
        var expected = new MindMaWalkForwardService.Report(
            "mind.ma-walkforward.v1", Instant.parse("2026-11-09T09:00:00Z"),
            "UNAVAILABLE", "MA_RECLAIM_V1", "1", null, "FIVE_MINUTE",
            "RECENT_SCANNER_CANDIDATE_SELECTION_BIASED", null, null, null,
            List.of(), 0, 0, null, null, null, null, null, null,
            List.of("HISTORICAL_SOURCE_UNAVAILABLE"), "ABSTAIN",
            false, false, false, false);
        when(walkForward.inspect()).thenReturn(expected);
        var response = new CustomerMindController(
            mock(MindReadinessService.class), mock(MindEvidenceLedgerService.class),
            mock(MindStrategyReplayService.class), walkForward,
            mock(MindProspectiveCohortService.class)).maWalkForward();
        assertEquals(200, response.getStatusCode().value());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertEquals(expected, response.getBody());
        assertFalse(response.getBody().validatedTradingEdge());
        assertFalse(response.getBody().paperOrderSubmitted());
        assertFalse(response.getBody().executionEnabled());
    }

    @Test
    void prospectiveReadCannotClaimValidatedSignalOrTradePermission() {
        var prospective = mock(MindProspectiveCohortService.class);
        var expected = new MindProspectiveCohortService.Status(
            "mind.prospective-cohort.v1", Instant.parse("2026-11-09T16:00:00Z"),
            "UNREGISTERED", null, "MA_RECLAIM_V1",
            "DETERMINISTIC_CATALOGUE_HASH_NO_SIGNAL_RANKING", null,
            null, null, null, 10, 0, 0, 0, List.of(),
            List.of("COHORT_NOT_REGISTERED"), "ABSTAIN",
            false, false, false);
        when(prospective.inspect()).thenReturn(expected);
        var response = new CustomerMindController(
            mock(MindReadinessService.class), mock(MindEvidenceLedgerService.class),
            mock(MindStrategyReplayService.class), mock(MindMaWalkForwardService.class),
            prospective).prospectiveCohort();
        assertEquals(200, response.getStatusCode().value());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertEquals(expected, response.getBody());
        assertFalse(response.getBody().outcomeValidated());
        assertFalse(response.getBody().paperOrderSubmitted());
        assertFalse(response.getBody().executionEnabled());
        org.mockito.Mockito.verify(prospective).inspect();
    }

}
