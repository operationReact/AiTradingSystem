package in.aitradingsystem.tradingrooms;

import in.aitradingsystem.marketdata.MarketSessionCalendar;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * STR-176: actual production source join + actual evaluator, re-run at each
 * past decision's own time, followed by linked, after-cost chronological OOS.
 *
 * It does NOT synthesize a first-known time, historical fill, spread, or OI.
 * The existing source service independently re-reads rights/first-known data
 * at EACH daily decision instant; no final-day query used for earlier dates.
 * No provider/network/trade call and NO independently accepted E2E claim.
 */
@Service
public class Fno176SourceLinkedWalkForwardV1 {
    public static final String CONTRACT =
        "minttick.str176.source-linked-original-asof-aftercost-research.v1";
    private static final String STRATEGY = FnoFuturesMomentumV1.STRATEGY_ID;
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    // A decision after completed previous-day OHLCV/OI, before new trading.
    private static final LocalTime DECISION_AT = LocalTime.of(9, 0);
    private static final int MAX_CALENDAR_DAYS = 26;

    public record Report(
        String contractVersion, String strategyId, String state,
        String reasonCode, int historicalDecisions, int trainingDecisions,
        int outOfSampleDecisions, int researchCandidateDecisions,
        int matchedCompletedTrades, List<DecisionEvidence> decisions,
        FnoHistoricalAfterCostWalkForwardV1.Report afterCost,
        boolean independentlyVerifiedHistoricalE2E, boolean paperOrdersPlaced,
        boolean liveOrdersPlaced) {}

    public record DecisionEvidence(
        Instant asOf, boolean outOfSample, String evaluatorResult,
        String sourceWindowReceiptSha256, String evaluatorReasonCode) {}

    private final Fno176VerifiedHistoricalReplayService replaySource;
    private final MarketSessionCalendar calendar;
    private final Clock clock;

    @Autowired
    public Fno176SourceLinkedWalkForwardV1(
            Fno176VerifiedHistoricalReplayService replaySource,
            MarketSessionCalendar calendar) {
        this(replaySource, calendar, Clock.systemUTC());
    }

    Fno176SourceLinkedWalkForwardV1(
            Fno176VerifiedHistoricalReplayService replaySource,
            MarketSessionCalendar calendar, Clock clock) {
        this.replaySource = Objects.requireNonNull(replaySource);
        this.calendar = Objects.requireNonNull(calendar);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * Every actual exchange trading date in the caller's bounded range must
     * have an eligible seven-completed-session replay. Fail the WHOLE run on
     * one missing source/calendar/as-of sample; never cherry-pick winners.
     *
     * Historical fill records MUST be independently sourced by the caller.
     * They are linked only to evaluator candidate dates before accounting:
     * this method cannot itself prove historical bid/ask execution or custody.
     */
    public Report inspect(String contractKey, LocalDate firstDecisionDay,
            LocalDate lastDecisionDay, LocalDate finalTrainingDay,
            List<FnoHistoricalAfterCostWalkForwardV1.CompletedTrade> completed) {
        if (contractKey == null
                || !contractKey.matches("NSE_FO\\|[A-Za-z0-9._-]{1,64}")
                || firstDecisionDay == null || lastDecisionDay == null
                || finalTrainingDay == null
                || !firstDecisionDay.isBefore(lastDecisionDay)
                || finalTrainingDay.isBefore(firstDecisionDay)
                || !finalTrainingDay.isBefore(lastDecisionDay)
                || ChronoUnit.DAYS.between(firstDecisionDay, lastDecisionDay)
                   >= MAX_CALENDAR_DAYS
                || firstDecisionDay.isBefore(
                    clock.instant().atZone(IST).toLocalDate().minusDays(365)))
            return blocked("STR176_OOS_REPLAY_SCOPE_INVALID");

        List<DecisionEvidence> evidence = new ArrayList<>();
        Map<Instant,String> originalCandidates = new HashMap<>();
        int training = 0, outOfSample = 0, candidates = 0;
        for (var day = firstDecisionDay; !day.isAfter(lastDecisionDay);
                day = day.plusDays(1)) {
            final boolean tradingDay;
            try {
                tradingDay = calendar.isTradingDay(day);
            } catch (RuntimeException unavailable) {
                return blocked("STR176_ORIGINAL_TRADING_CALENDAR_UNAVAILABLE");
            }
            if (!tradingDay) continue;
            Instant decisionAt = day.atTime(DECISION_AT).atZone(IST).toInstant();
            if (decisionAt.isAfter(clock.instant()))
                return blocked("STR176_FUTURE_DECISION_NOT_OBSERVED");
            final Fno176VerifiedHistoricalReplayService.Evidence proof;
            try {
                proof = replaySource.inspect(contractKey, decisionAt);
            } catch (RuntimeException unavailable) {
                return blocked("STR176_ORIGINAL_REPLAY_SOURCE_UNAVAILABLE");
            }
            if (proof == null || !STRATEGY.equals(proof.strategyId())
                    || !"SOURCE_JOINED_EVALUATOR_RAN".equals(proof.state())
                    || proof.replay() == null
                    || !proof.replay().state().equals(
                        "EVALUATOR_REPLAY_RESEARCH_ONLY_NOT_E2E")
                    || proof.replay().windowsAttempted() != 1
                    || proof.replay().windowsEvaluatorRan() != 1
                    || proof.replay().decisions().size() != 1
                    || proof.originalRows() < 7
                    || proof.sessionWindow() != 7) {
                // Do not expose backend SQL/rights diagnostics or accept a
                // subset of convenient profitable decisions.
                return blocked("STR176_INDEPENDENT_DAILY_SOURCE_REPLAY_INCOMPLETE");
            }
            var signal = proof.replay().decisions().getFirst();
            if (!decisionAt.equals(signal.asOf())
                    || !STRATEGY.equals(signal.strategyId())
                    || signal.inputSessions() != 7
                    || signal.inputReceiptHash() == null
                    || !signal.inputReceiptHash().matches("[0-9a-f]{64}")
                    || !Set.of("NO_SIGNAL","RESEARCH_CANDIDATE")
                           .contains(signal.result()))
                return blocked("STR176_DAILY_EVALUATOR_EVIDENCE_INVALID");
            boolean oos = day.isAfter(finalTrainingDay);
            if (oos) outOfSample++; else training++;
            if ("RESEARCH_CANDIDATE".equals(signal.result())) {
                candidates++;
                originalCandidates.put(decisionAt, signal.inputReceiptHash());
            }
            evidence.add(new DecisionEvidence(decisionAt, oos,
                signal.result(), signal.inputReceiptHash(),
                signal.evaluatorReasonCode()));
        }
        if (training == 0 || outOfSample == 0)
            return blocked("STR176_TRAIN_OR_OOS_SOURCE_SESSIONS_MISSING");

        // No fill is manufactured for a research candidate and no PnL is
        // guessed from close-to-close candles. Outcomes must be furnished
        // by an independently checked external fill-provenance source.
        if (completed == null || completed.isEmpty())
            return new Report(CONTRACT,STRATEGY,
                "SOURCE_LINKED_WALK_FORWARD_NO_ORIGINAL_FILLS_NOT_E2E",
                "STR176_INDEPENDENT_ORIGINAL_FILLS_REQUIRED",
                evidence.size(),training,outOfSample,candidates,0,
                List.copyOf(evidence),null,false,false,false);

        if (completed.size() > originalCandidates.size())
            return blocked("STR176_COMPLETED_FILLS_NOT_LINKED_TO_RESEARCH_CANDIDATES");
        Set<Instant> seenTrades = new HashSet<>();
        for (var trade : completed) {
            if (trade == null || !STRATEGY.equals(trade.strategyId())
                    || !contractKey.equals(trade.contractKey())
                    || !originalCandidates.containsKey(trade.entryDecisionAt())
                    || !seenTrades.add(trade.entryDecisionAt()))
                return blocked("STR176_COMPLETED_FILLS_NOT_LINKED_TO_RESEARCH_CANDIDATES");
        }

        // This accounting module refuses crossed train/OOS trades and
        // computes net returns only from externally provided completed fills.
        Instant split = finalTrainingDay.plusDays(1)
            .atStartOfDay(IST).toInstant();
        var afterCost=FnoHistoricalAfterCostWalkForwardV1.evaluate(
            STRATEGY,clock.instant(),split,true,completed);
        return new Report(CONTRACT,STRATEGY,
            "BLOCKED_NOT_HISTORICAL_E2E".equals(afterCost.state())
                ? "SOURCE_LINKED_AFTER_COST_INPUT_BLOCKED_NOT_E2E"
                : "SOURCE_LINKED_AFTER_COST_RESEARCH_ONLY_NOT_E2E",
            afterCost.reasonCode(),evidence.size(),training,outOfSample,
            candidates,completed.size(),List.copyOf(evidence),afterCost,
            false,false,false);
    }

    private static Report blocked(String reason) {
        return new Report(CONTRACT,STRATEGY,"BLOCKED_NOT_HISTORICAL_E2E",
            reason,0,0,0,0,0,List.of(),null,false,false,false);
    }
}
