package in.aitradingsystem.research.simulation;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ScenarioRunRepository {
    private final JdbcTemplate jdbc;

    public ScenarioRunRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void create(ScenarioRunCreate run) {
        jdbc.update("""
                INSERT INTO research_scenario_run (
                    id, scan_run_id, candidate_id, instrument_id, exchange, trading_symbol, interval,
                    strategy, strategy_config_version, entry_price, stop_price, target_price,
                    history_from, history_to, data_cutoff_at, simulation_count, horizon_bars, block_size,
                    seed, slippage_bps, round_trip_cost_bps, request_fingerprint, methodology, regime, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'CREATED')
                """,
                run.id(), run.scanRunId(), run.candidateId(), run.instrumentId(), run.exchange(), run.tradingSymbol(),
                run.interval(), run.strategy(), run.strategyConfigVersion(), run.entryPrice(), run.stopPrice(),
                run.targetPrice(), java.sql.Timestamp.from(run.historyFrom()), java.sql.Timestamp.from(run.historyTo()),
                java.sql.Timestamp.from(run.dataCutoffAt()), run.simulationCount(), run.horizonBars(), run.blockSize(),
                run.seed(), run.slippageBps(), run.roundTripCostBps(), run.requestFingerprint(), run.methodology(), run.regime());
    }

    public void markRunning(UUID id) {
        requireTransition(id, jdbc.update("""
                UPDATE research_scenario_run
                SET status = 'RUNNING', started_at = now(), error_code = NULL, error_message = NULL
                WHERE id = ? AND status = 'CREATED'
                """, id), "CREATED", "RUNNING");
    }

    public void markSucceeded(UUID id, ScenarioSimulationResult result) {
        requireTransition(id, jdbc.update("""
                UPDATE research_scenario_run
                SET status = 'SUCCEEDED', historical_samples = ?, eligible_block_count = ?, fallback_tier = ?,
                    target_first_count = ?, stop_first_count = ?, neither_count = ?,
                    target_first_probability = ?, stop_first_probability = ?, neither_probability = ?,
                    expected_r = ?, p05_r = ?, p50_r = ?, p95_r = ?, completed_at = now(),
                    error_code = NULL, error_message = NULL
                WHERE id = ? AND status = 'RUNNING'
                """,
                result.historicalSamples(), result.eligibleBlockCount(), result.fallbackTier(), result.targetFirstCount(),
                result.stopFirstCount(), result.neitherCount(), result.targetFirstProbability(),
                result.stopFirstProbability(), result.neitherProbability(), result.expectedR(), result.p05R(),
                result.p50R(), result.p95R(), id), "RUNNING", "SUCCEEDED");
    }

    public void markFailed(UUID id, String errorCode, String errorMessage) {
        String code = truncate(errorCode, 80);
        String message = truncate(errorMessage, 500);
        requireTransition(id, jdbc.update("""
                UPDATE research_scenario_run
                SET status = 'FAILED', error_code = ?, error_message = ?, completed_at = now()
                WHERE id = ? AND status IN ('CREATED','RUNNING')
                """, code, message, id), "CREATED/RUNNING", "FAILED");
    }

    public ScenarioRunStatus status(UUID id) {
        String value = jdbc.query("SELECT status FROM research_scenario_run WHERE id = ?", rs -> rs.next() ? rs.getString(1) : null, id);
        if (value == null) throw new IllegalArgumentException("scenario run not found: " + id);
        return ScenarioRunStatus.valueOf(value);
    }

    public ScenarioRunView requireView(UUID id) {
        if (id == null) throw new IllegalArgumentException("scenario run id is required");
        return jdbc.query("""
                SELECT id, scan_run_id, candidate_id, instrument_id, exchange, trading_symbol, interval,
                       strategy, strategy_config_version, entry_price, stop_price, target_price,
                       history_from, history_to, data_cutoff_at, simulation_count, horizon_bars, block_size,
                       seed, slippage_bps, round_trip_cost_bps, request_fingerprint, methodology, regime,
                       historical_samples, eligible_block_count, fallback_tier,
                       target_first_count, stop_first_count, neither_count,
                       target_first_probability, stop_first_probability, neither_probability,
                       expected_r, p05_r, p50_r, p95_r, status, error_code, error_message,
                       started_at, completed_at, created_at
                  FROM research_scenario_run
                 WHERE id = ?
                """, (rs, row) -> new ScenarioRunView(
                rs.getObject("id", UUID.class),
                rs.getObject("scan_run_id", UUID.class),
                rs.getObject("candidate_id", Long.class),
                rs.getLong("instrument_id"),
                rs.getString("exchange"),
                rs.getString("trading_symbol"),
                rs.getString("interval"),
                rs.getString("strategy"),
                rs.getObject("strategy_config_version", Integer.class),
                rs.getBigDecimal("entry_price"),
                rs.getBigDecimal("stop_price"),
                rs.getBigDecimal("target_price"),
                rs.getObject("history_from", OffsetDateTime.class).toInstant(),
                rs.getObject("history_to", OffsetDateTime.class).toInstant(),
                rs.getObject("data_cutoff_at", OffsetDateTime.class).toInstant(),
                rs.getInt("simulation_count"),
                rs.getInt("horizon_bars"),
                rs.getInt("block_size"),
                rs.getLong("seed"),
                rs.getInt("slippage_bps"),
                rs.getInt("round_trip_cost_bps"),
                rs.getString("request_fingerprint"),
                rs.getString("methodology"),
                rs.getString("regime"),
                rs.getInt("historical_samples"),
                rs.getInt("eligible_block_count"),
                rs.getString("fallback_tier"),
                rs.getInt("target_first_count"),
                rs.getInt("stop_first_count"),
                rs.getInt("neither_count"),
                rs.getBigDecimal("target_first_probability"),
                rs.getBigDecimal("stop_first_probability"),
                rs.getBigDecimal("neither_probability"),
                rs.getBigDecimal("expected_r"),
                rs.getBigDecimal("p05_r"),
                rs.getBigDecimal("p50_r"),
                rs.getBigDecimal("p95_r"),
                ScenarioRunStatus.valueOf(rs.getString("status")),
                rs.getString("error_code"),
                rs.getString("error_message"),
                toInstant(rs.getObject("started_at", OffsetDateTime.class)),
                toInstant(rs.getObject("completed_at", OffsetDateTime.class)),
                rs.getObject("created_at", OffsetDateTime.class).toInstant()), id).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("scenario run not found: " + id));
    }

    private static java.time.Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static void requireTransition(UUID id, int rows, String from, String to) {
        if (rows != 1) throw new IllegalStateException("scenario run " + id + " cannot transition from " + from + " to " + to);
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
