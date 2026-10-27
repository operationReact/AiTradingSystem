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
}