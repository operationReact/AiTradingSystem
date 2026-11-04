package in.aitradingsystem.intelligence;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import in.aitradingsystem.governance.MatchedCausalFeatureProjectionService;
import in.aitradingsystem.governance.MatchedCausalFeatureProjectionService.ProjectionView;
import in.aitradingsystem.intelligence.ResearchInferenceAttemptLedger.FailureCode;
import in.aitradingsystem.intelligence.ResearchInferenceAttemptLedger.Receipt;
import in.aitradingsystem.intelligence.ResearchInferenceAttemptLedger.Reservation;
import in.aitradingsystem.intelligence.ResearchInferenceAttemptLedger.Status;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class ResearchInferenceDispatchServiceTest {
    private static final UUID SESSION = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID MODEL = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID RESERVATION = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final String CASE = "CASE-001";
    private static final String SOURCE = "a".repeat(64);
    private static final String FEATURE = "b".repeat(64);
    private static final String ARTIFACT = "c".repeat(64);
    private static final Instant AS_OF = Instant.parse("2026-11-01T10:00:00Z");

    private final MatchedCausalFeatureProjectionService projection =
        mock(MatchedCausalFeatureProjectionService.class);
    private final ResearchInferenceAttemptLedger ledger =
        mock(ResearchInferenceAttemptLedger.class);
    private final ResearchArtifactInferenceClient client =
        mock(ResearchArtifactInferenceClient.class);

    private ProjectionView frozen() {
        return new ProjectionView(
            "v4-java-causal-feature-projection.v1", SESSION, CASE, "RELIANCE",
            SOURCE, "d".repeat(64), 99L, UUID.randomUUID(),
            "features.market.v1", AS_OF, AS_OF.minusSeconds(1),
            Map.of("market.return_1", 0.1), FEATURE, AS_OF.plusSeconds(1),
            "operator", true, false, false, false);
    }

    private Reservation reservation() {
        return new Reservation(RESERVATION, SESSION, CASE, MODEL, "e".repeat(64),
            "f".repeat(64), ARTIFACT, SOURCE, FEATURE, AS_OF,
            Instant.parse("2026-11-04T10:00:00Z"),
            Instant.parse("2026-11-04T10:01:00Z"));
    }

    private void givenFrozenReservation() {
        when(projection.byCase(SESSION, CASE)).thenReturn(frozen());
        when(ledger.reserve(SESSION, CASE, MODEL)).thenReturn(reservation());
    }

    @Test
    void disabledDefaultNeverReadsOrReservesOrCallsExternalWorker() {
        var dispatch = new ResearchInferenceDispatchService(
            projection, ledger, client, false);
        assertThrows(IllegalStateException.class,
            () -> dispatch.dispatch(SESSION, CASE, MODEL));
        verifyNoInteractions(projection, ledger, client);
    }

    @Test
    void reservesBeforeSingleWorkerCallAndNeverSubmitsOrderOrV56Case() {
        givenFrozenReservation();
        var score = new ResearchArtifactInferenceClient.Result(
            MODEL, "CATBOOST", ARTIFACT, CASE, "RELIANCE", SOURCE,
            FEATURE, AS_OF.plusSeconds(1), 0.75, "WAIT", "1".repeat(64), false);
        when(client.score(eq(MODEL), any(ProjectionView.class))).thenReturn(score);
        var receipt = new Receipt(RESERVATION, Status.SUCCEEDED,
            "VALIDATED_RESEARCH_RESPONSE", FEATURE, "1".repeat(64),
            "CATBOOST", 0.75, "WAIT", AS_OF.plusSeconds(1),
            AS_OF.plusSeconds(2), false);
        when(ledger.succeed(RESERVATION, score)).thenReturn(receipt);
        var dispatch = new ResearchInferenceDispatchService(
            projection, ledger, client, true);
        var actual = dispatch.dispatch(SESSION, CASE, MODEL);
        assertEquals(Status.SUCCEEDED, actual.status());
        assertEquals("1".repeat(64), actual.responseFingerprint());
        assertFalse(actual.canAuthorizeCapital());
        assertFalse(actual.paperOrderSubmitted());
        assertFalse(actual.matchedDecisionAppended());
        InOrder order = inOrder(projection, ledger, client);
        order.verify(projection).byCase(SESSION, CASE);
        order.verify(ledger).reserve(SESSION, CASE, MODEL);
        order.verify(client).score(eq(MODEL), any(ProjectionView.class));
        order.verify(ledger).succeed(RESERVATION, score);
        verify(ledger, never()).fail(any(), any(), any(), any());
    }

    @Test
    void timeoutOrUncertainTransportCreatesUnknownReceiptWithoutRetry() {
        givenFrozenReservation();
        when(client.score(eq(MODEL), any(ProjectionView.class)))
            .thenThrow(new IntelligenceUnavailableException(
                "Research inference outcome UNKNOWN"));
        when(ledger.fail(RESERVATION, Status.UNKNOWN,
            FailureCode.TRANSPORT_UNKNOWN, null))
            .thenReturn(new Receipt(RESERVATION, Status.UNKNOWN,
                "TRANSPORT_UNKNOWN", null, null, null, null,
                null, null, AS_OF.plusSeconds(2), false));
        var dispatch = new ResearchInferenceDispatchService(
            projection, ledger, client, true);
        var actual = dispatch.dispatch(SESSION, CASE, MODEL);
        assertEquals(Status.UNKNOWN, actual.status());
        assertNull(actual.responseFingerprint());
        verify(client, times(1)).score(eq(MODEL), any(ProjectionView.class));
        verify(ledger, times(1)).fail(RESERVATION, Status.UNKNOWN,
            FailureCode.TRANSPORT_UNKNOWN, null);
        verify(ledger, never()).succeed(any(), any());
    }

    @Test
    void featureIdentityMismatchCannotContactWorker() {
        givenFrozenReservation();
        var changed = new ProjectionView(
            frozen().contractVersion(), SESSION, CASE, "RELIANCE",
            SOURCE, "d".repeat(64), 99L, UUID.randomUUID(),
            "features.market.v1", AS_OF, AS_OF.minusSeconds(1),
            Map.of("market.return_1", 0.2), "9".repeat(64),
            AS_OF.plusSeconds(1), "operator", true, false, false, false);
        when(projection.byCase(SESSION, CASE)).thenReturn(changed);
        when(ledger.fail(RESERVATION, Status.REJECTED,
            FailureCode.RESPONSE_REJECTED, null))
            .thenReturn(new Receipt(RESERVATION, Status.REJECTED,
                "RESPONSE_REJECTED", null, null, null, null,
                null, null, AS_OF.plusSeconds(2), false));
        var actual = new ResearchInferenceDispatchService(
            projection, ledger, client, true).dispatch(SESSION, CASE, MODEL);
        assertEquals(Status.REJECTED, actual.status());
        verifyNoInteractions(client);
    }

    @Test
    void pauseDuringRemoteResponseRejectsLateSuccess() {
        givenFrozenReservation();
        var score = new ResearchArtifactInferenceClient.Result(
            MODEL, "CATBOOST", ARTIFACT, CASE, "RELIANCE", SOURCE,
            FEATURE, AS_OF.plusSeconds(1), 0.7, "WAIT", "1".repeat(64), false);
        when(client.score(eq(MODEL), any(ProjectionView.class))).thenReturn(score);
        when(ledger.succeed(RESERVATION, score))
            .thenThrow(new IllegalStateException("Session PAUSED"));
        when(ledger.fail(RESERVATION, Status.REJECTED,
            FailureCode.SESSION_REVOKED, null))
            .thenReturn(new Receipt(RESERVATION, Status.REJECTED,
                "SESSION_REVOKED", null, null, null, null,
                null, null, AS_OF.plusSeconds(2), false));
        var actual = new ResearchInferenceDispatchService(
            projection, ledger, client, true).dispatch(SESSION, CASE, MODEL);
        assertEquals(Status.REJECTED, actual.status());
        assertFalse(actual.canAuthorizeCapital());
        verify(client, times(1)).score(eq(MODEL), any(ProjectionView.class));
    }
}
