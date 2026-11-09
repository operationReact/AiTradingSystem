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
}
