package in.aitradingsystem.research.simulation;

import static org.assertj.core.api.Assertions.assertThat;

import in.aitradingsystem.support.AbstractPostgresIT;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class ScenarioSimulationRepositoryIT extends AbstractPostgresIT {
    private static final UUID SCAN_ID = UUID.fromString("71000000-0000-0000-0000-000000000001");

    @Autowired ScenarioRunRepository scenarioRuns;
    @Autowired ResearchEvaluationRepository evaluations;
    @Autowired JdbcTemplate jdbc;

    private long candidateId;

    @BeforeEach
    void seedCandidate() {
        jdbc.update("DELETE FROM research_evaluation");
        jdbc.update("DELETE FROM research_scenario_run");
        jdbc.update("DELETE FROM research_scan_candidate");
        jdbc.update("DELETE FROM research_scan_run");

        jdbc.update("""
                INSERT INTO research_scan_run
                    (id, interval, status, requested_limit, scanned_count, candidate_count, strategy_set, completed_at)
                VALUES (?, 'FIVE_MINUTE', 'SUCCEEDED', 1, 1, 1, 'OPENING_RANGE_BREAKOUT_V1', now())
                """, SCAN_ID);

        OffsetDateTime signal = OffsetDateTime.of(2026, 6, 30, 9, 55, 0, 0, ZoneOffset.UTC);
        candidateId = jdbc.queryForObject("""
                INSERT INTO research_scan_candidate
                    (scan_run_id, instrument_id, trading_symbol, exchange, interval, strategy, score,
                     entry_price, stop_price, target_price, signal_candle_time, reasons,
                     candidate_fingerprint, data_cutoff_at)
                VALUES (?, 424242, 'RELIANCE', 'NSE', 'FIVE_MINUTE', 'OPENING_RANGE_BREAKOUT_V1', 0.90,
                        2500, 2450, 2600, ?, 'fixture', ?, ?)
                RETURNING id
                """, Long.class, SCAN_ID, signal, "b".repeat(64), signal);
    }

    @Test
    void roundTripsPersistedScenarioAndEvaluationEvidence() {
        UUID scenarioId = UUID.fromString("72000000-0000-0000-0000-000000000002");
        String fingerprint = "c".repeat(64);
        scenarioRuns.create(new ScenarioRunCreate(
                scenarioId, SCAN_ID, candidateId, 424242L, "NSE", "RELIANCE", "FIVE_MINUTE",
                "OPENING_RANGE_BREAKOUT_V1", null,
                new BigDecimal("2500"), new BigDecimal("2450"), new BigDecimal("2600"),
                Instant.parse("2026-01-01T03:45:00Z"), Instant.parse("2026-06-30T09:55:00Z"),
                Instant.parse("2026-06-30T09:55:00Z"), 10_000, 20, 5, 778899L, 5, 10,
                fingerprint, HistoricalScenarioSimulator.METHODOLOGY, "HIGH_VOL"));
        scenarioRuns.markRunning(scenarioId);
        scenarioRuns.markSucceeded(scenarioId, new ScenarioSimulationResult(
                HistoricalScenarioSimulator.METHODOLOGY, 778899L, 10_000, 500, 420,
                BlockSamplingFallbackTier.SAME_VOLATILITY.name(), 20, 5,
                5_300, 3_100, 1_600, .53, .31, .16, .47, -1.05, .34, 2.10));

        UUID evaluationId = UUID.fromString("73000000-0000-0000-0000-000000000003");
        evaluations.saveEvaluation(evaluationId, candidateId, null, scenarioId,
                ResearchDecisionLabel.FAVORABLE,
                List.of("POSITIVE_EXPECTANCY", "TARGET_PROBABILITY_EXCEEDS_STOP"));

        ScenarioRunView scenario = scenarioRuns.requireView(scenarioId);
        assertThat(scenario.id()).isEqualTo(scenarioId);
        assertThat(scenario.candidateId()).isEqualTo(candidateId);
        assertThat(scenario.status()).isEqualTo(ScenarioRunStatus.SUCCEEDED);
        assertThat(scenario.requestFingerprint()).isEqualTo(fingerprint);
        assertThat(scenario.simulationCount()).isEqualTo(10_000);
        assertThat(scenario.eligibleBlockCount()).isEqualTo(420);
        assertThat(scenario.fallbackTier()).isEqualTo(BlockSamplingFallbackTier.SAME_VOLATILITY.name());
        assertThat(scenario.expectedR()).isEqualByComparingTo("0.47000000");
        assertThat(scenario.completedAt()).isNotNull();

        List<ResearchEvaluationView> history = evaluations.listEvaluations(candidateId);
        assertThat(history).hasSize(1);
        ResearchEvaluationView evaluation = history.getFirst();
        assertThat(evaluation.evaluationId()).isEqualTo(evaluationId);
        assertThat(evaluation.scenarioRunId()).isEqualTo(scenarioId);
        assertThat(evaluation.decisionLabel()).isEqualTo(ResearchDecisionLabel.FAVORABLE);
        assertThat(evaluation.reasonCodes()).containsExactly(
                "POSITIVE_EXPECTANCY", "TARGET_PROBABILITY_EXCEEDS_STOP");
    }

    @Test
    void failedScenarioReadPreservesBoundedFailureEvidence() {
        UUID scenarioId = UUID.fromString("74000000-0000-0000-0000-000000000004");
        scenarioRuns.create(new ScenarioRunCreate(
                scenarioId, SCAN_ID, candidateId, 424242L, "NSE", "RELIANCE", "FIVE_MINUTE",
                "OPENING_RANGE_BREAKOUT_V1", null,
                new BigDecimal("2500"), new BigDecimal("2450"), new BigDecimal("2600"),
                Instant.parse("2026-01-01T03:45:00Z"), Instant.parse("2026-06-30T09:55:00Z"),
                Instant.parse("2026-06-30T09:55:00Z"), 1_000, 20, 5, 11L, 5, 10,
                "d".repeat(64), HistoricalScenarioSimulator.METHODOLOGY, "NORMAL_VOL"));
        scenarioRuns.markFailed(scenarioId, "HISTORICAL_DATA_INELIGIBLE", "x".repeat(800));

        ScenarioRunView scenario = scenarioRuns.requireView(scenarioId);
        assertThat(scenario.status()).isEqualTo(ScenarioRunStatus.FAILED);
        assertThat(scenario.errorCode()).isEqualTo("HISTORICAL_DATA_INELIGIBLE");
        assertThat(scenario.errorMessage()).hasSize(500);
        assertThat(scenario.completedAt()).isNotNull();
    }
}
