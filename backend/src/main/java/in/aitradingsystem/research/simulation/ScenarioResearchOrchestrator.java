package in.aitradingsystem.research.simulation;

import in.aitradingsystem.backtest.BacktestRepository;
import in.aitradingsystem.backtest.BacktestRunSummary;
import in.aitradingsystem.scanner.StrategyRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ScenarioResearchOrchestrator {
    private final ResearchEvaluationRepository evaluations;
    private final ScenarioRunRepository scenarioRuns;
    private final ScenarioSimulationService simulations;
    private final BacktestRepository backtests;
    private final StrategyRegistry strategies;

    public ScenarioResearchOrchestrator(
            ResearchEvaluationRepository evaluations,
            ScenarioRunRepository scenarioRuns,
            ScenarioSimulationService simulations,
            BacktestRepository backtests,
            StrategyRegistry strategies) {
        this.evaluations = evaluations;
        this.scenarioRuns = scenarioRuns;
        this.simulations = simulations;
        this.backtests = backtests;
        this.strategies = strategies;
    }

    public ResearchEvaluationResult evaluateCandidate(long candidateId, ResearchEvaluationRequest request) {
        if (request == null) throw new IllegalArgumentException("request is required");
        ResearchCandidateProvenance candidate = evaluations.requireCandidate(candidateId);
        validateHistoryBoundary(candidate, request);
        resolveStrategy(candidate);
        evaluations.verifyImmutableProvenance(candidate);
        UUID backtestRunId = validateBacktestLink(candidate, request.backtestRunId());

        ScenarioSimulationRequest simulationRequest = new ScenarioSimulationRequest(
                candidate.instrumentId(), candidate.interval(), candidate.entryPrice(), candidate.stopPrice(),
                candidate.targetPrice(), request.historyFrom(), request.historyTo(), request.simulations(),
                request.horizonBars(), request.blockSize(), request.seed(), request.slippageBps(),
                request.roundTripCostBps());

        String fingerprint = fingerprint(candidate, simulationRequest);
        UUID scenarioRunId = evaluations.findSucceededScenario(candidateId, fingerprint).orElse(null);
        boolean reused = scenarioRunId != null;
        ResearchEvaluationRepository.ScenarioOutcome outcome;

        if (scenarioRunId == null) {
            scenarioRunId = UUID.randomUUID();
            scenarioRuns.create(new ScenarioRunCreate(
                    scenarioRunId, candidate.scanRunId(), candidate.id(), candidate.instrumentId(), candidate.exchange(),
                    candidate.tradingSymbol(), candidate.interval(), candidate.strategy(), candidate.strategyConfigVersion(),
                    candidate.entryPrice(), candidate.stopPrice(), candidate.targetPrice(), request.historyFrom(),
                    request.historyTo(), candidate.dataCutoffAt(), request.simulations(), request.horizonBars(),
                    request.blockSize(), request.seed(), request.slippageBps(), request.roundTripCostBps(), fingerprint,
                    HistoricalScenarioSimulator.METHODOLOGY, "AUTO_CLASSIFIED"));
            scenarioRuns.markRunning(scenarioRunId);
            try {
                ScenarioSimulationResult result = simulations.simulate(simulationRequest);
                scenarioRuns.markSucceeded(scenarioRunId, result);
                outcome = new ResearchEvaluationRepository.ScenarioOutcome(
                        result.historicalSamples(), result.targetFirstProbability(), result.stopFirstProbability(),
                        result.neitherProbability(), result.expectedR());
            } catch (RuntimeException e) {
                scenarioRuns.markFailed(scenarioRunId, "SCENARIO_SIMULATION_FAILED", e.getMessage());
                throw e;
            }
        } else {
            outcome = evaluations.requireScenarioOutcome(scenarioRunId);
        }

        Decision decision = classify(outcome);
        UUID evaluationId = UUID.randomUUID();
        evaluations.saveEvaluation(evaluationId, candidateId, backtestRunId, scenarioRunId,
                decision.label(), decision.reasonCodes());
        return new ResearchEvaluationResult(evaluationId, candidateId, backtestRunId, scenarioRunId,
                decision.label(), decision.reasonCodes(), reused);
    }

    /**
     * A historical candidate is only reproducible while its strategy implementation remains registered.
     * The persisted config-version row (when one was used) is verified independently by the repository.
     */
    private void resolveStrategy(ResearchCandidateProvenance candidate) {
        strategies.require(candidate.strategy())
                .orElseThrow(() -> new IllegalArgumentException(
                        "candidate strategy is no longer registered: " + candidate.strategy()));
    }

    /**
     * W04 safely reuses a caller-selected backtest only after provenance compatibility checks. It deliberately
     * does not auto-run the legacy SMA-specific backtest service for arbitrary scanner strategies.
     */
    private UUID validateBacktestLink(ResearchCandidateProvenance candidate, UUID requestedBacktestRunId) {
        if (requestedBacktestRunId == null) return null;
        BacktestRunSummary run = backtests.requireRun(requestedBacktestRunId);
        if (run.instrumentId() != candidate.instrumentId() || !run.interval().equals(candidate.interval())) {
            throw new IllegalArgumentException("backtest run does not match candidate instrument and interval");
        }
        if (candidate.catalogueVersion() != null && run.catalogueVersion() != null
                && !candidate.catalogueVersion().equals(run.catalogueVersion())) {
            throw new IllegalArgumentException("backtest run catalogue version does not match candidate provenance");
        }
        return requestedBacktestRunId;
    }

    private static void validateHistoryBoundary(ResearchCandidateProvenance candidate, ResearchEvaluationRequest request) {
        if (request.historyTo().isAfter(candidate.dataCutoffAt())) {
            throw new IllegalArgumentException("research history cannot extend beyond candidate data cutoff");
        }
        if (candidate.signalCandleTime().isAfter(candidate.dataCutoffAt())) {
            throw new IllegalArgumentException("candidate candle provenance is invalid");
        }
    }

    static String fingerprint(ResearchCandidateProvenance candidate, ScenarioSimulationRequest request) {
        String canonical = String.join("|",
                Long.toString(candidate.id()),
                candidate.candidateFingerprint() == null ? "" : candidate.candidateFingerprint(),
                Long.toString(request.instrumentId()), request.interval(),
                request.entryPrice().stripTrailingZeros().toPlainString(),
                request.stopPrice().stripTrailingZeros().toPlainString(),
                request.targetPrice().stripTrailingZeros().toPlainString(),
                request.historyFrom().toString(), request.historyTo().toString(),
                Integer.toString(request.simulations()), Integer.toString(request.horizonBars()),
                Integer.toString(request.blockSize()), Long.toString(request.seed()),
                Integer.toString(request.slippageBps()), Integer.toString(request.roundTripCostBps()),
                HistoricalScenarioSimulator.METHODOLOGY);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static Decision classify(ResearchEvaluationRepository.ScenarioOutcome outcome) {
        if (outcome.historicalSamples() <= 0 || outcome.neitherProbability() >= 0.80d) {
            return new Decision(ResearchDecisionLabel.INSUFFICIENT_DATA,
                    List.of("SCENARIO_EVIDENCE_INSUFFICIENT"));
        }
        if (outcome.expectedR() > 0d && outcome.targetFirstProbability() > outcome.stopFirstProbability()) {
            return new Decision(ResearchDecisionLabel.FAVORABLE,
                    List.of("POSITIVE_EXPECTANCY", "TARGET_PROBABILITY_EXCEEDS_STOP"));
        }
        if (outcome.expectedR() < 0d && outcome.stopFirstProbability() > outcome.targetFirstProbability()) {
            return new Decision(ResearchDecisionLabel.UNFAVORABLE,
                    List.of("NEGATIVE_EXPECTANCY", "STOP_PROBABILITY_EXCEEDS_TARGET"));
        }
        return new Decision(ResearchDecisionLabel.MIXED, List.of("MIXED_SCENARIO_EVIDENCE"));
    }

    private record Decision(ResearchDecisionLabel label, List<String> reasonCodes) {}
}
