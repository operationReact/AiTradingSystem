package in.aitradingsystem.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import in.aitradingsystem.mind.MindProspectiveCustodyAuditService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.server.ResponseStatusException;

class MindProspectiveCustodyAuditControllerTest {
    @Test
    void internalAuditIsAdminOnlyNoStoreAndNeverGrantsTrading() throws Exception {
        var method = MindProspectiveCustodyAuditController.class
            .getMethod("inspect", int.class);
        assertEquals("hasRole('ADMIN')",
            method.getAnnotation(PreAuthorize.class).value());

        var audit = mock(MindProspectiveCustodyAuditService.class);
        var expected = new MindProspectiveCustodyAuditService.Audit(
            "mind.prospective-custody-audit.v1",
            Instant.parse("2026-11-11T10:20:00Z"),
            0, 2, 1, 1, 0, 0, 0,
            "INCOMPLETE_RESEARCH_EVIDENCE",
            List.of("INTERNAL_DIGEST_NOT_INDEPENDENT_SOURCE_ATTESTATION"),
            "ABSTAIN", false, false, false, false);
        when(audit.audit(0)).thenReturn(expected);
        var controller = new MindProspectiveCustodyAuditController(audit);
        var response = controller.inspect(0);
        assertEquals(200, response.getStatusCode().value());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertEquals(expected, response.getBody());
        assertFalse(response.getBody().independentlyVerified());
        assertFalse(response.getBody().outcomeValidated());
        assertFalse(response.getBody().paperOrderSubmitted());
        assertFalse(response.getBody().executionEnabled());
        verify(audit).audit(0);
    }

    @Test
    void invalidAuditPageRedactsPrivateBackendFailure() {
        var audit = mock(MindProspectiveCustodyAuditService.class);
        when(audit.audit(0)).thenThrow(
            new IllegalStateException("private market-provider details"));
        var controller = new MindProspectiveCustodyAuditController(audit);
        var failure = assertThrows(ResponseStatusException.class,
            () -> controller.inspect(0));
        assertEquals(HttpStatus.CONFLICT, failure.getStatusCode());
        assertFalse(failure.getReason().contains("private"));
    }
}
