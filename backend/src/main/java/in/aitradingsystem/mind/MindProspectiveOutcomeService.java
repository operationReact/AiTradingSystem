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
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * MIND-04C2B: reconcile only genuine PRECOMMITTED prospective candidate
 * receipts against later verified market bars, never historical backfill signals.
 * Manual ADMIN invocation; no autonomous jobs, PAPER or LIVE orders.
 */
@Service
public class MindProspectiveOutcomeService {
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Duration SETTLE = Duration.ofMinutes(15);
    private static final Duration BAR = Duration.ofMinutes(5);
    private static final int MAX_PAGE = 100;

    private final MindProspectiveCohortRepository cohorts;
    private final MindProspectiveOutcomeRepository outcomes;
    private final MarketDataRepository market;
    private final MarketSessionCalendar calendar;
    private final MindProspectiveOutcomeEvaluator evaluator;
    private final Clock clock;

    public record Reconciliation(Instant evaluatedAt, int pageOffset, int scanned,
                                 int appended, int sourceBlocked, int notMature,
                                 int alreadyReconciled, String posture,
                                 boolean executionEnabled) {}

    public record Status(String contractVersion, Instant generatedAt,
                         String status, long precommittedCandidates,
                         long reconciledOutcomes, long positiveNetObservations,
                         List<String> blockers, String decisionPosture,
                         boolean outcomeValidated, boolean paperOrderSubmitted,
                         boolean executionEnabled) {}

    public MindProspectiveOutcomeService(MindProspectiveCohortRepository cohorts,
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

    /** Page offset is over ALL immutable candidate receipts, including resolved ones. */
    public Reconciliation reconcile(int pageOffset) {
        if (pageOffset < 0 || pageOffset > 15000 || pageOffset % MAX_PAGE != 0) {
            throw new IllegalArgumentException("INVALID_CANDIDATE_PAGE");
        }
        Instant now = clock.instant();
        var cohort = cohorts.current()
            .orElseThrow(() -> new IllegalStateException("COHORT_NOT_REGISTERED"));
        if (!validCohort(cohort)) throw new IllegalStateException("COHORT_INVALID");
        var receipts = outcomes.candidatePage(cohort.id(), pageOffset, MAX_PAGE);
        int written = 0, blocked = 0, notMature = 0, duplicate = 0;
        for (var receipt : receipts) {
            if (!belongsToFrozenCohort(cohort, receipt) ||
                !isEnrolledSession(cohort.observationStartsAt(), receipt.signalCandleAt())) {
                blocked++;
                continue;
            }
            LocalDate tradingDay = receipt.signalCandleAt().atZone(IST).toLocalDate();
            var session = calendar.session(tradingDay);
            if (!session.tradingDay() ||
                receipt.signalCandleAt().isBefore(session.open().toInstant()) ||
                !receipt.signalCandleAt().isBefore(session.close().toInstant())) {
                blocked++;
                continue;
            }
            // Entire session must have finished before outcomes are evaluated.
            if (now.isBefore(session.close().toInstant().plus(SETTLE))) {
                notMature++;
                continue;
            }
            if (outcomes.alreadyRecorded(receipt)) {
                duplicate++;
                continue;
            }
            Instant entryAt = receipt.signalCandleAt().plus(BAR);
            if (!entryAt.isBefore(session.close().toInstant())) {
                blocked++;
                continue;
            }
            var slots = calendar.expectedSlots(tradingDay,
                HistoricalCandleInterval.FIVE_MINUTE);
            var candles = market.backtestCandles(receipt.instrumentId(),
                "FIVE_MINUTE", entryAt, session.close().toInstant(), 75);
            var outcome = evaluator.evaluate(receipt, candles, slots, now);
            if (outcome.isEmpty()) {
                blocked++;
            } else if (outcomes.append(receipt, outcome.get(), now)) {
                written++;
            } else {
                duplicate++;
            }
        }
        return new Reconciliation(now, pageOffset, receipts.size(), written,
            blocked, notMature, duplicate, "ABSTAIN", false);
    }

    /** Customer GET never recalculates, captures, backfills or inserts outcomes. */
    public Status inspect() {
        Instant now = clock.instant();
        try {
            Optional<MindProspectiveCohortRepository.Cohort> saved = cohorts.current();
            if (saved.isEmpty()) return unavailable(now, "COHORT_NOT_REGISTERED");
            var cohort = saved.get();
            if (!validCohort(cohort)) return unavailable(now, "COHORT_INVALID");
            var counts = outcomes.totals(cohort.id());
            if (counts.candidates() < 0 || counts.evaluated() < 0 ||
                counts.evaluated() > counts.candidates() ||
                counts.netPositive() < 0 || counts.netPositive() > counts.evaluated())
                return unavailable(now, "OUTCOME_COUNTS_INVALID");
            String status = counts.candidates() == 0 ? "AWAITING_CANDIDATES"
                : counts.evaluated() == 0 ? "AWAITING_OUTCOMES"
                : counts.evaluated() < counts.candidates() ? "PARTIAL_RESEARCH_OUTCOMES"
                : "RESEARCH_OUTCOMES_RECORDED";
            return new Status("mind.prospective-outcomes.v1", now, status,
                counts.candidates(), counts.evaluated(), counts.netPositive(),
                blockers("PROSPECTIVE_SAMPLE_NOT_VALIDATED"),
                "ABSTAIN", false, false, false);
        } catch (RuntimeException unexpected) {
            return unavailable(now, "OUTCOME_LEDGER_UNAVAILABLE");
        }
    }

    private boolean isEnrolledSession(Instant start, Instant signalAt) {
        if (start == null || signalAt == null || signalAt.isBefore(start)) return false;
        LocalDate first = start.atZone(IST).toLocalDate();
        LocalDate current = signalAt.atZone(IST).toLocalDate();
        if (current.isAfter(first.plusDays(40))) return false;
        int sessions = 0;
        for (LocalDate day = first; !day.isAfter(current); day = day.plusDays(1)) {
            if (calendar.session(day).tradingDay()) {
                sessions++;
                if (day.equals(current)) return sessions <= 10;
            }
        }
        return false;
    }

    private static boolean belongsToFrozenCohort(
            MindProspectiveCohortRepository.Cohort cohort,
            MindProspectiveOutcomeRepository.Receipt receipt) {
        if (!cohort.id().equals(receipt.cohortId()) ||
            receipt.memberSlot() < 1 || receipt.memberSlot() > 20 ||
            receipt.recordedAt() == null || receipt.signalCandleAt() == null ||
            receipt.recordedAt().isBefore(cohort.registeredAt())) return false;
        return cohort.members().stream().anyMatch(m ->
            m.slot() == receipt.memberSlot() &&
            m.instrumentId() == receipt.instrumentId() &&
            m.catalogueVersion().equals(receipt.catalogueVersion()) &&
            m.exchange().equals(receipt.exchange()) &&
            m.tradingSymbol().equals(receipt.tradingSymbol()) &&
            m.symbolToken().equals(receipt.symbolToken()));
    }

    private static boolean validCohort(MindProspectiveCohortRepository.Cohort cohort) {
        return cohort != null && cohort.id() != null &&
            cohort.registeredAt() != null && cohort.observationStartsAt() != null &&
            cohort.observationStartsAt().isAfter(cohort.registeredAt()) &&
            cohort.members() != null &&
            cohort.members().size() == MindProspectiveCohortRepository.SIZE &&
            "MA_RECLAIM_V1".equals(cohort.strategyCode()) &&
            cohort.targetSessions() == 10;
    }

    private static List<String> blockers(String reason) {
        return List.of(reason,
            "SOURCE_PROVENANCE_NOT_INDEPENDENTLY_ATTESTED",
            "ESTIMATED_COSTS_NOT_ACTUAL_INDIAN_STATUTORY_CHARGES",
            "ACCOUNT_RISK_NOT_EVALUATED",
            "PAPER_SHADOW_ENROLLMENT_REQUIRED",
            "LIVE_EXECUTION_DISABLED");
    }

    private static Status unavailable(Instant at, String reason) {
        return new Status("mind.prospective-outcomes.v1", at,
            "COHORT_NOT_REGISTERED".equals(reason) ? "UNREGISTERED" : "UNAVAILABLE",
            0, 0, 0, blockers(reason), "ABSTAIN", false, false, false);
    }
}
