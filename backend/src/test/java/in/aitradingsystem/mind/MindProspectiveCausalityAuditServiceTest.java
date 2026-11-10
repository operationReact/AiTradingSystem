package in.aitradingsystem.mind;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MindProspectiveCausalityAuditServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-12T10:20:00Z");
    private static final Instant START = Instant.parse("2026-11-11T03:45:00Z");
    private static final Instant SIGNAL = START.plusSeconds(20 * 300);
    private static final Instant ENTRY = SIGNAL.plusSeconds(300);
    private static final Instant RECORDED = ENTRY.plusSeconds(60);
    private static final UUID COHORT = UUID.randomUUID();
    private static final UUID CATALOGUE = UUID.randomUUID();
    private static final String FINGERPRINT = "a".repeat(64);
    private static final String SOURCE_DIGEST = "b".repeat(64);
    private static final String OUTCOME_DIGEST = "c".repeat(64);

    private final MindProspectiveCohortRepository cohorts =
        mock(MindProspectiveCohortRepository.class);
    private final MindProspectiveOutcomeRepository outcomes =
        mock(MindProspectiveOutcomeRepository.class);

    private MindProspectiveCausalityAuditService service() {
        return new MindProspectiveCausalityAuditService(
            cohorts, outcomes, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static MindProspectiveCohortRepository.Cohort cohort() {
        var members = new ArrayList<MindProspectiveCohortRepository.Member>();
        for (int i = 1; i <= 20; i++) {
            members.add(new MindProspectiveCohortRepository.Member(
                i, 50000L + i, CATALOGUE, "NSE",
                "STOCK" + i + "-EQ", "token" + i));
        }
        return new MindProspectiveCohortRepository.Cohort(
            COHORT, MindProspectiveCohortRepository.CODE,
            MindProspectiveCohortRepository.PROTOCOL,
            "MA_RECLAIM_V1", CATALOGUE, START.minusSeconds(86400),
            START.minusSeconds(7200), START, 10, List.copyOf(members));
    }

    private static MindProspectiveOutcomeRepository.Receipt receipt() {
        return new MindProspectiveOutcomeRepository.Receipt(
            COHORT, 1, SIGNAL, RECORDED, FINGERPRINT, SOURCE_DIGEST,
            new BigDecimal("100"), new BigDecimal("99"),
            new BigDecimal("102"), 50001L, CATALOGUE,
            "NSE", "STOCK1-EQ", "token1");
    }

    private static MindProspectiveOutcomeRepository.AuditedOutcome outcome(
            Instant entry) {
        return new MindProspectiveOutcomeRepository.AuditedOutcome(
            entry, entry, NOW.minusSeconds(600),
            FINGERPRINT, SOURCE_DIGEST, OUTCOME_DIGEST,
            new BigDecimal("100"), new BigDecimal("99"),
            new BigDecimal("-1.0000"), new BigDecimal("-1.3000"),
            "STOP", 1);
    }

    private void withCandidate() {
        when(cohorts.current()).thenReturn(Optional.of(cohort()));
        when(outcomes.candidatePage(COHORT, 0, 100))
            .thenReturn(List.of(receipt()));
    }

    @Test
    void originalV76OpeningPrecedesReceiptAndIsNeverAnExecutableObservation() {
        withCandidate();
        when(outcomes.recordedOutcome(receipt()))
            .thenReturn(Optional.of(outcome(ENTRY)));
        var result = service().inspect(0);
        assertEquals("mind.prospective-entry-causality.v1", result.contractVersion());
        assertEquals("ENTRY_NOT_CAUSALLY_OBSERVABLE", result.status());
        assertEquals(1, result.candidatesInspected());
        assertEquals(1, result.entryNotAfterReceipt());
        assertEquals(0, result.laterEntryReferences());
        assertEquals(0, result.invalidEvidence());
        assertTrue(result.blockers().contains(
            "ENTRY_OPEN_NOT_AFTER_SIGNAL_RECORDING"));
        assertFalse(result.entryCausallyValidated());
        assertFalse(result.independentlySourceAttested());
        assertFalse(result.paperOrderSubmitted());
        assertFalse(result.executionEnabled());
        assertEquals("ABSTAIN", result.decisionPosture());
        verify(outcomes).recordedOutcome(receipt());
        verifyNoMoreInteractions(outcomes);
    }

    @Test
    void exactReceiptBoundaryIsStillNotAProvenAchievableEntry() {
        withCandidate();
        when(outcomes.recordedOutcome(receipt()))
            .thenReturn(Optional.of(outcome(RECORDED)));
        assertEquals(1, service().inspect(0).entryNotAfterReceipt());
    }

    @Test
    void futureReferenceHasOnlyTimingOrderNotBrokerFillProof() {
        withCandidate();
        when(outcomes.recordedOutcome(receipt()))
            .thenReturn(Optional.of(outcome(RECORDED.plusSeconds(300))));
        var result = service().inspect(0);
        assertEquals("TIMING_ORDER_ONLY_NOT_VALIDATED", result.status());
        assertEquals(1, result.laterEntryReferences());
        assertFalse(result.entryCausallyValidated());
        assertFalse(result.executionEnabled());
    }

    @Test
    void pendingCandidateRemainsUnknownNotASuccessfulTrade() {
        withCandidate();
        when(outcomes.recordedOutcome(receipt())).thenReturn(Optional.empty());
        var result = service().inspect(0);
        assertEquals("AWAITING_OUTCOMES", result.status());
        assertEquals(1, result.awaitingOutcomes());
        assertEquals(0, result.entryNotAfterReceipt());
        assertFalse(result.executionEnabled());
    }

    @Test
    void contradictoryImmutableDigestFailsClosed() {
        withCandidate();
        var invalid = new MindProspectiveOutcomeRepository.AuditedOutcome(
            ENTRY, ENTRY, NOW.minusSeconds(600), FINGERPRINT,
            "d".repeat(64), OUTCOME_DIGEST, new BigDecimal("100"),
            new BigDecimal("99"), BigDecimal.ONE, BigDecimal.ZERO,
            "STOP", 1);
        when(outcomes.recordedOutcome(receipt())).thenReturn(Optional.of(invalid));
        var result = service().inspect(0);
        assertEquals("INVALID_IMMUTABLE_TIMESTAMPS", result.status());
        assertEquals(1, result.invalidEvidence());
        assertFalse(result.entryCausallyValidated());
    }

    @Test
    void noCandidatePageDoesNotClaimAnyCausalObservations() {
        when(cohorts.current()).thenReturn(Optional.of(cohort()));
        when(outcomes.candidatePage(COHORT, 0, 100)).thenReturn(List.of());
        var result = service().inspect(0);
        assertEquals("NO_CANDIDATES_ON_PAGE", result.status());
        assertEquals(0, result.candidatesInspected());
        verify(outcomes, never()).recordedOutcome(any());
    }

    @Test
    void cohortUnavailableAndStoreFailuresReturnNoPrivateDetails() {
        when(cohorts.current()).thenReturn(Optional.empty());
        assertEquals("UNAVAILABLE", service().inspect(0).status());
        verifyNoInteractions(outcomes);
        when(cohorts.current()).thenThrow(new IllegalStateException("private storage details"));
        var result = service().inspect(0);
        assertEquals("UNAVAILABLE", result.status());
        assertTrue(result.blockers().contains("CAUSALITY_EVIDENCE_UNAVAILABLE"));
        assertFalse(result.executionEnabled());
    }

    @Test
    void paginationIsBoundedAndNeverQueriesBeforeValidation() {
        for (int invalid : new int[]{-1, 1, 15001, 15100}) {
            assertThrows(IllegalArgumentException.class,
                () -> service().inspect(invalid));
        }
        verifyNoInteractions(cohorts, outcomes);
    }
}
