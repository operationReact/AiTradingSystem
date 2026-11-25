package in.aitradingsystem.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import in.aitradingsystem.mind.MindProspectiveCausalityAuditService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.server.ResponseStatusException;

class MindProspectiveCausalityAuditControllerTest {
    @Test
    void customerOnlyNoStoreGetNeverMutatesSourceOrCreatesOrders()
            throws Exception {
        var method = MindProspectiveCausalityAuditController.class
            .getMethod("inspect", int.class);
        assertEquals("hasRole('CUSTOMER')",
            method.getAnnotation(PreAuthorize.class).value());
        assertEquals("/api/customer/mind/v1/prospective-entry-causality",
            method.getAnnotation(GetMapping.class).value()[0]);

        var service = mock(MindProspectiveCausalityAuditService.class);
        var report = new MindProspectiveCausalityAuditService.Audit(
            "mind.prospective-entry-causality.v1",
            Instant.parse("2026-11-12T10:20:00Z"),
            0, "ENTRY_NOT_CAUSALLY_OBSERVABLE",
            10, 0, 10, 0, 0,
            List.of("ENTRY_OPEN_NOT_AFTER_SIGNAL_RECORDING"), "ABSTAIN",
            false, false, false, false);
        when(service.inspect(0)).thenReturn(report);

        var response = new MindProspectiveCausalityAuditController(service)
            .inspect(0);
        assertEquals(200, response.getStatusCode().value());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertEquals(10, response.getBody().entryNotAfterReceipt());
        assertFalse(response.getBody().entryCausallyValidated());
        assertFalse(response.getBody().paperOrderSubmitted());
        assertFalse(response.getBody().executionEnabled());
        verify(service).inspect(0);
        verifyNoMoreInteractions(service);
    }

    @Test
    void invalidPageCannotLeakInternalReasons() {
        var service = mock(MindProspectiveCausalityAuditService.class);
        when(service.inspect(1)).thenThrow(
            new IllegalArgumentException("internal candidate information"));
        var controller = new MindProspectiveCausalityAuditController(service);
        var error = assertThrows(ResponseStatusException.class,
            () -> controller.inspect(1));
        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        assertFalse(error.getReason().contains("internal"));
    }
}
