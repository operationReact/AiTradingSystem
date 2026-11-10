package in.aitradingsystem.mind;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MindProspectiveEvidenceCompletenessServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-27T10:40:00Z");
    private static final Instant REGISTERED = Instant.parse("2026-11-10T05:00:00Z");
    private static final Instant START = Instant.parse("2026-11-11T03:45:00Z");

    private final MindProspectiveCohortService cohorts =
        mock(MindProspectiveCohortService.class);
    private final MindProspectiveSignalService signals =
        mock(MindProspectiveSignalService.class);
    private final MindProspectiveOutcomeService outcomes =
        mock(MindProspectiveOutcomeService.class);

    private MindProspectiveEvidenceCompletenessService service() {
        return new MindProspectiveEvidenceCompletenessService(
            cohorts, signals, outcomes, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static MindProspectiveCohortService.Status cohort(
            int completed, int covered) {
        var members = new ArrayList<MindProspectiveCohortService.MemberState>();
        for (int i = 1; i <= 20; i++) {
            int bars = 75 * completed;
            members.add(new MindProspectiveCohortService.MemberState(
                i, "NSE", "STOCK" + i + "-EQ",
                bars, bars, new BigDecimal("100.00"),
                completed == 10 ? "SOURCE_COVERAGE_RECORDED" : "OBSERVATION_PENDING"));
        }
        return new MindProspectiveCohortService.Status(
            "mind.prospective-cohort.v1", NOW,
            completed == 10
                ? covered == 20 ? "SOURCE_COVERAGE_READY_RESEARCH_ONLY"
                    : "INSUFFICIENT_COVERAGE"
                : "COLLECTING",
            "MA_RECLAIM_NSE_EQ_PROSPECTIVE_V1", "MA_RECLAIM_V1",
            "DETERMINISTIC_CATALOGUE_HASH_NO_SIGNAL_RANKING",
            UUID.randomUUID(), REGISTERED, START, NOW, 10, completed,
            20, covered, List.copyOf(members),
            List.of("LIVE_EXECUTION_DISABLED"), "ABSTAIN",
            false, false, false);
    }

    private static MindProspectiveSignalService.Status signals(
            int windowsPerMember, int candidates, boolean missingLast) {
        var members = new ArrayList<MindProspectiveSignalService.MemberCount>();
        int total = 0;
        for (int i = 1; i <= 20; i++) {
            int windows = windowsPerMember - (i == 20 && missingLast ? 1 : 0);
            total += windows;
            members.add(new MindProspectiveSignalService.MemberCount(
                i, "STOCK" + i + "-EQ", windows, i == 1 ? candidates : 0));
        }
        return new MindProspectiveSignalService.Status(
            "mind.prospective-signals.v1", NOW, "RESEARCH_RECEIPTS_RECORDED",
            REGISTERED, START, 20, total, candidates, List.copyOf(members),
            List.of("LIVE_EXECUTION_DISABLED"), "ABSTAIN",
            false, false, false);
    }

    private static MindProspectiveOutcomeService.Status outcomes(
            int candidates, int resolved) {
        return new MindProspectiveOutcomeService.Status(
            "mind.prospective-outcomes.v1", NOW,
            resolved < candidates ? "PARTIAL_RESEARCH_OUTCOMES"
                : "RESEARCH_OUTCOMES_RECORDED",
            candidates, resolved, 0,
            List.of("PROSPECTIVE_SAMPLE_NOT_VALIDATED"), "ABSTAIN",
            false, false, false);
    }

    private void given(MindProspectiveCohortService.Status cohort,
                       MindProspectiveSignalService.Status signal,
                       MindProspectiveOutcomeService.Status outcome) {
        when(cohorts.inspect()).thenReturn(cohort);
        when(signals.inspect()).thenReturn(signal);
        when(outcomes.inspect()).thenReturn(outcome);
    }

    @Test
    void fullTenSessionAccountingIsStillResearchOnlyAndCannotEnableOrders() {
        given(cohort(10, 20), signals(550, 30, false), outcomes(30, 30));
        var value = service().inspect();
        assertEquals("RESEARCH_ACCOUNTING_COMPLETE_NOT_VALIDATED", value.status());
        assertEquals(11000, value.expectedSignalWindowsAtMaturity());
        assertEquals(11000, value.recordedSignalWindows());
        assertEquals(0, value.missingSignalWindows());
        assertEquals(30, value.precommittedCandidates());
        assertEquals(0, value.pendingOutcomes());
        assertEquals("ABSTAIN", value.decisionPosture());
        assertFalse(value.outcomeValidated());
        assertFalse(value.paperOrderSubmitted());
        assertFalse(value.executionEnabled());
        verify(cohorts).inspect();
        verify(signals).inspect();
        verify(outcomes).inspect();
        verifyNoMoreInteractions(cohorts, signals, outcomes);
    }

    @Test
    void observationSessionStillRunningNeverClaimsMissingFinalWindows() {
        given(cohort(4, 0), signals(220, 2, false), outcomes(2, 1));
        var value = service().inspect();
        assertEquals("OBSERVATION_SESSIONS_IN_PROGRESS", value.status());
        assertNull(value.expectedSignalWindowsAtMaturity());
        assertNull(value.missingSignalWindows());
        assertFalse(value.executionEnabled());
    }

    @Test
    void oneMissingNoSignalReceiptBlocksCompletionEvenWhenOutcomesResolve() {
        given(cohort(10, 20), signals(550, 30, true), outcomes(30, 30));
        var value = service().inspect();
        assertEquals("SIGNAL_RECEIPT_WINDOWS_INCOMPLETE", value.status());
        assertEquals(10999, value.recordedSignalWindows());
        assertEquals(1, value.missingSignalWindows());
        assertTrue(value.blockers().contains(
            "PROSPECTIVE_NO_SIGNAL_OR_CANDIDATE_WINDOWS_MISSING"));
    }

    @Test
    void sourceCoverageRemainsAMandatoryBlocker() {
        given(cohort(10, 19), signals(550, 30, false), outcomes(30, 30));
        var value = service().inspect();
        assertEquals("SOURCE_COVERAGE_INCOMPLETE", value.status());
        assertFalse(value.outcomeValidated());
    }

    @Test
    void emptyCandidateSetCannotBePresentedAsValidated() {
        given(cohort(10, 20), signals(550, 0, false), outcomes(0, 0));
        var value = service().inspect();
        assertEquals("NO_PROSPECTIVE_CANDIDATES", value.status());
        assertEquals(0, value.reconciledOutcomes());
        assertFalse(value.outcomeValidated());
    }

    @Test
    void allReceiptsDoNotConcealUnreconciledCandidateOutcomes() {
        given(cohort(10, 20), signals(550, 30, false), outcomes(30, 29));
        var value = service().inspect();
        assertEquals("OUTCOME_RECONCILIATION_INCOMPLETE", value.status());
        assertEquals(1, value.pendingOutcomes());
        assertFalse(value.executionEnabled());
    }

    @Test
    void inconsistentLedgerOrUnregisteredCohortFailsClosed() {
        given(cohort(10, 20), signals(550, 30, false), outcomes(31, 30));
        assertEquals("UNAVAILABLE", service().inspect().status());
        when(cohorts.inspect()).thenReturn(null);
        assertEquals("UNAVAILABLE", service().inspect().status());
    }

    @Test
    void duplicateMemberSlotsDoNotMasqueradeAsCompletePopulation() {
        var original = signals(550, 30, false);
        var duplicate = new ArrayList<>(original.members());
        duplicate.set(1, new MindProspectiveSignalService.MemberCount(
            1, "STOCK1-EQ", 550, 0));
        given(cohort(10, 20), new MindProspectiveSignalService.Status(
            original.contractVersion(), NOW, original.status(),
            original.registeredAt(), original.observationStartsAt(),
            original.memberCount(), original.recordedWindows(),
            original.researchCandidates(), List.copyOf(duplicate),
            original.blockers(), "ABSTAIN", false, false, false),
            outcomes(30, 30));
        assertEquals("UNAVAILABLE", service().inspect().status());
    }

    @Test
    void sourceReadExceptionProducesOnlyGenericUnverifiedState() {
        when(cohorts.inspect()).thenThrow(new IllegalStateException("database details"));
        var value = service().inspect();
        assertEquals("UNAVAILABLE", value.status());
        assertTrue(value.blockers().contains("RESEARCH_EVIDENCE_UNAVAILABLE"));
        assertFalse(value.executionEnabled());
    }
}
