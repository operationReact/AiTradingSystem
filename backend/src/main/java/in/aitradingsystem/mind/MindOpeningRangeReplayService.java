package in.aitradingsystem.mind;

import in.aitradingsystem.marketdata.CandleSeriesValidator;
import in.aitradingsystem.marketdata.MarketCandle;
import in.aitradingsystem.marketdata.MarketDataRepository;
import in.aitradingsystem.marketdata.MarketSessionCalendar;
import in.aitradingsystem.scanner.CandidateFingerprint;
import in.aitradingsystem.scanner.OpeningRangeBreakoutStrategy;
import in.aitradingsystem.scanner.ResearchScanCandidateView;
import in.aitradingsystem.scanner.ResearchScanRepository;
import in.aitradingsystem.scanner.ResearchScanSummary;
import in.aitradingsystem.scanner.ScanCandidate;
import in.aitradingsystem.scanner.ScanContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Read-only, exact-input historical replay for the cash-equity opening-range strategy. */
@Service
public final class MindOpeningRangeReplayService {
    public static final String CONTRACT = "mind.opening-range-replay.v1";
    private static final String STRATEGY = OpeningRangeBreakoutStrategy.NAME;
    private static final int REQUIRED_BARS = MindSourceSnapshot.OPENING_RANGE_BARS;
    private static final Duration RECENT = Duration.ofMinutes(15);
    private static final Duration MAX_SOURCE_AGE = Duration.ofMinutes(60);
    private static final Set<String> INTERVALS = Set.of("ONE_MINUTE", "THREE_MINUTE", "FIVE_MINUTE");

    private final ResearchScanRepository scans;
    private final MarketDataRepository candles;
    private final OpeningRangeBreakoutStrategy strategy;
    private final MarketSessionCalendar calendar;
    private final Clock clock;

    public record Replay(String contractVersion, Instant generatedAt,
                         UUID scanRunId, String strategyCode, String strategyVersion,
                         String instrument, String interval, Instant signalAt,
                         Instant dataCutoffAt, String candidateFingerprint,
                         String replayStatus, String decisionPosture,
                         List<String> blockingReasons, boolean independentlyVerifiedSignal,
                         String paperShadowStatus, boolean paperOrderSubmitted,
                         boolean executionEnabled) {}

    public MindOpeningRangeReplayService(ResearchScanRepository scans,
            MarketDataRepository candles, OpeningRangeBreakoutStrategy strategy,
            MarketSessionCalendar calendar, Clock clock) {
        this.scans = scans;
        this.candles = candles;
        this.strategy = strategy;
        this.calendar = calendar;
        this.clock = clock;
    }

    public Replay inspect() {
        Instant now = clock.instant();
        try {
            Optional<ResearchScanSummary> latest = scans.latestCompletedRun();
            if (latest.isEmpty()) return blocked(now, null, null,
                    "UNAVAILABLE", "NO_COMPLETED_RESEARCH_SCAN");
            ResearchScanSummary run = latest.get();
            if (!"SUCCEEDED".equals(run.status())) return blocked(now, run, null,
                    "UNAVAILABLE", "LATEST_SCAN_NOT_SUCCESSFUL");
            if (run.startedAt() == null || run.completedAt() == null ||
                    run.startedAt().isAfter(run.completedAt()) || run.completedAt().isAfter(now)) {
                return blocked(now, run, null, "INVALID", "SCAN_CLOCK_INVALID");
            }

            ResearchScanCandidateView candidate = scans.rankedCandidates(run.id(), 200).stream()
                    .filter(c -> c != null && STRATEGY.equals(c.strategy()))
                    .findFirst().orElse(null);
            if (candidate == null) return blocked(now, run, null,
                    "NO_MATCH", "NO_OPENING_RANGE_CANDIDATE_IN_BOUNDED_WINDOW");
            if (!validCandidate(run, candidate, now)) {
                return blocked(now, run, candidate, "INVALID",
                        "MISSING_OR_UNSAFE_CANDIDATE_PROVENANCE");
            }

            List<MarketCandle> bars = candles.recentCandlesAsOf(
                    candidate.instrumentId(), candidate.interval(), candidate.candleTime(), REQUIRED_BARS);
            if (!validBars(run, candidate, bars)) {
                return blocked(now, run, candidate, "INVALID",
                        "SOURCE_BARS_UNAVAILABLE_OR_UNVERIFIED");
            }

            var frozen = scans.findMindInputCustody(run.id(), candidate.candidateFingerprint());
            if (frozen.isEmpty()) return blocked(now, run, candidate,
                    "INVALID", "INPUT_CUSTODY_NOT_RECORDED");
            var custody = frozen.get();
            if (custody.capturedAt() == null ||
                    custody.capturedAt().isBefore(run.startedAt()) ||
                    custody.capturedAt().isAfter(run.completedAt()) ||
                    custody.capturedAt().isAfter(now) ||
                    !candidate.candleTime().equals(custody.inputLastAt()) ||
                    !candidate.catalogueVersion().equals(custody.catalogueVersion()) ||
                    custody.maxFetchedAt() == null ||
                    custody.maxFetchedAt().isAfter(run.startedAt())) {
                return blocked(now, run, candidate, "INVALID",
                        "SOURCE_CUSTODY_CHRONOLOGY_INVALID");
            }

            MindSourceSnapshot reconstructed = MindSourceSnapshot.fromOpeningRangeCandles(
                    bars, candidate.instrumentId(), candidate.exchange(),
                    candidate.tradingSymbol(), candidate.interval(),
                    candidate.candleTime(), run.startedAt());
            if (!reconstructed.digest().equals(custody.sourceDigest()) ||
                    !reconstructed.canonicalInput().equals(custody.canonicalInput())) {
                return blocked(now, run, candidate, "INVALID",
                        "SOURCE_CANDLE_REVISION_DETECTED");
            }

            ScanCandidate reproduced = strategy.evaluate(new ScanContext(
                    candidate.instrumentId(), candidate.tradingSymbol(),
                    candidate.exchange(), candidate.interval(), run.startedAt(), bars)).orElse(null);
            if (reproduced == null) return blocked(now, run, candidate,
                    "INVALID", "STRATEGY_REPLAY_NO_LONGER_MATCHES");
            String fingerprint = CandidateFingerprint.of(
                    reproduced.instrumentId(), reproduced.interval(), reproduced.strategy(),
                    strategy.descriptor().version(), reproduced.candleTime(),
                    reproduced.referencePrice(), reproduced.suggestedStop(),
                    reproduced.suggestedTarget());
            if (!fingerprint.equals(candidate.candidateFingerprint())) {
                return blocked(now, run, candidate, "INVALID",
                        "STRATEGY_FINGERPRINT_MISMATCH");
            }

            if (Duration.between(run.completedAt(), now).compareTo(RECENT) > 0) {
                return blocked(now, run, candidate, "STALE", "RESEARCH_SCAN_STALE");
            }
            if (Duration.between(candidate.candleTime(), now).compareTo(MAX_SOURCE_AGE) > 0) {
                return blocked(now, run, candidate, "STALE", "SOURCE_CANDLE_STALE");
            }
            if (!calendar.isInsideCashSession(now)) return blocked(
                    now, run, candidate, "REPRODUCED", "CASH_MARKET_CLOSED");

            return result(now, run, candidate, "REPRODUCED", "WAIT",
                    List.of("STRUCTURAL_REPLAY_ONLY",
                            "CAPTURED_INPUT_DIGEST_REPRODUCED",
                            "SOURCE_KNOWN_AT_NOT_INDEPENDENTLY_PROVEN",
                            "AFTER_COST_WALK_FORWARD_NOT_VALIDATED",
                            "ACCOUNT_RISK_NOT_EVALUATED",
                            "PAPER_SHADOW_ENROLLMENT_REQUIRED"));
        } catch (RuntimeException ex) {
            return blocked(now, null, null, "UNAVAILABLE", "REPLAY_SOURCE_UNAVAILABLE");
        }
    }

    private boolean validCandidate(ResearchScanSummary run,
            ResearchScanCandidateView candidate, Instant now) {
        return run.id().equals(candidate.scanRunId()) && candidate.candidateId() > 0 &&
                candidate.instrumentId() > 0 && candidate.exchange() != null &&
                Set.of("NSE", "BSE").contains(candidate.exchange()) &&
                candidate.tradingSymbol() != null && candidate.tradingSymbol().endsWith("-EQ") &&
                INTERVALS.contains(candidate.interval()) &&
                candidate.candleTime() != null && candidate.dataCutoffAt() != null &&
                candidate.candleTime().equals(candidate.dataCutoffAt()) &&
                !candidate.candleTime().isAfter(run.startedAt()) &&
                !candidate.candleTime().isAfter(now) &&
                candidate.catalogueVersion() != null &&
                candidate.strategyConfigVersion() != null && candidate.strategyConfigVersion() > 0 &&
                candidate.candidateFingerprint() != null &&
                candidate.candidateFingerprint().matches("[a-f0-9]{64}");
    }

    private boolean validBars(ResearchScanSummary run,
            ResearchScanCandidateView candidate, List<MarketCandle> bars) {
        if (bars == null || bars.size() != REQUIRED_BARS ||
                !candidate.candleTime().equals(bars.getLast().candleTime()) ||
                !CandleSeriesValidator.validate(bars, run.startedAt(), null).valid()) return false;
        String source = bars.getFirst().source();
        if (source == null || source.isBlank() ||
                source.matches("(?i).*(TEST|FIXTURE|DEMO|MOCK|SYNTHETIC).*")) return false;
        for (MarketCandle bar : bars) {
            if (bar.instrumentId() != candidate.instrumentId() ||
                    !candidate.exchange().equals(bar.exchange()) ||
                    !candidate.tradingSymbol().equals(bar.tradingSymbol()) ||
                    !candidate.interval().equals(bar.interval()) ||
                    !candidate.catalogueVersion().equals(bar.catalogueVersion()) ||
                    !source.equals(bar.source()) || bar.fetchedAt() == null ||
                    bar.fetchedAt().isAfter(run.startedAt()) ||
                    bar.candleTime().isAfter(candidate.candleTime()) ||
                    bar.high().compareTo(bar.low()) < 0 ||
                    bar.high().compareTo(bar.open()) < 0 ||
                    bar.high().compareTo(bar.close()) < 0 ||
                    bar.low().compareTo(bar.open()) > 0 ||
                    bar.low().compareTo(bar.close()) > 0 || bar.volume() <= 0) return false;
        }
        return true;
    }

    private Replay blocked(Instant now, ResearchScanSummary run,
            ResearchScanCandidateView candidate, String status, String reason) {
        return result(now, run, candidate, status, "ABSTAIN", List.of(reason,
                "ACCOUNT_RISK_NOT_EVALUATED", "PAPER_SHADOW_ENROLLMENT_REQUIRED"));
    }

    private Replay result(Instant now, ResearchScanSummary run,
            ResearchScanCandidateView candidate, String status, String posture,
            List<String> reasons) {
        return new Replay(CONTRACT, now, run == null ? null : run.id(),
                STRATEGY, strategy.descriptor().version(),
                candidate == null ? null : candidate.exchange() + ":" + candidate.tradingSymbol(),
                candidate == null ? null : candidate.interval(),
                candidate == null ? null : candidate.candleTime(),
                candidate == null ? null : candidate.dataCutoffAt(),
                candidate == null ? null : candidate.candidateFingerprint(),
                status, posture, List.copyOf(reasons), false, "NOT_ENROLLED", false, false);
    }
}
