package in.aitradingsystem.research.simulation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.aitradingsystem.support.AbstractPostgresIT;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

class ScenarioResearchOrchestratorIT extends AbstractPostgresIT {
    private static final UUID SCAN_ID = UUID.fromString("81000000-0000-0000-0000-000000000001");
    private static final UUID CATALOGUE_VERSION = UUID.fromString("82000000-0000-0000-0000-000000000002");
    private static final OffsetDateTime SIGNAL_TIME = OffsetDateTime.of(2026, 6, 30, 9, 55, 0, 0, ZoneOffset.UTC);

    @Autowired ScenarioResearchOrchestrator orchestrator;
    @Autowired ScenarioRunRepository scenarioRuns;
    @Autowired ResearchEvaluationRepository evaluations;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean ScenarioSimulationService simulations;

    private long candidateId;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM research_evaluation");
        jdbc.update("DELETE FROM research_scenario_run");
        jdbc.update("DELETE FROM research_scan_candidate");
        jdbc.update("DELETE FROM research_scan_run");
        jdbc.update("DELETE FROM market_candle WHERE instrument_id = 818181");

        jdbc.update("""
                INSERT INTO research_scan_run
                    (id, interval, status, requested_limit, scanned_count, candidate_count, strategy_set, completed_at)
                VALUES (?, 'FIVE_MINUTE', 'SUCCEEDED', 1, 1, 1, 'OPENING_RANGE_BREAKOUT_V1', now())
                """, SCAN_ID);
        jdbc.update("""
                INSERT INTO market_candle
                    (instrument_id, exchange, trading_symbol, symbol_token, catalogue_version, interval,
                     candle_time, open_price, high_price, low_price, close_price, volume)
                VALUES (818181, 'NSE', 'RELIANCE', '818181', ?, 'FIVE_MINUTE', ?,
                        2495, 2510, 2490, 2500, 100000)
                """, CATALOGUE_VERSION, SIGNAL_TIME);

        candidateId = jdbc.queryForObject("""
                INSERT INTO research_scan_candidate
                    (scan_run_id, instrument_id, trading_symbol, exchange, interval, strategy, score,
                     entry_price, stop_price, target_price, signal_candle_time, reasons,
                     candidate_fingerprint, catalogue_version, data_cutoff_at)
                VALUES (?, 818181, 'RELIANCE', 'NSE', 'FIVE_MINUTE', 'OPENING_RANGE_BREAKOUT_V1', 0.90,
                        2500, 2450, 2600, ?, 'fixture', ?, ?, ?)
                RETURNING id
                """, Long.class, SCAN_ID, SIGNAL_TIME, "e".repeat(64), CATALOGUE_VERSION, SIGNAL_TIME);

        when(simulations.classifyRegime(any())).thenReturn(new RegimeSnapshot(
                .031, .012, .024, MarketRegime.HIGH_VOL,
                1.18, LiquidityRegime.NORMAL, RegimeClassifier.METHODOLOGY));
        when(simulations.simulate(any())).thenReturn(new ScenarioSimulationResult(
                HistoricalScenarioSimulator.METHODOLOGY, 123456L, 1_000, 480, 350,
                BlockSamplingFallbackTier.EXACT_VOLATILITY_AND_VOLUME.name(), 20, 5,
                550, 300, 150, .55, .30, .15, .50, -1.02, .40, 2.05));
    }

    @Test
    void candidateToScenarioToEvaluationIsPersistedAndSameRequestReusesScenario() {
        ResearchEvaluationRequest request = new ResearchEvaluationRequest(
                Instant.parse("2026-01-01T03:45:00Z"), SIGNAL_TIME.toInstant(),
                1_000, 20, 5, 123456L, 5, 10, null);

        ResearchEvaluationResult first = orchestrator.evaluateCandidate(candidateId, request);
        ResearchEvaluationResult replay = orchestrator.evaluateCandidate(candidateId, request);

        assertThat(first.scenarioReused()).isFalse();
        assertThat(first.decisionLabel()).isEqualTo(ResearchDecisionLabel.FAVORABLE);
        assertThat(replay.scenarioReused()).isTrue();
        assertThat(replay.scenarioRunId()).isEqualTo(first.scenarioRunId());
        assertThat(replay.evaluationId()).isNotEqualTo(first.evaluationId());

        ScenarioRunView persisted = scenarioRuns.requireView(first.scenarioRunId());
        assertThat(persisted.status()).isEqualTo(ScenarioRunStatus.SUCCEEDED);
        assertThat(persisted.candidateId()).isEqualTo(candidateId);
        assertThat(persisted.requestFingerprint()).hasSize(64);
        assertThat(persisted.eligibleBlockCount()).isEqualTo(350);
        assertThat(persisted.regime()).isEqualTo(MarketRegime.HIGH_VOL.name());

        assertThat(evaluations.listEvaluations(candidateId)).hasSize(2)
                .allSatisfy(evaluation -> {
                    assertThat(evaluation.scenarioRunId()).isEqualTo(first.scenarioRunId());
                    assertThat(evaluation.decisionLabel()).isEqualTo(ResearchDecisionLabel.FAVORABLE);
                });
        verify(simulations, times(1)).classifyRegime(any());
        verify(simulations, times(1)).simulate(any());
    }
}
