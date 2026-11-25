package in.aitradingsystem.mind;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * MIND-04D2: immutable READ-ONLY temporal causality audit.
 *
 * A research candidate captured minutes after its signal bar closes cannot
 * enter at that bar's next opening price: the opening may already be past.
 * Prior outcome records remain intact as illustrative counterfactuals, not
 * tradable prospective returns. No order/capital/backfill authority.
 */
@Service
public class MindProspectiveCausalityAuditService {
    private static final Duration BAR = Duration.ofMinutes(5);
    private static final int PAGE = 100;
    private final MindProspectiveCohortRepository cohorts;
    private final MindProspectiveOutcomeRepository outcomes;
    private final Clock clock;

    public record Audit(String contractVersion, Instant generatedAt, int pageOffset,
                        String status, int candidatesInspected, int awaitingOutcomes,
                        int entryNotAfterReceipt, int laterEntryReferences,
                        int invalidEvidence, List<String> blockers,
                        String decisionPosture, boolean entryCausallyValidated,
                        boolean independentlySourceAttested,
                        boolean paperOrderSubmitted, boolean executionEnabled) {}

    public MindProspectiveCausalityAuditService(
            MindProspectiveCohortRepository cohorts,
            MindProspectiveOutcomeRepository outcomes,
            Clock clock) {
        this.cohorts = cohorts;
        this.outcomes = outcomes;
        this.clock = clock;
    }

    public Audit inspect(int offset) {
        if (offset < 0 || offset > 15000 || offset % PAGE != 0)
            throw new IllegalArgumentException("INVALID_CAUSALITY_PAGE");
        Instant now = clock.instant();
        try {
            var cohort = cohorts.current();
            if (cohort.isEmpty()) return unavailable(now, offset, "COHORT_NOT_REGISTERED");
            var frozen = cohort.get();
            if (frozen.id() == null || frozen.registeredAt() == null ||
                frozen.observationStartsAt() == null ||
                frozen.observationStartsAt().isAfter(now) ||
                frozen.members() == null ||
                frozen.members().size() != MindProspectiveCohortRepository.SIZE ||
                !"MA_RECLAIM_V1".equals(frozen.strategyCode()))
                return unavailable(now, offset, "COHORT_NOT_ELIGIBLE");

            var receipts = outcomes.candidatePage(frozen.id(), offset, PAGE);
            if (receipts == null || receipts.size() > PAGE)
                return unavailable(now, offset, "CANDIDATE_PAGE_INVALID");

            int pending = 0, noncausal = 0, future = 0, invalid = 0;
            for (var receipt : receipts) {
                if (!validReceipt(frozen, receipt, now)) {
                    invalid++;
                    continue;
                }
                var recorded = outcomes.recordedOutcome(receipt);
                if (recorded.isEmpty()) {
                    pending++;
                    continue;
                }
                var outcome = recorded.get();
                if (!validOutcome(receipt, outcome, now)) {
                    invalid++;
                    continue;
                }
                // The OLD V76 research protocol anchors the outcome at
                // signal+5m. A receipt created AFTER that opening cannot
                // establish a real or counterfactually executable entry.
                // Equality is also not proof of an achievable fill.
                if (!outcome.entryCandleAt().isAfter(receipt.recordedAt())) {
                    noncausal++;
                } else {
                    // A later reference is only timing-consistent, never a
                    // validated fill, independent market attestation or edge.
                    future++;
                }
            }

            var blockers = new ArrayList<>(standardBlockers());
            String status;
            if (receipts.isEmpty()) {
                status = "NO_CANDIDATES_ON_PAGE";
            } else if (invalid > 0) {
                status = "INVALID_IMMUTABLE_TIMESTAMPS";
                blockers.add("INVALID_CANDIDATE_OR_OUTCOME_TIMESTAMPS");
            } else if (noncausal > 0) {
                status = "ENTRY_NOT_CAUSALLY_OBSERVABLE";
                blockers.add("ENTRY_OPEN_NOT_AFTER_SIGNAL_RECORDING");
            } else if (pending > 0) {
                status = "AWAITING_OUTCOMES";
            } else {
                status = "TIMING_ORDER_ONLY_NOT_VALIDATED";
            }
            return new Audit("mind.prospective-entry-causality.v1", now,
                offset, status, receipts.size(), pending, noncausal, future,
                invalid, List.copyOf(blockers), "ABSTAIN",
                false, false, false, false);
        } catch (RuntimeException failure) {
            return unavailable(now, offset, "CAUSALITY_EVIDENCE_UNAVAILABLE");
        }
    }

    private static boolean validReceipt(
            MindProspectiveCohortRepository.Cohort cohort,
            MindProspectiveOutcomeRepository.Receipt receipt,
            Instant now) {
        if (receipt == null || !cohort.id().equals(receipt.cohortId()) ||
            receipt.memberSlot() < 1 ||
            receipt.memberSlot() > MindProspectiveCohortRepository.SIZE ||
            receipt.signalCandleAt() == null || receipt.recordedAt() == null ||
            receipt.recordedAt().isAfter(now) ||
            receipt.recordedAt().isBefore(receipt.signalCandleAt().plus(BAR)) ||
            !receipt.recordedAt().isBefore(
                receipt.signalCandleAt().plus(Duration.ofMinutes(15))) ||
            receipt.candidateFingerprint() == null ||
            !receipt.candidateFingerprint().matches("[a-f0-9]{64}") ||
            receipt.originalSourceDigest() == null ||
            !receipt.originalSourceDigest().matches("[a-f0-9]{64}")) return false;
        return cohort.members().stream().anyMatch(member ->
            member.slot() == receipt.memberSlot() &&
            member.instrumentId() == receipt.instrumentId() &&
            member.catalogueVersion().equals(receipt.catalogueVersion()) &&
            member.exchange().equals(receipt.exchange()) &&
            member.tradingSymbol().equals(receipt.tradingSymbol()) &&
            member.symbolToken().equals(receipt.symbolToken()));
    }

    private static boolean validOutcome(
            MindProspectiveOutcomeRepository.Receipt receipt,
            MindProspectiveOutcomeRepository.AuditedOutcome saved,
            Instant now) {
        return saved != null &&
            receipt.candidateFingerprint().equals(saved.candidateFingerprint()) &&
            receipt.originalSourceDigest().equals(saved.signalSourceDigest()) &&
            saved.outcomeSourceDigest() != null &&
            saved.outcomeSourceDigest().matches("[a-f0-9]{64}") &&
            saved.entryCandleAt() != null && saved.exitCandleAt() != null &&
            saved.evaluatedAt() != null && !saved.evaluatedAt().isAfter(now) &&
            !saved.entryCandleAt().isBefore(receipt.signalCandleAt().plus(BAR)) &&
            !saved.exitCandleAt().isBefore(saved.entryCandleAt()) &&
            !saved.evaluatedAt().isBefore(saved.exitCandleAt().plus(BAR)) &&
            saved.sourceBars() > 0 && saved.sourceBars() <= 75;
    }

    private static List<String> standardBlockers() {
        return List.of(
            "ILLUSTRATIVE_OUTCOME_NOT_AN_OBSERVED_FILL",
            "TIMING_CHECK_NOT_INDEPENDENT_SOURCE_ATTESTATION",
            "ACCOUNT_RISK_AND_BROKER_FEES_NOT_VERIFIED",
            "PAPER_SHADOW_ENROLLMENT_REQUIRED",
            "LIVE_EXECUTION_DISABLED");
    }

    private static Audit unavailable(Instant now, int offset, String reason) {
        var blockers = new ArrayList<>(standardBlockers());
        blockers.add(reason);
        return new Audit("mind.prospective-entry-causality.v1", now,
            offset, "UNAVAILABLE", 0, 0, 0, 0, 0, List.copyOf(blockers),
            "ABSTAIN", false, false, false, false);
    }
}
