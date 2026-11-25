package in.aitradingsystem.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import in.aitradingsystem.mind.MindProspectiveOutcomeService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.server.ResponseStatusException;

class MindProspectiveOutcomeControllerTest {
    @Test
    void customerRouteIsReadOnlyNoStoreAndNeverReconciles() throws Exception {
        var method = MindProspectiveOutcomeController.class.getMethod("inspect");
        assertEquals("hasRole('CUSTOMER')",
            method.getAnnotation(PreAuthorize.class).value());
        var service = mock(MindProspectiveOutcomeService.class);
        var status = new MindProspectiveOutcomeService.Status(
            "mind.prospective-outcomes.v1",
            Instant.parse("2026-11-11T10:16:00Z"),
            "PARTIAL_RESEARCH_OUTCOMES", 7, 4, 1,
            List.of("PROSPECTIVE_SAMPLE_NOT_VALIDATED"), "ABSTAIN",
            false, false, false);
        when(service.inspect()).thenReturn(status);
        var result = new MindProspectiveOutcomeController(service).inspect();
        assertEquals(200, result.getStatusCode().value());
        assertEquals("no-store", result.getHeaders().getCacheControl());
        assertFalse(result.getBody().outcomeValidated());
        assertFalse(result.getBody().paperOrderSubmitted());
        assertFalse(result.getBody().executionEnabled());
        verify(service).inspect();
        verify(service, never()).reconcile(anyInt());
    }

    @Test
    void mutationRequiresAdminAndCannotLeakInternalError() throws Exception {
        var method = MindProspectiveOutcomeController.class
            .getMethod("reconcile", int.class);
        assertEquals("hasRole('ADMIN')",
            method.getAnnotation(PreAuthorize.class).value());
        var service = mock(MindProspectiveOutcomeService.class);
        var resultData = new MindProspectiveOutcomeService.Reconciliation(
            Instant.parse("2026-11-11T10:16:00Z"), 0, 3, 1, 1, 0, 1,
            "ABSTAIN", false);
        when(service.reconcile(0)).thenReturn(resultData);
        var controller = new MindProspectiveOutcomeController(service);
        var response = controller.reconcile(0);
        assertEquals(resultData, response.getBody());
        assertFalse(response.getBody().executionEnabled());
        assertEquals("no-store", response.getHeaders().getCacheControl());

        when(service.reconcile(0)).thenThrow(
            new IllegalStateException("private source query details"));
        var failure = assertThrows(ResponseStatusException.class,
            () -> controller.reconcile(0));
        assertEquals(HttpStatus.CONFLICT, failure.getStatusCode());
        assertFalse(failure.getReason().contains("private"));
    }
}
