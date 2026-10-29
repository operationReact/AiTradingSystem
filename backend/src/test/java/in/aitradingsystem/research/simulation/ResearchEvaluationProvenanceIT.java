package in.aitradingsystem.research.simulation;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.aitradingsystem.support.AbstractPostgresIT;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class ResearchEvaluationProvenanceIT extends AbstractPostgresIT {
    private static final UUID SCAN_ID = UUID.fromString("61000000-0000-0000-0000-000000000001");
    private static final UUID CATALOGUE_VERSION = UUID.fromString("62000000-0000-0000-0000-000000000002");
    private static final UUID CONFIG_ID = UUID.fromString("63000000-0000-0000-0000-000000000003");
    private static final OffsetDateTime SIGNAL_TIME = OffsetDateTime.of(2026, 6, 30, 9, 55, 0, 0, ZoneOffset.UTC);

    @Autowired ResearchEvaluationRepository repository;
    @Autowired JdbcTemplate jdbc;

    private long candidateId;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM research_evaluation");
        jdbc.update("DELETE FROM research_scenario_run");
        jdbc.update("DELETE FROM research_scan_candidate");
        jdbc.update("DELETE FROM research_scan_run");
        jdbc.update("DELETE FROM market_candle WHERE instrument_id = 424242");
        jdbc.update("DELETE FROM strategy_config WHERE strategy_code = 'VOLUME_BREAKOUT_V1'");

        jdbc.update("""
                INSERT INTO research_scan_run
                    (id, interval, status, requested_limit, scanned_count, candidate_count, strategy_set, completed_at)
                VALUES (?, 'FIVE_MINUTE', 'SUCCEEDED', 1, 1, 1, 'VOLUME_BREAKOUT_V1', now())
                """, SCAN_ID);
        jdbc.update("""
                INSERT INTO strategy_config
                    (id, strategy_code, config_version, enabled, config_json, effective_from, created_by)
                VALUES (?, 'VOLUME_BREAKOUT_V1', 3, true, '{}'::jsonb, ?::timestamptz, 'test')
                """, CONFIG_ID, "2026-01-01T00:00:00Z");
        jdbc.update("""
                INSERT INTO market_candle
                    (instrument_id, exchange, trading_symbol, symbol_token, catalogue_version, interval,
                     candle_time, open_price, high_price, low_price, close_price, volume)
                VALUES (424242, 'NSE', 'RELIANCE', '424242', ?, 'FIVE_MINUTE', ?,
                        2495, 2510, 2490, 2500, 100000)
                """, CATALOGUE_VERSION, SIGNAL_TIME);

        candidateId = jdbc.queryForObject("""
                INSERT INTO research_scan_candidate
                    (scan_run_id, instrument_id, trading_symbol, exchange, interval, strategy, score,
                     entry_price, stop_price, target_price, signal_candle_time, reasons,
                     candidate_fingerprint, strategy_config_version, catalogue_version, data_cutoff_at)
                VALUES (?, 424242, 'RELIANCE', 'NSE', 'FIVE_MINUTE', 'VOLUME_BREAKOUT_V1', 0.90,
                        2500, 2450, 2600, ?, 'fixture', ?, 3, ?, ?)
                RETURNING id
                """, Long.class, SCAN_ID, SIGNAL_TIME, "a".repeat(64), CATALOGUE_VERSION, SIGNAL_TIME);
    }

    @Test
    void acceptsCandidateWhenScanCandleAndConfigProvenanceRemainAvailable() {
        ResearchCandidateProvenance candidate = repository.requireCandidate(candidateId);
        assertThatCode(() -> repository.verifyImmutableProvenance(candidate)).doesNotThrowAnyException();
    }

    @Test
    void rejectsCandidateWhenExactHistoricalSignalCandleHasDisappeared() {
        ResearchCandidateProvenance candidate = repository.requireCandidate(candidateId);
        jdbc.update("DELETE FROM market_candle WHERE instrument_id = 424242 AND candle_time = ?", SIGNAL_TIME);

        assertThatThrownBy(() -> repository.verifyImmutableProvenance(candidate))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("historical candle provenance");
    }

    @Test
    void rejectsCandidateWhenVersionedStrategyConfigHasDisappeared() {
        ResearchCandidateProvenance candidate = repository.requireCandidate(candidateId);
        jdbc.update("DELETE FROM strategy_config WHERE strategy_code = 'VOLUME_BREAKOUT_V1' AND config_version = 3");

        assertThatThrownBy(() -> repository.verifyImmutableProvenance(candidate))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("strategy configuration provenance");
    }

    @Test
    void rejectsCandidateFromNonCompletedScan() {
        ResearchCandidateProvenance candidate = repository.requireCandidate(candidateId);
        jdbc.update("UPDATE research_scan_run SET status = 'FAILED' WHERE id = ?", SCAN_ID);

        assertThatThrownBy(() -> repository.verifyImmutableProvenance(candidate))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scan provenance");
    }
}
