package in.aitradingsystem.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.aitradingsystem.mind.MindReadinessService;
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

        var response = new CustomerMindController(service).overview();
        assertEquals(200, response.getStatusCode().value());
        assertEquals(expected, response.getBody());
        assertFalse(response.getBody().executionEnabled());
        assertEquals("no-store", response.getHeaders().getCacheControl());
    }
}
