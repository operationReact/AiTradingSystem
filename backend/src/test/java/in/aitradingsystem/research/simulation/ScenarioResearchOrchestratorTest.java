package in.aitradingsystem.research.simulation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.aitradingsystem.backtest.BacktestRepository;
import in.aitradingsystem.scanner.ScannerStrategy;
import in.aitradingsystem.scanner.StrategyRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class ScenarioResearchOrchestratorTest {
    @Mock ResearchEvaluationRepository evaluations;
    @Mock ScenarioRunRepository scenarioRuns;
    @Mock ScenarioSimulationService simulations;
    @Mock BacktestRepository backtests;
    @Mock StrategyRegistry strategies;
    @Mock ScannerStrategy strategy;

    private ScenarioResearchOrchestrator orchestrator;
    private ResearchCandidateProvenance candidate;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        orchestrator = new ScenarioResearchOrchestrator(evaluations, scenarioRuns, simulations, backtests, strategies);
        candidate = new ResearchCandidateProvenance(
                11L, UUID.fromString("11111111-1111-1111-1111-111111111111"), 42L, "RELIANCE", "NSE",
                "FIVE_MINUTE", "VOLUME_BREAKOUT_V1", 3,
                UUID.fromString("22222222-2222-2222-2222-222222222222"), "a".repeat(64),
                Instant.parse("2026-06-30T09:55:00Z"), Instant.parse("2026-06-30T10:00:00Z"),
                new BigDecimal("2500"), new BigDecimal("2450"), new BigDecimal("2600"));
        when(strategies.require(candidate.strategy())).thenReturn(Optional.of(strategy));
    }

    @Test
    void reusesSuccessfulScenarioAndPersistsResearchOnlyEvaluation() {
        UUID existingScenario = UUID.fromString("33333333-3333-3333-3333-333333333333");
        when(evaluations.requireCandidate(11L)).thenReturn(candidate);
        when(evaluations.findSucceededScenario(eq(11L), any())).thenReturn(Optional.of(existingScenario));
        when(evaluations.requireScenarioOutcome(existingScenario))
                .thenReturn(new ResearchEvaluationRepository.ScenarioOutcome(400, .56, .30, .14, .42));

        ResearchEvaluationResult result = orchestrator.evaluateCandidate(11L, request());

        assertThat(result.scenarioRunId()).isEqualTo(existingScenario);
        assertThat(result.scenarioReused()).isTrue();
        assertThat(result.decisionLabel()).isEqualTo(ResearchDecisionLabel.FAVORABLE);
        assertThat(result.reasonCodes()).containsExactly("POSITIVE_EXPECTANCY", "TARGET_PROBABILITY_EXCEEDS_STOP");
        verify(strategies).require(candidate.strategy());
        verify(evaluations).verifyImmutableProvenance(candidate);
        verify(simulations, never()).simulate(any());
        verify(scenarioRuns, never()).create(any());
        verify(evaluations).saveEvaluation(any(), eq(11L), eq(null), eq(existingScenario),
                eq(ResearchDecisionLabel.FAVORABLE), any());
    }

    @Test
    void rejectsHistoryAfterImmutableCandidateCutoff() {
        when(evaluations.requireCandidate(11L)).thenReturn(candidate);
        ResearchEvaluationRequest invalid = new ResearchEvaluationRequest(
                Instant.parse("2026-01-01T03:45:00Z"), Instant.parse("2026-06-30T10:05:00Z"),
                1000, 20, 5, 99L, 5, 10, null);

        assertThatThrownBy(() -> orchestrator.evaluateCandidate(11L, invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("data cutoff");
        verify(evaluations, never()).verifyImmutableProvenance(any());
        verify(evaluations, never()).findSucceededScenario(any(Long.class), any());
    }

    @Test
    void rejectsCandidateWhenStrategyIsNoLongerRegistered() {
        when(evaluations.requireCandidate(11L)).thenReturn(candidate);
        when(strategies.require(candidate.strategy())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orchestrator.evaluateCandidate(11L, request()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no longer registered");

        verify(evaluations, never()).verifyImmutableProvenance(any());
        verify(evaluations, never()).findSucceededScenario(any(Long.class), any());
    }

    @Test
    void immutableProvenanceFailureStopsScenarioReuseAndSimulation() {
        when(evaluations.requireCandidate(11L)).thenReturn(candidate);
        org.mockito.Mockito.doThrow(new IllegalArgumentException("candidate historical candle provenance is no longer available"))
                .when(evaluations).verifyImmutableProvenance(candidate);

        assertThatThrownBy(() -> orchestrator.evaluateCandidate(11L, request()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("historical candle provenance");

        verify(evaluations, never()).findSucceededScenario(any(Long.class), any());
        verify(simulations, never()).simulate(any());
    }

    @Test
    void requestFingerprintIsDeterministic() {
        ScenarioSimulationRequest simulation = new ScenarioSimulationRequest(
                candidate.instrumentId(), candidate.interval(), candidate.entryPrice(), candidate.stopPrice(),
                candidate.targetPrice(), request().historyFrom(), request().historyTo(), 1000, 20, 5, 99L, 5, 10);
        String first = ScenarioResearchOrchestrator.fingerprint(candidate, simulation);
        String second = ScenarioResearchOrchestrator.fingerprint(candidate, simulation);

        assertThat(first).hasSize(64).isEqualTo(second);
    }

    private ResearchEvaluationRequest request() {
        return new ResearchEvaluationRequest(
                Instant.parse("2026-01-01T03:45:00Z"), Instant.parse("2026-06-30T10:00:00Z"),
                1000, 20, 5, 99L, 5, 10, null);
    }
}
