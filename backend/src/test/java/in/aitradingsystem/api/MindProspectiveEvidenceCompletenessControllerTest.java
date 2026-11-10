package in.aitradingsystem.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import in.aitradingsystem.mind.MindProspectiveEvidenceCompletenessService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;

class MindProspectiveEvidenceCompletenessControllerTest {
    @Test
    void authenticatedCustomerOnlyReadRouteHasNoStoreAndNoExecutionAuthority()
            throws Exception {
        var method = MindProspectiveEvidenceCompletenessController.class
            .getMethod("inspect");
        assertEquals("hasRole('CUSTOMER')",
            method.getAnnotation(PreAuthorize.class).value());
        assertEquals("/api/customer/mind/v1/prospective-evidence-completeness",
            method.getAnnotation(GetMapping.class).value()[0]);

        var service = mock(MindProspectiveEvidenceCompletenessService.class);
        var snapshot = new MindProspectiveEvidenceCompletenessService.Snapshot(
            "mind.prospective-evidence-completeness.v1",
            Instant.parse("2026-11-27T10:40:00Z"),
            "SIGNAL_RECEIPT_WINDOWS_INCOMPLETE",
            10, 10, 20, 20, 11000, 10999, 1,
            30, 30, 0, List.of("PROSPECTIVE_NO_SIGNAL_OR_CANDIDATE_WINDOWS_MISSING"),
            "ABSTAIN", false, false, false);
        when(service.inspect()).thenReturn(snapshot);
        var response = new MindProspectiveEvidenceCompletenessController(service)
            .inspect();

        assertEquals(200, response.getStatusCode().value());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertEquals(1, response.getBody().missingSignalWindows());
        assertEquals("ABSTAIN", response.getBody().decisionPosture());
        assertFalse(response.getBody().outcomeValidated());
        assertFalse(response.getBody().paperOrderSubmitted());
        assertFalse(response.getBody().executionEnabled());
        verify(service).inspect();
        verifyNoMoreInteractions(service);
    }
}
