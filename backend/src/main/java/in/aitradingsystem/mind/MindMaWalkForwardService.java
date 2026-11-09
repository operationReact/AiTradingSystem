package in.aitradingsystem.mind;

import in.aitradingsystem.marketdata.ExpectedCandleSlot;
import in.aitradingsystem.marketdata.HistoricalCandleInterval;
import in.aitradingsystem.marketdata.MarketCandle;
import in.aitradingsystem.marketdata.MarketDataRepository;
import in.aitradingsystem.marketdata.MarketSessionCalendar;
import in.aitradingsystem.scanner.MovingAverageReclaimStrategy;
import in.aitradingsystem.scanner.ResearchScanCandidateView;
import in.aitradingsystem.scanner.ResearchScanRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Read-only, bounded, unoptimized walk-forward research projection for exactly
 * MA_RECLAIM_V1. Never interprets SMA_TREND_VOLUME validation as MA_RECLAIM.
 *
 * This is a fixed-configuration, post-selection historical study. It is NOT a
 * predictive validation/promotion or independent as-of source attestation.
 */
@Service
public final class MindMaWalkForwardService {
    public static final String CONTRACT = "mind.ma-walkforward.v1";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final int LOOKBACK_DAYS = 180;
    private static final int TRAINING_DAYS = 60;
    private static final int VALIDATION_DAYS = 20;
    private static final int STEP_DAYS = VALIDATION_DAYS;
    private static final int MAX_CANDLES = 20000;
    private static final int REQUIRED_FOLDS = 3;
    private static final int REQUIRED_OOS_TRADES = 20;
    private static final BigDecimal MIN_COVERAGE = new BigDecimal("95");
    private static final String STRATEGY = MovingAverageReclaimStrategy.NAME;

    private final ResearchScanRepository scans;
    private final MarketDataRepository candles;
    private final MarketSessionCalendar calendar;
    private final MindMaWalkForwardEvaluator evaluator;
    private final Clock clock;

    public record Fold(
        int number, LocalDate trainingFrom, LocalDate trainingTo,
        LocalDate validationFrom, LocalDate validationTo,
        BigDecimal trainingCoveragePercent, BigDecimal validationCoveragePercent,
        String status, MindMaWalkForwardEvaluator.Metrics training,
        MindMaWalkForwardEvaluator.Metrics validation
    ) {}

    public record Report(
        String contractVersion, Instant generatedAt, String status,
        String strategyCode, String strategyVersion,
        String instrument, String interval, String selectionMethod,
        LocalDate from, LocalDate to,
        MindMaWalkForwardEvaluator.Assumptions assumptions, List<Fold> folds,
        int completedFolds, int oosTrades, BigDecimal meanOosReturnPercent,
        BigDecimal worstOosDrawdownPercent, BigDecimal oosGrossPnl,
        BigDecimal oosFeeCost, BigDecimal oosSlippageCost, BigDecimal oosNetPnl,
        List<String> blockers, String decisionPosture,
        boolean sourceKnownAtIndependentlyVerified, boolean validatedTradingEdge,
        boolean paperOrderSubmitted, boolean executionEnabled
    ) {}

    public MindMaWalkForwardService(ResearchScanRepository scans,
                                   MarketDataRepository candles,
                                   MarketSessionCalendar calendar,
                                   MindMaWalkForwardEvaluator evaluator,
                                   Clock clock) {
        this.scans = scans;
        this.candles = candles;
        this.calendar = calendar;
        this.evaluator = evaluator;
        this.clock = clock;
    }

    public Report inspect() {
        Instant now = clock.instant();
        LocalDate to = now.atZone(IST).toLocalDate().minusDays(1);
        LocalDate from = to.minusDays(LOOKBACK_DAYS - 1);
        try {
            var recent = scans.latestCompletedRun();
            if (recent.isEmpty() || !"SUCCEEDED".equals(recent.get().status())) {
                return unavailable(now, from, to, "LATEST_SUCCESSFUL_SCAN_UNAVAILABLE");
            }
            var run = recent.get();
            if (run.completedAt() == null || run.completedAt().isAfter(now) ||
                run.completedAt().isBefore(now.minusSeconds(30L * 86400))) {
                return unavailable(now, from, to, "RECENT_REFERENCE_SCAN_UNAVAILABLE");
            }
            // Candidate selection is retrospective and selection-biased.
            // This is always disclosed and never grants model promotion.
            ResearchScanCandidateView selected = scans.rankedCandidates(run.id(), 200)
                .stream()
                .filter(c -> c != null && STRATEGY.equals(c.strategy()) &&
                    "FIVE_MINUTE".equals(c.interval()) &&
                    c.exchange() != null && Set.of("NSE", "BSE").contains(c.exchange()) &&
                    c.tradingSymbol() != null && c.tradingSymbol().endsWith("-EQ") &&
                    c.candidateFingerprint() != null &&
                    c.candidateFingerprint().matches("[a-f0-9]{64}") &&
                    c.catalogueVersion() != null)
                .findFirst().orElse(null);
            if (selected == null) return unavailable(now, from, to,
                "NO_ELIGIBLE_MA_RECLAIM_CANDIDATE");
            var custody = scans.findMindInputCustody(run.id(), selected.candidateFingerprint());
            if (custody.isEmpty()) {
                return unavailable(now, from, to, "REFERENCE_SCAN_CUSTODY_MISSING");
            }
            // V72 custody must match the original scan's 21 inputs before
            // this instrument is even considered for historical research.
            // Presence of a hash alone is not an integrity verification.
            if (run.startedAt() == null || run.startedAt().isAfter(run.completedAt()) ||
                selected.candleTime() == null || selected.catalogueVersion() == null ||
                custody.get().capturedAt() == null ||
                custody.get().capturedAt().isBefore(run.startedAt()) ||
                custody.get().capturedAt().isAfter(run.completedAt()) ||
                !selected.catalogueVersion().equals(custody.get().catalogueVersion()) ||
                !selected.candleTime().equals(custody.get().inputLastAt()) ||
                custody.get().maxFetchedAt() == null ||
                custody.get().maxFetchedAt().isAfter(run.startedAt())) {
                return unavailable(now, from, to, "REFERENCE_CUSTODY_CHRONOLOGY_INVALID");
            }
            List<MarketCandle> referenceBars = candles.recentCandlesAsOf(
                selected.instrumentId(), selected.interval(),
                selected.candleTime(), MindSourceSnapshot.BARS);
            MindSourceSnapshot reference = MindSourceSnapshot.fromCandles(
                referenceBars, selected.instrumentId(), selected.exchange(),
                selected.tradingSymbol(), selected.interval(),
                selected.candleTime(), run.startedAt());
            if (!reference.digest().equals(custody.get().sourceDigest()) ||
                !reference.canonicalInput().equals(custody.get().canonicalInput())) {
                return unavailable(now, from, to, "REFERENCE_INPUT_REVISION_DETECTED");
            }

            Instant start = from.atStartOfDay(IST).toInstant();
            Instant end = to.plusDays(1).atStartOfDay(IST).toInstant();
            List<MarketCandle> bars = candles.backtestCandles(
                selected.instrumentId(), "FIVE_MINUTE", start, end, MAX_CANDLES + 1);
            if (bars == null || bars.size() > MAX_CANDLES) {
                return unavailable(now, from, to, "HISTORICAL_WINDOW_OVER_LIMIT");
            }

            Map<LocalDate, Set<Instant>> expected = expectedCalendar(from, to);
            if (expected.values().stream().mapToInt(Set::size).sum() == 0) {
                return unavailable(now, from, to, "EXPECTED_MARKET_SLOTS_UNAVAILABLE");
            }
            if (!validSourceHistory(bars, selected, start, end, now, expected)) {
                return unavailable(now, from, to, "HISTORICAL_SOURCE_INVALID_OR_GAPPED");
            }

            List<Fold> folds = new ArrayList<>();
            BigDecimal returnSum = BigDecimal.ZERO;
            BigDecimal worstDrawdown = BigDecimal.ZERO;
            BigDecimal gross = BigDecimal.ZERO;
            BigDecimal fees = BigDecimal.ZERO;
            BigDecimal slippage = BigDecimal.ZERO;
            BigDecimal net = BigDecimal.ZERO;
            int completed = 0;
            int tradedFolds = 0;
            int trades = 0;
            for (int offset = 0, foldNumber = 1;
                 offset + TRAINING_DAYS + VALIDATION_DAYS <= LOOKBACK_DAYS;
                 offset += STEP_DAYS, foldNumber++) {
                LocalDate trainFrom = from.plusDays(offset);
                LocalDate trainTo = trainFrom.plusDays(TRAINING_DAYS - 1);
                LocalDate valFrom = trainTo.plusDays(1);
                LocalDate valTo = valFrom.plusDays(VALIDATION_DAYS - 1);
                List<MarketCandle> train = between(bars, trainFrom, trainTo);
                List<MarketCandle> validation = between(bars, valFrom, valTo);
                BigDecimal trainCoverage = coverage(train, expected, trainFrom, trainTo);
                BigDecimal valCoverage = coverage(validation, expected, valFrom, valTo);

                if (trainCoverage.compareTo(MIN_COVERAGE) < 0 ||
                    valCoverage.compareTo(MIN_COVERAGE) < 0 ||
                    train.size() < 22 || validation.size() < 22) {
                    folds.add(new Fold(foldNumber, trainFrom, trainTo,
                        valFrom, valTo, trainCoverage, valCoverage,
                        "INSUFFICIENT_COVERAGE", null, null));
                    continue;
                }

                // Both windows run independently with the SAME fixed parameters.
                // No training optimization and no training position carries over.
                var trainMetrics = evaluator.evaluate(train);
                var validationMetrics = evaluator.evaluate(validation);
                folds.add(new Fold(foldNumber, trainFrom, trainTo, valFrom, valTo,
                    trainCoverage, valCoverage, "EVALUATED", trainMetrics, validationMetrics));
                completed++;
                if (validationMetrics.closedTrades() > 0) tradedFolds++;
                trades += validationMetrics.closedTrades();
                returnSum = returnSum.add(validationMetrics.returnPercent());
                worstDrawdown = worstDrawdown.max(validationMetrics.maxDrawdownPercent());
                gross = gross.add(validationMetrics.grossPnl());
                fees = fees.add(validationMetrics.feeCost());
                slippage = slippage.add(validationMetrics.slippageCost());
                net = net.add(validationMetrics.netPnl());
            }

            boolean completeCoverage = completed == folds.size() && completed >= REQUIRED_FOLDS;
            boolean sufficientTrades = trades >= REQUIRED_OOS_TRADES &&
                tradedFolds >= REQUIRED_FOLDS;
            String status = !completeCoverage ? "INSUFFICIENT_COVERAGE"
                : !sufficientTrades ? "INSUFFICIENT_SAMPLE"
                : "EVALUATED_RESEARCH_ONLY";
            List<String> blockers = new ArrayList<>(baseBlockers());
            if (!completeCoverage) blockers.add("OUT_OF_SAMPLE_COVERAGE_INCOMPLETE");
            if (!sufficientTrades) blockers.add("OUT_OF_SAMPLE_TRADE_SAMPLE_INSUFFICIENT");
            if (!"EVALUATED_RESEARCH_ONLY".equals(status)) blockers.add("NO_VALIDATED_PERFORMANCE_CLAIM");
            return new Report(CONTRACT, now, status, STRATEGY, "1",
                selected.exchange() + ":" + selected.tradingSymbol(), "FIVE_MINUTE",
                "RECENT_SCANNER_CANDIDATE_SELECTION_BIASED",
                from, to, evaluator.assumptions(), List.copyOf(folds),
                completed, trades,
                completed == 0 ? null : returnSum.divide(
                    BigDecimal.valueOf(completed), 4, RoundingMode.HALF_UP),
                completed == 0 ? null : worstDrawdown.setScale(4, RoundingMode.HALF_UP),
                completed == 0 ? null : gross.setScale(2, RoundingMode.HALF_UP),
                completed == 0 ? null : fees.setScale(2, RoundingMode.HALF_UP),
                completed == 0 ? null : slippage.setScale(2, RoundingMode.HALF_UP),
                completed == 0 ? null : net.setScale(2, RoundingMode.HALF_UP),
                List.copyOf(blockers), "ABSTAIN", false, false, false, false);
        } catch (RuntimeException exception) {
            // Fail closed: no DB internals or provider failure text escapes.
            return unavailable(now, from, to, "WALK_FORWARD_EVIDENCE_UNAVAILABLE");
        }
    }

    private Map<LocalDate, Set<Instant>> expectedCalendar(LocalDate from, LocalDate to) {
        Map<LocalDate, Set<Instant>> result = new HashMap<>();
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            List<ExpectedCandleSlot> slots = calendar.expectedSlots(
                day, HistoricalCandleInterval.FIVE_MINUTE);
            Set<Instant> times = new HashSet<>();
            for (ExpectedCandleSlot slot : slots) times.add(slot.timestamp());
            if (times.size() != slots.size()) {
                throw new IllegalArgumentException("Non-unique expected slots");
            }
            result.put(day, Set.copyOf(times));
        }
        return result;
    }

    private boolean validSourceHistory(List<MarketCandle> bars,
                                       ResearchScanCandidateView selected,
                                       Instant start, Instant end, Instant now,
                                       Map<LocalDate, Set<Instant>> expected) {
        Instant previous = null;
        String source = null;
        Set<Instant> times = new HashSet<>();
        for (MarketCandle bar : bars) {
            if (bar == null || bar.candleTime() == null ||
                bar.candleTime().isBefore(start) || !bar.candleTime().isBefore(end) ||
                bar.candleTime().isAfter(now) ||
                (previous != null && !bar.candleTime().isAfter(previous)) ||
                !times.add(bar.candleTime()) ||
                bar.instrumentId() != selected.instrumentId() ||
                !selected.exchange().equals(bar.exchange()) ||
                !selected.tradingSymbol().equals(bar.tradingSymbol()) ||
                !selected.catalogueVersion().equals(bar.catalogueVersion()) ||
                !"FIVE_MINUTE".equals(bar.interval()) ||
                bar.symbolToken() == null || bar.symbolToken().isBlank() ||
                bar.fetchedAt() == null || bar.fetchedAt().isAfter(now) ||
                bar.source() == null || bar.source().isBlank() ||
                bar.source().matches("(?i).*(TEST|DEMO|MOCK|FIXTURE|SYNTHETIC).*") ||
                !validOhlc(bar)) return false;
            if (source == null) source = bar.source();
            if (!source.equals(bar.source())) return false;
            LocalDate date = bar.candleTime().atZone(IST).toLocalDate();
            if (!expected.getOrDefault(date, Set.of()).contains(bar.candleTime())) return false;
            previous = bar.candleTime();
        }
        return true;
    }

    private static boolean validOhlc(MarketCandle b) {
        return b.open() != null && b.high() != null && b.low() != null &&
            b.close() != null && b.volume() > 0 &&
            b.open().signum() > 0 && b.high().signum() > 0 &&
            b.low().signum() > 0 && b.close().signum() > 0 &&
            b.high().compareTo(b.low()) >= 0 &&
            b.high().compareTo(b.open()) >= 0 &&
            b.high().compareTo(b.close()) >= 0 &&
            b.low().compareTo(b.open()) <= 0 &&
            b.low().compareTo(b.close()) <= 0;
    }

    private static List<MarketCandle> between(List<MarketCandle> bars,
                                              LocalDate from, LocalDate to) {
        return bars.stream().filter(bar -> {
            LocalDate date = bar.candleTime().atZone(IST).toLocalDate();
            return !date.isBefore(from) && !date.isAfter(to);
        }).toList();
    }

    private static BigDecimal coverage(List<MarketCandle> actual,
                                       Map<LocalDate, Set<Instant>> expected,
                                       LocalDate from, LocalDate to) {
        int slots = 0;
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            slots += expected.getOrDefault(day, Set.of()).size();
        }
        return slots == 0 ? BigDecimal.ZERO.setScale(2) :
            BigDecimal.valueOf(actual.size()).multiply(
                new BigDecimal("100")).divide(BigDecimal.valueOf(slots), 2, RoundingMode.DOWN);
    }

    private Report unavailable(Instant now, LocalDate from, LocalDate to, String reason) {
        List<String> reasons = new ArrayList<>(baseBlockers());
        reasons.add(reason);
        return new Report(CONTRACT, now, "UNAVAILABLE", STRATEGY, "1",
            null, "FIVE_MINUTE", "RECENT_SCANNER_CANDIDATE_SELECTION_BIASED",
            from, to, evaluator.assumptions(), List.of(), 0, 0,
            null, null, null, null, null, null,
            List.copyOf(reasons), "ABSTAIN", false, false, false, false);
    }

    private static List<String> baseBlockers() {
        return List.of("SELECTION_BIAS_NOT_CORRECTED",
            "SOURCE_KNOWN_AT_NOT_INDEPENDENTLY_PROVEN",
            "COST_MODEL_ESTIMATE_NOT_STATUTORY_CHARGES",
            "ACCOUNT_RISK_NOT_EVALUATED",
            "PAPER_SHADOW_ENROLLMENT_REQUIRED",
            "LIVE_EXECUTION_DISABLED");
    }
}
