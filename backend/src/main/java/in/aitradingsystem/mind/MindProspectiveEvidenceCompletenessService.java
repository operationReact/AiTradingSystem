package in.aitradingsystem.mind;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * MIND-04D1: read-only cross-check of the fixed 20-member, 10-session
 * prospective protocol, capture receipts, and outcome reconciliation.
 *
 * The first 20 bars per session are warm-up input for MA_RECLAIM_V1: only
 * the remaining windows can have on-time NO_SIGNAL/CANDIDATE receipts.
 * A count match is an internal accounting check, NOT proof of source
 * authenticity, independence, strategy profitability or permission to trade.
 */
@Service
public class MindProspectiveEvidenceCompletenessService {
    private static final int MEMBERS = MindProspectiveCohortRepository.SIZE;
    private static final int SESSIONS = 10;
    private static final int WARMUP_BARS_PER_SESSION = 20;
    private static final String COHORT_CONTRACT = "mind.prospective-cohort.v1";
    private static final String SIGNAL_CONTRACT = "mind.prospective-signals.v1";
    private static final String OUTCOME_CONTRACT = "mind.prospective-outcomes.v1";

    private final MindProspectiveCohortService cohorts;
    private final MindProspectiveSignalService signals;
    private final MindProspectiveOutcomeService outcomes;
    private final Clock clock;

    public record Snapshot(String contractVersion, Instant generatedAt,
                           String status, int targetSessions, int completedSessions,
                           int memberCount, int membersWithSufficientSourceCoverage,
                           Integer expectedSignalWindowsAtMaturity,
                           int recordedSignalWindows, Integer missingSignalWindows,
                           long precommittedCandidates, long reconciledOutcomes,
                           long pendingOutcomes, List<String> blockers,
                           String decisionPosture, boolean outcomeValidated,
                           boolean paperOrderSubmitted, boolean executionEnabled) {}

    public MindProspectiveEvidenceCompletenessService(
            MindProspectiveCohortService cohorts,
            MindProspectiveSignalService signals,
            MindProspectiveOutcomeService outcomes,
            Clock clock) {
        this.cohorts = cohorts;
        this.signals = signals;
        this.outcomes = outcomes;
        this.clock = clock;
    }

    /**
     * Reads existing persisted evidence only. NEVER creates a cohort, captures
     * signals, reconciles outcomes, backfills candles or places an order.
     */
    public Snapshot inspect() {
        Instant now = clock.instant();
        try {
            var cohort = cohorts.inspect();
            var signal = signals.inspect();
            var outcome = outcomes.inspect();
            if (!consistent(cohort, signal, outcome, now))
                return unavailable(now, "INCONSISTENT_RESEARCH_LEDGER");

            boolean mature = cohort.completedSessions() == SESSIONS;
            int expected = 0;
            boolean perMemberReceiptMatch = true;
            var signalsBySlot = new HashMap<Integer, MindProspectiveSignalService.MemberCount>();
            for (var member : signal.members()) {
                if (signalsBySlot.putIfAbsent(member.slot(), member) != null)
                    return unavailable(now, "DUPLICATE_SIGNAL_MEMBER");
            }
            for (var member : cohort.members()) {
                var observed = signalsBySlot.get(member.slot());
                if (observed == null || !member.symbol().equals(observed.symbol()))
                    return unavailable(now, "COHORT_SIGNAL_MEMBER_MISMATCH");
                if (mature) {
                    int eligible = member.expectedCandles() -
                        SESSIONS * WARMUP_BARS_PER_SESSION;
                    if (eligible < 0)
                        return unavailable(now, "INVALID_ELIGIBLE_SIGNAL_WINDOWS");
                    expected += eligible;
                    if (observed.recordedWindows() != eligible)
                        perMemberReceiptMatch = false;
                }
            }

            int totalRecorded = signal.recordedWindows();
            Integer expectedAtMaturity = mature ? expected : null;
            Integer missing = mature ? Math.max(0, expected - totalRecorded) : null;
            long pendingOutcomes = outcome.precommittedCandidates() -
                outcome.reconciledOutcomes();

            var blockers = new ArrayList<String>();
            blockers.add("INTERNAL_COUNTS_NOT_INDEPENDENT_EXCHANGE_ATTESTATION");
            blockers.add("SIGNAL_SELECTION_AND_SAMPLE_INDEPENDENCE_NOT_VALIDATED");
            blockers.add("REALISTIC_INDIAN_FEES_TAXES_AND_SLIPPAGE_NOT_VERIFIED");
            blockers.add("ACCOUNT_RISK_AND_OUT_OF_SAMPLE_GENERALIZATION_NOT_EVALUATED");
            blockers.add("PAPER_SHADOW_ENROLLMENT_REQUIRED");
            blockers.add("LIVE_EXECUTION_DISABLED");

            String status;
            if (!mature) {
                status = "OBSERVATION_SESSIONS_IN_PROGRESS";
                blockers.add("TEN_PROSPECTIVE_SESSIONS_NOT_COMPLETE");
            } else if (cohort.membersWithSufficientCoverage() != MEMBERS ||
                       !"SOURCE_COVERAGE_READY_RESEARCH_ONLY".equals(cohort.sourceStatus())) {
                status = "SOURCE_COVERAGE_INCOMPLETE";
                blockers.add("ALL_MEMBERS_SOURCE_COVERAGE_NOT_VERIFIED");
            } else if (!perMemberReceiptMatch || expected != totalRecorded) {
                status = "SIGNAL_RECEIPT_WINDOWS_INCOMPLETE";
                blockers.add("PROSPECTIVE_NO_SIGNAL_OR_CANDIDATE_WINDOWS_MISSING");
            } else if (outcome.precommittedCandidates() == 0) {
                status = "NO_PROSPECTIVE_CANDIDATES";
                blockers.add("NO_CANDIDATES_TO_VALIDATE");
            } else if (pendingOutcomes > 0) {
                status = "OUTCOME_RECONCILIATION_INCOMPLETE";
                blockers.add("PRECOMMITTED_CANDIDATES_WITHOUT_OUTCOMES");
            } else {
                status = "RESEARCH_ACCOUNTING_COMPLETE_NOT_VALIDATED";
                blockers.add("COMPLETE_LEDGER_DOES_NOT_ESTABLISH_PROFITABLE_EDGE");
            }
            return new Snapshot("mind.prospective-evidence-completeness.v1",
                now, status, SESSIONS, cohort.completedSessions(), MEMBERS,
                cohort.membersWithSufficientCoverage(), expectedAtMaturity,
                totalRecorded, missing, outcome.precommittedCandidates(),
                outcome.reconciledOutcomes(), pendingOutcomes,
                List.copyOf(blockers), "ABSTAIN", false, false, false);
        } catch (RuntimeException unavailable) {
            return unavailable(now, "RESEARCH_EVIDENCE_UNAVAILABLE");
        }
    }

    private static boolean consistent(
            MindProspectiveCohortService.Status cohort,
            MindProspectiveSignalService.Status signal,
            MindProspectiveOutcomeService.Status outcome,
            Instant now) {
        if (cohort == null || signal == null || outcome == null ||
            !COHORT_CONTRACT.equals(cohort.contractVersion()) ||
            !SIGNAL_CONTRACT.equals(signal.contractVersion()) ||
            !OUTCOME_CONTRACT.equals(outcome.contractVersion()) ||
            cohort.registeredAt() == null || cohort.observationStartsAt() == null ||
            cohort.registeredAt().isAfter(now) ||
            !cohort.registeredAt().isBefore(cohort.observationStartsAt()) ||
            !cohort.registeredAt().equals(signal.registeredAt()) ||
            !cohort.observationStartsAt().equals(signal.observationStartsAt()) ||
            cohort.targetSessions() != SESSIONS ||
            cohort.completedSessions() < 0 || cohort.completedSessions() > SESSIONS ||
            cohort.memberCount() != MEMBERS || signal.memberCount() != MEMBERS ||
            cohort.membersWithSufficientCoverage() < 0 ||
            cohort.membersWithSufficientCoverage() > MEMBERS ||
            cohort.members() == null || cohort.members().size() != MEMBERS ||
            signal.members() == null || signal.members().size() != MEMBERS ||
            signal.recordedWindows() < 0 || signal.researchCandidates() < 0 ||
            signal.researchCandidates() > signal.recordedWindows() ||
            outcome.precommittedCandidates() < 0 ||
            outcome.reconciledOutcomes() < 0 ||
            outcome.reconciledOutcomes() > outcome.precommittedCandidates() ||
            outcome.positiveNetObservations() < 0 ||
            outcome.positiveNetObservations() > outcome.reconciledOutcomes() ||
            outcome.precommittedCandidates() != signal.researchCandidates() ||
            !"ABSTAIN".equals(cohort.decisionPosture()) ||
            !"ABSTAIN".equals(signal.decisionPosture()) ||
            !"ABSTAIN".equals(outcome.decisionPosture()) ||
            cohort.executionEnabled() || signal.executionEnabled() ||
            outcome.executionEnabled() || cohort.outcomeValidated() ||
            signal.outcomeValidated() || outcome.outcomeValidated() ||
            cohort.paperOrderSubmitted() || signal.paperOrderSubmitted() ||
            outcome.paperOrderSubmitted()) return false;

        Map<Integer, MindProspectiveCohortService.MemberState> members =
            new HashMap<>();
        for (var member : cohort.members()) {
            if (member == null || member.slot() < 1 || member.slot() > MEMBERS ||
                member.symbol() == null || member.expectedCandles() < 0 ||
                member.validCandles() < 0 ||
                member.validCandles() > member.expectedCandles() ||
                members.putIfAbsent(member.slot(), member) != null) return false;
        }
        int recorded = 0;
        int candidates = 0;
        for (var member : signal.members()) {
            if (member == null || member.slot() < 1 || member.slot() > MEMBERS ||
                !members.containsKey(member.slot()) ||
                member.recordedWindows() < 0 || member.researchCandidates() < 0 ||
                member.researchCandidates() > member.recordedWindows()) return false;
            recorded += member.recordedWindows();
            candidates += member.researchCandidates();
        }
        return recorded == signal.recordedWindows() &&
            candidates == signal.researchCandidates() &&
            !("UNAVAILABLE".equals(cohort.sourceStatus()) ||
              "UNREGISTERED".equals(cohort.sourceStatus()) ||
              "UNAVAILABLE".equals(signal.status()) ||
              "UNREGISTERED".equals(signal.status()) ||
              "UNAVAILABLE".equals(outcome.status()) ||
              "UNREGISTERED".equals(outcome.status()));
    }

    private static Snapshot unavailable(Instant now, String reason) {
        return new Snapshot("mind.prospective-evidence-completeness.v1",
            now, "UNAVAILABLE", SESSIONS, 0, 0, 0, null, 0, null,
            0, 0, 0, List.of(reason, "INDEPENDENT_VALIDATION_NOT_COMPLETED",
                "PAPER_SHADOW_ENROLLMENT_REQUIRED", "LIVE_EXECUTION_DISABLED"),
            "ABSTAIN", false, false, false);
    }
}
