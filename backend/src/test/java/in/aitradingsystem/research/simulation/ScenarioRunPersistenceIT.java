package in.aitradingsystem.research.simulation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.aitradingsystem.support.AbstractPostgresIT;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class ScenarioRunPersistenceIT extends AbstractPostgresIT {

    @Autowired ScenarioRunRepository repository;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM research_scenario_run");
    }

    @Test
    void persistsImmutableProvenanceAndLifecycleResults() {
        UUID id = UUID.randomUUID();
        ScenarioRunCreate create = new ScenarioRunCreate(
                id, null, null, 42L, "NSE", "RELIANCE", "FIVE_MINUTE",
                "SMA_TREND_VOLUME", 3,
                new BigDecimal("2500.000000"), new BigDecimal("2450.000000"), new BigDecimal("2600.000000"),
                Instant.parse("2026-01-01T03:45:00Z"), Instant.parse("2026-06-30T10:00:00Z"),
                Instant.parse("2026-06-30T10:00:00Z"), 1000, 20, 5, 123456789L, 5, 10,
                HistoricalScenarioSimulator.METHODOLOGY, "UNCLASSIFIED");

        repository.create(create);
        assertThat(repository.status(id)).isEqualTo(ScenarioRunStatus.CREATED);
        repository.markRunning(id);
        assertThat(repository.status(id)).isEqualTo(ScenarioRunStatus.RUNNING);

        ScenarioSimulationResult result = new ScenarioSimulationResult(
                HistoricalScenarioSimulator.METHODOLOGY, 123456789L, 1000, 400, 396, 20, 5,
                520, 310, 170, .52, .31, .17, .42, -1.1, .30, 2.0);
        repository.markSucceeded(id, result);

        assertThat(repository.status(id)).isEqualTo(ScenarioRunStatus.SUCCEEDED);
        var row = jdbc.queryForMap("SELECT * FROM research_scenario_run WHERE id = ?", id);
        assertThat(row.get("instrument_id")).isEqualTo(42L);
        assertThat(row.get("seed")).isEqualTo(123456789L);
        assertThat(row.get("eligible_block_count")).isEqualTo(396);
        assertThat(row.get("target_first_count")).isEqualTo(520);
        assertThat(row.get("error_code")).isNull();
        assertThat(row.get("completed_at")).isNotNull();

        assertThatThrownBy(() -> repository.markRunning(id))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void failureEvidenceIsBoundedAndTerminal() {
        UUID id = UUID.randomUUID();
        repository.create(new ScenarioRunCreate(
                id, null, null, 7L, "NSE", "INFY", "FIFTEEN_MINUTE", null, null,
                new BigDecimal("1500"), new BigDecimal("1470"), new BigDecimal("1560"),
                Instant.parse("2026-01-01T03:45:00Z"), Instant.parse("2026-06-30T10:00:00Z"),
                Instant.parse("2026-06-30T10:00:00Z"), 500, 12, 3, 99L, 0, 0,
                HistoricalScenarioSimulator.METHODOLOGY, "UNCLASSIFIED"));

        repository.markFailed(id, "HISTORICAL_DATA_INELIGIBLE", "x".repeat(800));
        assertThat(repository.status(id)).isEqualTo(ScenarioRunStatus.FAILED);
        String message = jdbc.queryForObject("SELECT error_message FROM research_scenario_run WHERE id = ?", String.class, id);
        assertThat(message).hasSize(500);
        assertThatThrownBy(() -> repository.markRunning(id)).isInstanceOf(IllegalStateException.class);
    }
}
