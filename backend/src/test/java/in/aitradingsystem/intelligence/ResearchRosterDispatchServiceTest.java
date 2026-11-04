package in.aitradingsystem.intelligence;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import in.aitradingsystem.intelligence.ResearchInferenceAttemptLedger.Status;
import in.aitradingsystem.intelligence.ResearchReceiptDiagnosticsService.ModelStatus;
import in.aitradingsystem.intelligence.ResearchReceiptDiagnosticsService.Report;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class ResearchRosterDispatchServiceTest {
    private static final UUID SESSION = UUID.fromString(
        "22222222-2222-4222-8222-222222222222");
    private static final UUID CHAMPION = UUID.fromString(
        "11111111-1111-4111-8111-111111111111");
    private static final UUID CHALLENGER = UUID.fromString(
        "33333333-3333-4333-8333-333333333333");
    private static final String CASE = "CAUSAL-001";
    private static final String HASH = "a".repeat(64);
    private static final Instant NOW = Instant.parse("2026-11-04T10:00:00Z");

    private final ResearchReceiptDiagnosticsService diagnostics =
        mock(ResearchReceiptDiagnosticsService.class);
    private final ResearchInferenceDispatchService single =
        mock(ResearchInferenceDispatchService.class);
    private final ResearchMatchedDecisionFinalizeService finalizer =
        mock(ResearchMatchedDecisionFinalizeService.class);

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    private ModelStatus member(UUID id, String role, String status) {
        return new ModelStatus(id, role,
            id.equals(CHAMPION) ? "CATBOOST" : "LIGHTGBM",
            HASH, HASH, status,
            "NOT_ATTEMPTED".equals(status)
                ? "NO_RESEARCH_RESERVATION" : "VALIDATED_RESEARCH_RESPONSE",
            "NOT_ATTEMPTED".equals(status) ? null : UUID.randomUUID(),
            "NOT_ATTEMPTED".equals(status) ? null : NOW,
            "NOT_ATTEMPTED".equals(status) ? null : NOW.plusSeconds(60),
            "NOT_ATTEMPTED".equals(status) ? null : NOW.plusSeconds(2),
            "NOT_ATTEMPTED".equals(status) ? null : HASH,
            "SUCCEEDED".equals(status));
    }

    private Report report(String state, long revision, String first, String second) {
        List<ModelStatus> models = List.of(
            member(CHAMPION, "PAPER_CHAMPION", first),
            member(CHALLENGER, "SHADOW_CHALLENGER", second));
        int succeeded = (int) models.stream().filter(m ->
            "SUCCEEDED".equals(m.status())).count();
        return new Report(ResearchReceiptDiagnosticsService.CONTRACT,
            SESSION, CASE, HASH, state, revision,
            true, true, HASH, HASH, false, null, null, 2,
            succeeded, 2 - succeeded, models,
            false, false, false, false, false, false);
    }

    private void seed(String first, String second) {
        when(diagnostics.inspect(SESSION, CASE)).thenReturn(
            report("ACTIVE", 0, "NOT_ATTEMPTED", "NOT_ATTEMPTED"),
            report("ACTIVE", 0, first, second));
    }

    private static void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(
            new TestingAuthenticationToken("research-admin", null, "ROLE_ADMIN"));
    }

    private ResearchRosterDispatchService roster(boolean enabled) {
        return new ResearchRosterDispatchService(diagnostics, single, finalizer, enabled);
    }

    @Test
    void defaultDisabledDoesNotPreflightOrStartAnyWorker() {
        assertThrows(IllegalStateException.class,
            () -> roster(false).dispatchRoster(SESSION, CASE));
        verifyNoInteractions(diagnostics, single, finalizer);
    }

    @Test
    void oneAuthenticatedCallRunsEachMemberOnceThenFinalizesOnlyFullRoster() {
        authenticate();
        seed("SUCCEEDED", "SUCCEEDED");
        var calls = new AtomicInteger();
        when(single.dispatch(eq(SESSION), eq(CASE), any(UUID.class)))
            .thenAnswer(inv -> {
                assertEquals("research-admin",
                    SecurityContextHolder.getContext().getAuthentication().getName());
                UUID id = inv.getArgument(2, UUID.class);
                calls.incrementAndGet();
                return new ResearchInferenceDispatchService.DispatchView(
                    ResearchInferenceDispatchService.CONTRACT, UUID.randomUUID(),
                    SESSION, CASE, id, Status.SUCCEEDED,
                    "VALIDATED_RESEARCH_RESPONSE", HASH, false, false, false);
            });
        when(finalizer.finalizeCase(SESSION, CASE)).thenReturn(
            new ResearchMatchedDecisionFinalizeService.FinalizedView(
                ResearchMatchedDecisionFinalizeService.CONTRACT,
                SESSION, CASE, HASH, HASH, 2, false, false, false, false));

        var result = roster(true).dispatchRoster(SESSION, CASE);
        assertEquals("FINALIZED_RESEARCH_ONLY", result.status());
        assertTrue(result.matchedEvidenceFinalized());
        assertEquals(2, result.succeeded());
        assertEquals(0, result.unresolved());
        assertEquals(HASH, result.caseFingerprint());
        assertFalse(result.canAuthorizeCapital());
        assertFalse(result.paperOrdersSubmitted());
        assertEquals(2, calls.get());
        verify(single, times(1)).dispatch(SESSION, CASE, CHAMPION);
        verify(single, times(1)).dispatch(SESSION, CASE, CHALLENGER);
        verify(finalizer, times(1)).finalizeCase(SESSION, CASE);
    }

    @Test
    void partialUnknownNeverCallsFinalizerAndNeverRetriesAnyModel() {
        authenticate();
        seed("SUCCEEDED", "UNKNOWN");
        when(single.dispatch(eq(SESSION), eq(CASE), any(UUID.class)))
            .thenAnswer(inv -> {
                UUID id = inv.getArgument(2, UUID.class);
                return new ResearchInferenceDispatchService.DispatchView(
                    ResearchInferenceDispatchService.CONTRACT, UUID.randomUUID(),
                    SESSION, CASE, id,
                    id.equals(CHAMPION) ? Status.SUCCEEDED : Status.UNKNOWN,
                    id.equals(CHAMPION)
                        ? "VALIDATED_RESEARCH_RESPONSE" : "TRANSPORT_UNKNOWN",
                    null, false, false, false);
            });
        var result = roster(true).dispatchRoster(SESSION, CASE);
        assertEquals("INCOMPLETE_NO_RETRY", result.status());
        assertEquals(1, result.succeeded());
        assertEquals(1, result.unresolved());
        assertFalse(result.matchedEvidenceFinalized());
        verify(finalizer, never()).finalizeCase(any(), any());
        verify(single, times(2)).dispatch(eq(SESSION), eq(CASE), any(UUID.class));
    }

    @Test
    void preexistingAttemptIsNotDispatchedAgain() {
        authenticate();
        when(diagnostics.inspect(SESSION, CASE)).thenReturn(
            report("ACTIVE", 0, "SUCCEEDED", "NOT_ATTEMPTED"));
        assertThrows(IllegalStateException.class,
            () -> roster(true).dispatchRoster(SESSION, CASE));
        verifyNoInteractions(single, finalizer);
    }

    @Test
    void sourceMustBeFrozenAndSessionMustRemainActive() {
        authenticate();
        when(diagnostics.inspect(SESSION, CASE))
            .thenReturn(report("PAUSED", 1,
                "NOT_ATTEMPTED", "NOT_ATTEMPTED"));
        assertThrows(IllegalStateException.class,
            () -> roster(true).dispatchRoster(SESSION, CASE));
        verifyNoInteractions(single, finalizer);
    }

    @Test
    void concurrentPauseInvalidatesCompleteResultsBeforeV62() {
        authenticate();
        when(diagnostics.inspect(SESSION, CASE)).thenReturn(
            report("ACTIVE", 0, "NOT_ATTEMPTED", "NOT_ATTEMPTED"),
            report("PAUSED", 1, "SUCCEEDED", "SUCCEEDED"));
        when(single.dispatch(eq(SESSION), eq(CASE), any(UUID.class)))
            .thenAnswer(inv -> new ResearchInferenceDispatchService.DispatchView(
                ResearchInferenceDispatchService.CONTRACT, UUID.randomUUID(),
                SESSION, CASE, inv.getArgument(2, UUID.class), Status.SUCCEEDED,
                "VALIDATED_RESEARCH_RESPONSE", HASH, false, false, false));
        var result = roster(true).dispatchRoster(SESSION, CASE);
        assertEquals("INCOMPLETE_NO_RETRY", result.status());
        assertFalse(result.matchedEvidenceFinalized());
        verify(finalizer, never()).finalizeCase(any(), any());
    }

    @Test
    void workerExceptionCannotBeTreatedAsSuccessfulRoster() {
        authenticate();
        seed("UNKNOWN_UNRESOLVED", "SUCCEEDED");
        when(single.dispatch(eq(SESSION), eq(CASE), any(UUID.class)))
            .thenThrow(new IllegalStateException("lost transport"));
        var result = roster(true).dispatchRoster(SESSION, CASE);
        assertEquals("INCOMPLETE_NO_RETRY", result.status());
        assertEquals(1, result.unresolved());
        verify(finalizer, never()).finalizeCase(any(), any());
    }
}
