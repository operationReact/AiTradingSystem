package in.aitradingsystem.mind;

import in.aitradingsystem.marketdata.CandleSeriesValidator;
import in.aitradingsystem.marketdata.MarketCandle;
import in.aitradingsystem.marketdata.MarketDataRepository;
import in.aitradingsystem.marketdata.MarketSessionCalendar;
import in.aitradingsystem.scanner.CandidateFingerprint;
import in.aitradingsystem.scanner.MovingAverageReclaimStrategy;
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

/**
 * One bounded, deterministic historical replay for an Indian cash-equity strategy.
 *
 * Replays stored MA_RECLAIM_V1 candidate evidence against 21 original candles,
 * bounded at the original signal cutoff. No signal generation endpoint, account
 * budget, inferred risk approval, broker access or PAPER order is exposed here.
 * The existing V56/V62 matched PAPER/SHADOW enrollment and capital checks cannot
 * be bypassed by this read-only projection.
 */
@Service
public class MindStrategyReplayService {
    public static final String CONTRACT = "mind.strategy-replay.v1";
    private static final String STRATEGY = MovingAverageReclaimStrategy.NAME;
    private static final int MAX_CANDIDATES = 200;
    private static final int REQUIRED_BARS = 21;
    private static final Duration RECENT = Duration.ofMinutes(15);
    private static final Duration MAX_SOURCE_AGE = Duration.ofMinutes(60);
    private final ResearchScanRepository scans;
    private final MarketDataRepository candles;
    private final MovingAverageReclaimStrategy strategy;
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

    public MindStrategyReplayService(
            ResearchScanRepository scans, MarketDataRepository candles,
            MovingAverageReclaimStrategy strategy, MarketSessionCalendar calendar,
            Clock clock) {
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
                    run.startedAt().isAfter(run.completedAt()) ||
                    run.completedAt().isAfter(now)) {
                return blocked(now, run, null, "INVALID", "SCAN_CLOCK_INVALID");
            }
            // Bounded to 200 ranked candidates. The absence of MA_RECLAIM in
            // this window is an explicit no-match, never a fabricated trade.
            ResearchScanCandidateView candidate = scans.rankedCandidates(run.id(), MAX_CANDIDATES)
                    .stream()
                    .filter(c -> c != null && STRATEGY.equals(c.strategy()))
                    .findFirst().orElse(null);
            if (candidate == null) return blocked(now, run, null,
                    "NO_MATCH", "NO_MA_RECLAIM_CANDIDATE_IN_BOUNDED_WINDOW");
            if (!validCandidate(run, candidate, now)) {
                return blocked(now, run, candidate, "INVALID",
                    "MISSING_OR_UNSAFE_CANDIDATE_PROVENANCE");
            }

            List<MarketCandle> bars = candles.recentCandlesAsOf(
                    candidate.instrumentId(), candidate.interval(),
                    candidate.candleTime(), REQUIRED_BARS);
            if (!validBars(run, candidate, bars)) return blocked(now, run,
                    candidate, "INVALID", "SOURCE_BARS_UNAVAILABLE_OR_UNVERIFIED");

            // This replay is historical, not a real-time signal. Re-evaluation
            // is deterministic and its fingerprint must exactly match the
            // persisted scanner result, even after any candle revisions.
            ScanCandidate reproduced = strategy.evaluate(new ScanContext(
                    candidate.instrumentId(), candidate.tradingSymbol(),
                    candidate.exchange(), candidate.interval(), run.startedAt(),
                    bars)).orElse(null);
            if (reproduced == null) return blocked(now, run, candidate,
                    "INVALID", "STRATEGY_REPLAY_NO_LONGER_MATCHES");
            String replayFingerprint = CandidateFingerprint.of(
                    reproduced.instrumentId(), reproduced.interval(),
                    reproduced.strategy(), strategy.descriptor().version(),
                    reproduced.candleTime(), reproduced.referencePrice(),
                    reproduced.suggestedStop(), reproduced.suggestedTarget());
            if (!replayFingerprint.equals(candidate.candidateFingerprint())) {
                return blocked(now, run, candidate, "INVALID",
                    "STRATEGY_FINGERPRINT_MISMATCH");
            }
            if (!now.isBefore(run.completedAt()) &&
                    Duration.between(run.completedAt(), now).compareTo(RECENT) > 0) {
                return blocked(now, run, candidate, "STALE", "RESEARCH_SCAN_STALE");
            }
            if (Duration.between(candidate.candleTime(), now).compareTo(MAX_SOURCE_AGE) > 0) {
                return blocked(now, run, candidate, "STALE", "SOURCE_CANDLE_STALE");
            }
            if (!calendar.isInsideCashSession(now)) return blocked(
                    now, run, candidate, "REPRODUCED", "CASH_MARKET_CLOSED");
            return result(now, run, candidate, "REPRODUCED", "WAIT",
                    List.of("STRUCTURAL_REPLAY_ONLY",
                            "SOURCE_KNOWN_AT_NOT_INDEPENDENTLY_PROVEN",
                            "ACCOUNT_RISK_NOT_EVALUATED",
                            "PAPER_SHADOW_ENROLLMENT_REQUIRED"));
        } catch (RuntimeException ex) {
            return blocked(now, null, null, "UNAVAILABLE", "REPLAY_SOURCE_UNAVAILABLE");
        }
    }

    private boolean validCandidate(ResearchScanSummary run, ResearchScanCandidateView c, Instant now) {
        return run.id().equals(c.scanRunId()) && c.candidateId() > 0 &&
            c.instrumentId() > 0 && c.exchange() != null && Set.of("NSE", "BSE").contains(c.exchange()) &&
            c.tradingSymbol() != null && c.tradingSymbol().endsWith("-EQ") &&
            "FIVE_MINUTE".equals(c.interval()) &&
            c.candleTime() != null && c.dataCutoffAt() != null &&
            c.candleTime().equals(c.dataCutoffAt()) &&
            !c.candleTime().isAfter(run.startedAt()) &&
            !c.candleTime().isAfter(now) &&
            c.catalogueVersion() != null &&
            c.strategyConfigVersion() != null && c.strategyConfigVersion() > 0 &&
            c.candidateFingerprint() != null &&
            c.candidateFingerprint().matches("[a-f0-9]{64}");
    }

    private boolean validBars(ResearchScanSummary run,
                              ResearchScanCandidateView c, List<MarketCandle> bars) {
        if (bars == null || bars.size() != REQUIRED_BARS ||
                !c.candleTime().equals(bars.getLast().candleTime()) ||
                !CandleSeriesValidator.validate(bars, run.startedAt(), null).valid()) return false;
        String source = bars.getFirst().source();
        if (source == null || source.isBlank() ||
                source.matches("(?i).*(TEST|FIXTURE|DEMO|MOCK|SYNTHETIC).*")) return false;
        for (MarketCandle bar : bars) {
            if (bar.instrumentId() != c.instrumentId() ||
                    !c.exchange().equals(bar.exchange()) ||
                    !c.tradingSymbol().equals(bar.tradingSymbol()) ||
                    !c.interval().equals(bar.interval()) ||
                    !c.catalogueVersion().equals(bar.catalogueVersion()) ||
                    !source.equals(bar.source()) ||
                    bar.fetchedAt() == null || bar.fetchedAt().isAfter(run.startedAt()) ||
                    bar.candleTime().isAfter(c.candleTime()) ||
                    bar.high().compareTo(bar.low()) < 0 ||
                    bar.high().compareTo(bar.open()) < 0 ||
                    bar.high().compareTo(bar.close()) < 0 ||
                    bar.low().compareTo(bar.open()) > 0 ||
                    bar.low().compareTo(bar.close()) > 0 ||
                    bar.volume() <= 0) return false;
        }
        return true;
    }

    private Replay blocked(Instant now, ResearchScanSummary run,
                           ResearchScanCandidateView candidate, String status,
                           String code) {
        return result(now, run, candidate, status, "ABSTAIN", List.of(code,
            "ACCOUNT_RISK_NOT_EVALUATED", "PAPER_SHADOW_ENROLLMENT_REQUIRED"));
    }

    private Replay result(Instant now, ResearchScanSummary run,
                          ResearchScanCandidateView candidate, String status,
                          String decision, List<String> blockers) {
        return new Replay(CONTRACT, now,
            run == null ? null : run.id(),
            STRATEGY, strategy.descriptor().version(),
            candidate == null ? null : candidate.exchange() + ":" + candidate.tradingSymbol(),
            candidate == null ? null : candidate.interval(),
            candidate == null ? null : candidate.candleTime(),
            candidate == null ? null : candidate.dataCutoffAt(),
            candidate == null ? null : candidate.candidateFingerprint(),
            status, decision, List.copyOf(blockers),
            false, "NOT_ENROLLED", false, false);
    }
}
