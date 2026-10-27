package in.aitradingsystem.backtest;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import in.aitradingsystem.support.AbstractPostgresIT;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

class PaperBacktestApiIT extends AbstractPostgresIT {
    private static final String KEY_HEADER = "X-API-Key";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private long instrumentId;

    @BeforeEach
    void resetAndSeed() {
        jdbc.update("DELETE FROM paper_backtest_validation");
        jdbc.update("DELETE FROM paper_backtest_trade");
        jdbc.update("DELETE FROM paper_backtest_run");
        jdbc.update("DELETE FROM market_candle");
        jdbc.update("DELETE FROM candle_backfill_checkpoint");
        jdbc.update("DELETE FROM candle_backfill_run");
        jdbc.update("UPDATE instrument_catalogue_state SET active_version = NULL, activated_at = NULL WHERE singleton_id = 1");
        jdbc.update("DELETE FROM instrument_master");
        jdbc.update("DELETE FROM instrument_import_run");

        UUID catalogue = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO instrument_import_run (id, status, source_url, completed_at, catalogue_version)
            VALUES (?, 'SUCCEEDED', 'test-fixture', now(), ?)
            """, catalogue, catalogue);
        jdbc.update("""
            INSERT INTO instrument_master
                (exchange_segment, exchange, symbol_token, trading_symbol, instrument_name, instrument_type,
                 expiry, strike, lot_size, tick_size, active, catalogue_version)
            VALUES ('NSE_EQ', 'NSE', 'backtest-token', 'BACKTEST-EQ', 'Backtest Equity', 'EQ',
                    NULL, NULL, 1, 0.05, TRUE, ?)
            """, catalogue);
        instrumentId = jdbc.queryForObject(
            "SELECT instrument_id FROM instrument_master WHERE trading_symbol = 'BACKTEST-EQ'", Long.class);
        jdbc.update("UPDATE instrument_catalogue_state SET active_version = ?, activated_at = now() WHERE singleton_id = 1",
            catalogue);
    }

    @Test
    void endpointsRequireOperatorApiKey() throws Exception {
        mvc.perform(get("/api/backtests")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/backtests").contentType(MediaType.APPLICATION_JSON)
            .content("""
              {"instrumentId":1,"interval":"ONE_DAY","fromDate":"2026-10-01","toDate":"2026-10-02"}
              """)).andExpect(status().isUnauthorized());
    }

    @Test
    void reportsInsufficientStoredHistoryWithoutCreatingRun() throws Exception {
        mvc.perform(post("/api/backtests")
                .header(KEY_HEADER, API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"instrumentId":%d,"interval":"ONE_DAY","fromDate":"2026-10-01","toDate":"2026-10-02"}
                    """.formatted(instrumentId)))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("INSUFFICIENT_HISTORY"));

        Integer runCount = jdbc.queryForObject("SELECT count(*) FROM paper_backtest_run", Integer.class);
        org.assertj.core.api.Assertions.assertThat(runCount).isZero();
    }

    @Test
    void rejectsFutureBacktestWindow() throws Exception {
        mvc.perform(post("/api/backtests")
                .header(KEY_HEADER, API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"instrumentId":%d,"interval":"ONE_DAY","fromDate":"2026-10-01","toDate":"2099-10-02"}
                    """.formatted(instrumentId)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("FUTURE_DATE_NOT_ALLOWED"));
    }

    @Test
    void outOfSampleValidationIsProtectedAndRejectsMissingHoldoutWithoutPersistingRuns() throws Exception {
        mvc.perform(post("/api/backtests/validation").contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"instrumentId":%d,"interval":"ONE_DAY","fromDate":"2026-10-01","toDate":"2026-10-20"}
                    """.formatted(instrumentId)))
            .andExpect(status().isUnauthorized());

        mvc.perform(post("/api/backtests/validation")
                .header(KEY_HEADER, API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"instrumentId":%d,"interval":"ONE_DAY","fromDate":"2026-10-01","toDate":"2026-10-20","outOfSamplePercent":30}
                    """.formatted(instrumentId)))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("INSUFFICIENT_TRAINING_HISTORY"));

        Integer validationCount = jdbc.queryForObject("SELECT count(*) FROM paper_backtest_validation", Integer.class);
        Integer runCount = jdbc.queryForObject("SELECT count(*) FROM paper_backtest_run", Integer.class);
        org.assertj.core.api.Assertions.assertThat(validationCount).isZero();
        org.assertj.core.api.Assertions.assertThat(runCount).isZero();
    }

    @Test
    void rejectsInvalidHoldoutPercentageBeforeLoadingCandles() throws Exception {
        mvc.perform(post("/api/backtests/validation")
                .header(KEY_HEADER, API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"instrumentId":%d,"interval":"ONE_DAY","fromDate":"2026-10-01","toDate":"2026-10-20","outOfSamplePercent":5}
                    """.formatted(instrumentId)))
            .andExpect(status().isBadRequest());
    }

    @Test
    void holdoutValidationPersistsTwoRunsAndAnAuditableComparison() throws Exception {
        // 40 chronological daily bars, enough for configured 20-period warm-up and a 30% holdout.
        UUID catalogue = jdbc.queryForObject(
            "SELECT active_version FROM instrument_catalogue_state WHERE singleton_id = 1", UUID.class);
        java.time.Instant start = java.time.LocalDate.parse("2026-01-01")
            .atStartOfDay(java.time.ZoneId.of("Asia/Kolkata")).toInstant();
        for (int i = 0; i < 40; i++) {
            java.math.BigDecimal close = java.math.BigDecimal.valueOf(100 + i);
            java.time.Instant at = start.plusSeconds(i * 86400L);
            jdbc.update("""
                INSERT INTO market_candle
                    (instrument_id, exchange, trading_symbol, symbol_token, catalogue_version, interval,
                     candle_time, open_price, high_price, low_price, close_price, volume, source, fetched_at)
                VALUES (?, 'NSE', 'BACKTEST-EQ', 'backtest-token', ?, 'ONE_DAY',
                        ?, ?, ?, ?, ?, ?, 'TEST_FIXTURE', ?)
                """, instrumentId, catalogue, java.time.OffsetDateTime.ofInstant(at, java.time.ZoneOffset.UTC),
                close.subtract(new java.math.BigDecimal("0.2")),
                close.add(new java.math.BigDecimal("0.8")),
                close.subtract(new java.math.BigDecimal("0.8")),
                close, 10000L + i * 50L,
                java.time.OffsetDateTime.ofInstant(at, java.time.ZoneOffset.UTC));
        }

        mvc.perform(post("/api/backtests/validation")
                .header(KEY_HEADER, API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"instrumentId":%d,"interval":"ONE_DAY","fromDate":"2026-01-01","toDate":"2026-02-09","outOfSamplePercent":30}
                    """.formatted(instrumentId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.validation.outOfSamplePercent").value(30))
            .andExpect(jsonPath("$.validation.trainingCandles").value(28))
            .andExpect(jsonPath("$.validation.outOfSampleCandles").value(12));

        Integer validations = jdbc.queryForObject("SELECT count(*) FROM paper_backtest_validation", Integer.class);
        Integer runs = jdbc.queryForObject("SELECT count(*) FROM paper_backtest_run", Integer.class);
        org.assertj.core.api.Assertions.assertThat(validations).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(runs).isEqualTo(2);
    }

}