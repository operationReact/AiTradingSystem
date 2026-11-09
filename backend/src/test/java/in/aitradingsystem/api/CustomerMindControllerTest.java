package in.aitradingsystem.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.aitradingsystem.mind.MindReadinessService;
import in.aitradingsystem.mind.MindEvidenceLedgerService;
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

        var response = new CustomerMindController(service, mock(MindEvidenceLedgerService.class)).overview();
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

        var response = new CustomerMindController(readiness, evidence).evidence(10);
        assertEquals(200, response.getStatusCode().value());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertEquals(expected, response.getBody());
        assertFalse(response.getBody().executionEnabled());
        org.mockito.Mockito.verify(evidence).inspect(10);
    }

}
