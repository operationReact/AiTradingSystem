package in.aitradingsystem.mind;

import in.aitradingsystem.marketdata.HistoricalCandleInterval;
import in.aitradingsystem.marketdata.MarketDataRepository;
import in.aitradingsystem.marketdata.MarketSessionCalendar;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * MIND-04C2C: bounded READ-ONLY audit of immutable research receipts and
 * outcomes against the CURRENT internal candle store. Reproducing a digest
 * is only an internal consistency check: NOT independent exchange evidence,
 * a prospective performance certification, or execution permission.
 */
@Service
public class MindProspectiveCustodyAuditService {
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Duration BAR = Duration.ofMinutes(5);
    private static final int PAGE = 100;
    private final MindProspectiveCohortRepository cohorts;
    private final MindProspectiveOutcomeRepository outcomes;
    private final MarketDataRepository market;
    private final MarketSessionCalendar calendar;
    private final MindProspectiveOutcomeEvaluator evaluator;
    private final Clock clock;

    public record Audit(String contractVersion, Instant auditedAt, int pageOffset,
                        int candidatesInspected, int awaitingOutcome,
                        int internallyReproduced, int originalInputUnavailableOrRevised,
                        int outcomeInputUnavailableOrRevised, int invalidRecord,
                        String status, List<String> blockers,
                        String decisionPosture, boolean independentlyVerified,
                        boolean outcomeValidated, boolean paperOrderSubmitted,
                        boolean executionEnabled) {}

    public MindProspectiveCustodyAuditService(
            MindProspectiveCohortRepository cohorts,
            MindProspectiveOutcomeRepository outcomes,
            MarketDataRepository market,
            MarketSessionCalendar calendar,
            MindProspectiveOutcomeEvaluator evaluator,
            Clock clock) {
        this.cohorts = cohorts;
        this.outcomes = outcomes;
        this.market = market;
        this.calendar = calendar;
        this.evaluator = evaluator;
        this.clock = clock;
    }

    public Audit audit(int offset) {
        if (offset < 0 || offset > 15000 || offset % PAGE != 0)
            throw new IllegalArgumentException("INVALID_AUDIT_PAGE");
        Instant now = clock.instant();
        var cohort = cohorts.current()
            .orElseThrow(() -> new IllegalStateException("COHORT_NOT_REGISTERED"));
        if (cohort.id() == null || cohort.members() == null ||
            cohort.members().size() != MindProspectiveCohortRepository.SIZE ||
            !"MA_RECLAIM_V1".equals(cohort.strategyCode()))
            throw new IllegalStateException("COHORT_INVALID");

        var page = outcomes.candidatePage(cohort.id(), offset, PAGE);
        int pending = 0, matched = 0, originalChanged = 0, outcomeChanged = 0, invalid = 0;
        for (var receipt : page) {
            if (!sameImmutableMember(cohort, receipt) ||
                receipt.recordedAt() == null || receipt.signalCandleAt() == null ||
                receipt.recordedAt().isAfter(now)) {
                invalid++;
                continue;
            }
            var saved = outcomes.recordedOutcome(receipt);
            if (saved.isEmpty()) {
                pending++;
                continue;
            }
            var observed = saved.get();
            if (!validStoredRecord(receipt, observed, now)) {
                invalid++;
                continue;
            }
            // Recreate EXACT original 21-candle capture from current stored
            // bars. No historical data can be silently substituted.
            try {
                var original = market.recentCandlesAsOf(
                    receipt.instrumentId(), "FIVE_MINUTE",
                    receipt.signalCandleAt(), MindSourceSnapshot.BARS);
                var reproduced = MindSourceSnapshot.fromCandles(
                    original, receipt.instrumentId(), receipt.exchange(),
                    receipt.tradingSymbol(), "FIVE_MINUTE",
                    receipt.signalCandleAt(), receipt.recordedAt());
                if (!receipt.originalSourceDigest().equals(reproduced.digest())) {
                    originalChanged++;
                    continue;
                }
            } catch (RuntimeException unavailableOrRevised) {
                originalChanged++;
                continue;
            }

            try {
                LocalDate day = receipt.signalCandleAt().atZone(IST).toLocalDate();
                var session = calendar.session(day);
                if (!session.tradingDay() ||
                    !receipt.signalCandleAt().isBefore(session.close().toInstant()) ||
                    receipt.signalCandleAt().isBefore(session.open().toInstant()) ||
                    observed.exitCandleAt().isBefore(observed.entryCandleAt()) ||
                    !observed.exitCandleAt().isBefore(session.close().toInstant())) {
                    outcomeChanged++;
                    continue;
                }
                var slots = calendar.expectedSlots(day,
                    HistoricalCandleInterval.FIVE_MINUTE);
                var candles = market.backtestCandles(
                    receipt.instrumentId(), "FIVE_MINUTE",
                    observed.entryCandleAt(), observed.exitCandleAt().plus(BAR), 75);
                var rerun = evaluator.evaluate(receipt, candles, slots, now);
                if (rerun.isEmpty() || !sameOutcome(observed, rerun.get())) {
                    outcomeChanged++;
                } else {
                    matched++;
                }
            } catch (RuntimeException unavailableOrRevised) {
                outcomeChanged++;
            }
        }

        return new Audit("mind.prospective-custody-audit.v1", now, offset,
            page.size(), pending, matched, originalChanged, outcomeChanged, invalid,
            page.isEmpty() ? "NO_CANDIDATES_ON_PAGE" :
                originalChanged + outcomeChanged + invalid > 0
                    ? "INTERNAL_EVIDENCE_NONREPRODUCIBLE"
                    : pending > 0 ? "INCOMPLETE_RESEARCH_EVIDENCE"
                    : "INTERNALLY_REPRODUCED_RESEARCH_ONLY",
            List.of("INTERNAL_DIGEST_NOT_INDEPENDENT_SOURCE_ATTESTATION",
                "PROSPECTIVE_SAMPLE_NOT_VALIDATED",
                "ACTUAL_BROKER_TAX_AND_STATUTORY_COSTS_NOT_PROVEN",
                "ACCOUNT_RISK_NOT_EVALUATED",
                "PAPER_SHADOW_ENROLLMENT_REQUIRED",
                "LIVE_EXECUTION_DISABLED"),
            "ABSTAIN", false, false, false, false);
    }

    private static boolean sameImmutableMember(
            MindProspectiveCohortRepository.Cohort cohort,
            MindProspectiveOutcomeRepository.Receipt receipt) {
        if (!cohort.id().equals(receipt.cohortId()) ||
            receipt.memberSlot() < 1 || receipt.memberSlot() > 20 ||
            receipt.catalogueVersion() == null ||
            receipt.tradingSymbol() == null || receipt.symbolToken() == null)
            return false;
        return cohort.members().stream().anyMatch(m ->
            m.slot() == receipt.memberSlot() &&
            m.instrumentId() == receipt.instrumentId() &&
            m.catalogueVersion().equals(receipt.catalogueVersion()) &&
            m.exchange().equals(receipt.exchange()) &&
            m.tradingSymbol().equals(receipt.tradingSymbol()) &&
            m.symbolToken().equals(receipt.symbolToken()));
    }

    private static boolean validStoredRecord(
            MindProspectiveOutcomeRepository.Receipt receipt,
            MindProspectiveOutcomeRepository.AuditedOutcome saved,
            Instant now) {
        return receipt.originalSourceDigest() != null &&
            receipt.originalSourceDigest().matches("[a-f0-9]{64}") &&
            receipt.candidateFingerprint() != null &&
            receipt.candidateFingerprint().matches("[a-f0-9]{64}") &&
            receipt.candidateFingerprint().equals(saved.candidateFingerprint()) &&
            receipt.originalSourceDigest().equals(saved.signalSourceDigest()) &&
            saved.outcomeSourceDigest() != null &&
            saved.outcomeSourceDigest().matches("[a-f0-9]{64}") &&
            saved.entryCandleAt() != null && saved.exitCandleAt() != null &&
            saved.entryCandleAt().equals(receipt.signalCandleAt().plus(BAR)) &&
            saved.evaluatedAt() != null && !saved.evaluatedAt().isAfter(now) &&
            saved.rawEntry() != null && saved.rawExit() != null &&
            saved.grossReturnPercent() != null &&
            saved.illustrativeNetReturnPercent() != null &&
            saved.sourceBars() >= 1 && saved.sourceBars() <= 75;
    }

    private static boolean sameOutcome(
            MindProspectiveOutcomeRepository.AuditedOutcome stored,
            MindProspectiveOutcomeEvaluator.Outcome rerun) {
        return stored.outcomeSourceDigest().equals(rerun.outcomeSourceDigest()) &&
            stored.entryCandleAt().equals(rerun.entryCandleAt()) &&
            stored.exitCandleAt().equals(rerun.exitCandleAt()) &&
            stored.exitReason().equals(rerun.exitReason()) &&
            stored.sourceBars() == rerun.sourceBars() &&
            stored.rawEntry().compareTo(rerun.rawEntry()) == 0 &&
            stored.rawExit().compareTo(rerun.rawExit()) == 0 &&
            stored.grossReturnPercent().compareTo(rerun.grossReturnPercent()) == 0 &&
            stored.illustrativeNetReturnPercent()
                .compareTo(rerun.illustrativeNetReturnPercent()) == 0;
    }
}
