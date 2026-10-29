package in.aitradingsystem.research.simulation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ResearchEvaluationRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public ResearchEvaluationRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public ResearchCandidateProvenance requireCandidate(long candidateId) {
        if (candidateId < 1) throw new IllegalArgumentException("candidateId must be positive");
        return jdbc.query("""
                SELECT id, scan_run_id, instrument_id, trading_symbol, exchange, interval, strategy,
                       strategy_config_version, catalogue_version, candidate_fingerprint,
                       signal_candle_time, data_cutoff_at, entry_price, stop_price, target_price
                  FROM research_scan_candidate
                 WHERE id = ?
                """, (rs, row) -> new ResearchCandidateProvenance(
                rs.getLong("id"), rs.getObject("scan_run_id", UUID.class), rs.getLong("instrument_id"),
                rs.getString("trading_symbol"), rs.getString("exchange"), rs.getString("interval"),
                rs.getString("strategy"), (Integer) rs.getObject("strategy_config_version"),
                rs.getObject("catalogue_version", UUID.class), rs.getString("candidate_fingerprint"),
                rs.getObject("signal_candle_time", OffsetDateTime.class).toInstant(),
                rs.getObject("data_cutoff_at", OffsetDateTime.class).toInstant(),
                rs.getBigDecimal("entry_price"), rs.getBigDecimal("stop_price"), rs.getBigDecimal("target_price")),
                candidateId).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("research candidate not found: " + candidateId));
    }

    /**
     * Fail closed when candidate provenance can no longer be reproduced. A research evaluation may only
     * consume candidates produced by a completed/partial scan and whose exact signal candle is still present.
     */
    public void verifyImmutableProvenance(ResearchCandidateProvenance candidate) {
        if (candidate == null) throw new IllegalArgumentException("candidate is required");

        Integer scanCount = jdbc.queryForObject("""
                SELECT count(*)
                  FROM research_scan_run
                 WHERE id = ?
                   AND status IN ('SUCCEEDED','PARTIAL')
                """, Integer.class, candidate.scanRunId());
        if (scanCount == null || scanCount != 1) {
            throw new IllegalArgumentException("candidate scan provenance is not eligible for research evaluation");
        }

        Integer candleCount;
        if (candidate.catalogueVersion() == null) {
            candleCount = jdbc.queryForObject("""
                    SELECT count(*)
                      FROM market_candle
                     WHERE instrument_id = ?
                       AND exchange = ?
                       AND trading_symbol = ?
                       AND interval = ?
                       AND candle_time = ?
                    """, Integer.class, candidate.instrumentId(), candidate.exchange(), candidate.tradingSymbol(),
                    candidate.interval(), OffsetDateTime.ofInstant(candidate.signalCandleTime(), java.time.ZoneOffset.UTC));
        } else {
            candleCount = jdbc.queryForObject("""
                    SELECT count(*)
                      FROM market_candle
                     WHERE instrument_id = ?
                       AND exchange = ?
                       AND trading_symbol = ?
                       AND interval = ?
                       AND candle_time = ?
                       AND catalogue_version = ?
                    """, Integer.class, candidate.instrumentId(), candidate.exchange(), candidate.tradingSymbol(),
                    candidate.interval(), OffsetDateTime.ofInstant(candidate.signalCandleTime(), java.time.ZoneOffset.UTC),
                    candidate.catalogueVersion());
        }
        if (candleCount == null || candleCount != 1) {
            throw new IllegalArgumentException("candidate historical candle provenance is no longer available");
        }

        if (candidate.strategyConfigVersion() != null) {
            Integer configCount = jdbc.queryForObject("""
                    SELECT count(*)
                      FROM strategy_config
                     WHERE strategy_code = ?
                       AND config_version = ?
                    """, Integer.class, candidate.strategy(), candidate.strategyConfigVersion());
            if (configCount == null || configCount != 1) {
                throw new IllegalArgumentException("candidate strategy configuration provenance is no longer available");
            }
        }
    }

    public Optional<UUID> findSucceededScenario(long candidateId, String requestFingerprint) {
        return jdbc.query("""
                SELECT id FROM research_scenario_run
                 WHERE candidate_id = ? AND request_fingerprint = ? AND status = 'SUCCEEDED'
                 ORDER BY completed_at DESC NULLS LAST, created_at DESC
                 LIMIT 1
                """, (rs, row) -> rs.getObject("id", UUID.class), candidateId, requestFingerprint)
                .stream().findFirst();
    }

    public ScenarioOutcome requireScenarioOutcome(UUID scenarioRunId) {
        return jdbc.query("""
                SELECT historical_samples, target_first_probability, stop_first_probability,
                       neither_probability, expected_r
                  FROM research_scenario_run
                 WHERE id = ? AND status = 'SUCCEEDED'
                """, (rs, row) -> new ScenarioOutcome(
                rs.getInt("historical_samples"), rs.getDouble("target_first_probability"),
                rs.getDouble("stop_first_probability"), rs.getDouble("neither_probability"),
                rs.getDouble("expected_r")), scenarioRunId).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("successful scenario run not found: " + scenarioRunId));
    }

    public void saveEvaluation(UUID id, long candidateId, UUID backtestRunId, UUID scenarioRunId,
                               ResearchDecisionLabel label, List<String> reasonCodes) {
        jdbc.update("""
                INSERT INTO research_evaluation
                    (id, candidate_id, backtest_run_id, scenario_run_id, decision_label, reason_codes)
                VALUES (?, ?, ?, ?, ?, ?::jsonb)
                """, id, candidateId, backtestRunId, scenarioRunId, label.name(), json(reasonCodes));
    }

    private String json(List<String> reasonCodes) {
        try {
            return objectMapper.writeValueAsString(reasonCodes == null ? List.of() : reasonCodes);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize research reason codes", e);
        }
    }

    public record ScenarioOutcome(
            int historicalSamples,
            double targetFirstProbability,
            double stopFirstProbability,
            double neitherProbability,
            double expectedR) {}
}
