package in.aitradingsystem.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import in.aitradingsystem.mind.MindProspectiveCohortService;
import in.aitradingsystem.mind.MindProspectiveSignalService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.server.ResponseStatusException;

class MindProspectiveCohortAdminControllerTest {
    @Test
    void registrationMustHaveExplicitAdminRoleIndependentOfRequestMatcher() throws Exception {
        var action = MindProspectiveCohortAdminController.class.getMethod("register");
        var role = action.getAnnotation(PreAuthorize.class);
        assertNotNull(role);
        assertEquals("hasRole('ADMIN')", role.value());
    }

    @Test
    void ownerRegistrationIsASeparateMutationWithNoTradeAuthority() {
        var service = mock(MindProspectiveCohortService.class);
        var response = new MindProspectiveCohortService.Status(
            "mind.prospective-cohort.v1", Instant.parse("2026-11-09T16:00:00Z"),
            "AWAITING_FUTURE_SESSION",
            "MA_RECLAIM_NSE_EQ_PROSPECTIVE_V1", "MA_RECLAIM_V1",
            "DETERMINISTIC_CATALOGUE_HASH_NO_SIGNAL_RANKING", null,
            null, null, null, 10, 0, 20, 0, List.of(),
            List.of("PROSPECTIVE_RETURNS_NOT_EVALUATED"), "ABSTAIN",
            false, false, false);
        when(service.register()).thenReturn(response);
        var result = new MindProspectiveCohortAdminController(
            service, mock(MindProspectiveSignalService.class)).register();
        assertEquals(HttpStatus.CREATED, result.getStatusCode());
        assertEquals("no-store", result.getHeaders().getCacheControl());
        assertEquals(response, result.getBody());
        assertFalse(result.getBody().outcomeValidated());
        assertFalse(result.getBody().paperOrderSubmitted());
        assertFalse(result.getBody().executionEnabled());
        verify(service).register();
        verify(service, never()).inspect();
    }

    @Test
    void duplicateOrUnavailableCatalogueReturnsConflictWithoutPrivateErrorDetails() {
        var service = mock(MindProspectiveCohortService.class);
        when(service.register()).thenThrow(
            new IllegalStateException("secret catalogue diagnostic"));
        var failure = assertThrows(ResponseStatusException.class,
            () -> new MindProspectiveCohortAdminController(
            service, mock(MindProspectiveSignalService.class)).register());
        assertEquals(HttpStatus.CONFLICT, failure.getStatusCode());
        assertFalse(failure.getReason().contains("secret"));
    }
    @Test
    void prospectiveCaptureIsExplicitAdminOnlyAndReturnsNoTradeAuthority()
            throws Exception {
        var method = MindProspectiveCohortAdminController.class
            .getMethod("captureSignals");
        var preauthorize = method.getAnnotation(PreAuthorize.class);
        assertNotNull(preauthorize);
        assertEquals("hasRole('ADMIN')", preauthorize.value());

        var signals = mock(MindProspectiveSignalService.class);
        var expected = new MindProspectiveSignalService.Capture(
            Instant.parse("2026-11-10T05:31:00Z"),
            Instant.parse("2026-11-10T05:25:00Z"),
            20, 1, 0, 19, 0, "ABSTAIN", false);
        when(signals.capture()).thenReturn(expected);
        var controller = new MindProspectiveCohortAdminController(
            mock(MindProspectiveCohortService.class), signals);
        var response = controller.captureSignals();
        assertEquals(200, response.getStatusCode().value());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertEquals(expected, response.getBody());
        assertFalse(response.getBody().executionEnabled());
        verify(signals).capture();

        when(signals.capture()).thenThrow(
            new IllegalStateException("internal provider details"));
        var error = assertThrows(ResponseStatusException.class,
            controller::captureSignals);
        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        assertFalse(error.getReason().contains("provider"));
    }

}
