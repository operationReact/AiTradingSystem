package in.aitradingsystem.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import in.aitradingsystem.support.AbstractPostgresIT;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

class StrategyApiIT extends AbstractPostgresIT {
    private static final String KEY_HEADER = "X-API-Key";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    private long instrumentId;

    @BeforeEach
    void resetAndSeedInstrument() {
        jdbc.update("DELETE FROM strategy_signal");
        jdbc.update("DELETE FROM market_candle");
        jdbc.update("DELETE FROM candle_backfill_checkpoint");
        jdbc.update("DELETE FROM candle_backfill_run");
        jdbc.update("UPDATE instrument_catalogue_state SET active_version = NULL, activated_at = NULL WHERE singleton_id = 1");
        jdbc.update("DELETE FROM instrument_master");
        jdbc.update("DELETE FROM instrument_import_run");

        UUID catalogue = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO instrument_import_run (id, status, source_url, completed_at, catalogue_version)
                VALUES (?, 'SUCCEEDED', 'strategy-test-fixture', now(), ?)
                """, catalogue, catalogue);
        jdbc.update("""
                INSERT INTO instrument_master
                    (exchange_segment, exchange, symbol_token, trading_symbol, instrument_name, instrument_type,
                     expiry, strike, lot_size, tick_size, active, catalogue_version)
                VALUES ('NSE_EQ', 'NSE', 'strategy-token', 'TEST-STRAT', 'Test equity', 'EQ',
                        NULL, NULL, 1, 0.05, TRUE, ?)
                """, catalogue);
        instrumentId = jdbc.queryForObject(
                "SELECT instrument_id FROM instrument_master WHERE trading_symbol = 'TEST-STRAT'", Long.class);
        jdbc.update("UPDATE instrument_catalogue_state SET active_version = ?, activated_at = now() WHERE singleton_id = 1",
                catalogue);
    }

    @Test
    void strategyEndpointsRequireOperatorKey() throws Exception {
        mvc.perform(post("/api/strategies/" + instrumentId + "/evaluate").param("interval", "FIVE_MINUTE"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/signals"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void insufficientHistoryIsExplicitAndDoesNotCreateSignal() throws Exception {
        mvc.perform(post("/api/strategies/" + instrumentId + "/evaluate")
                    .header(KEY_HEADER, API_KEY).param("interval", "FIVE_MINUTE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableCandles").value(0))
                .andExpect(jsonPath("$.requiredCandles").value(21))
                .andExpect(jsonPath("$.reasonCodes[0]").value("INSUFFICIENT_HISTORY"))
                .andExpect(jsonPath("$.signal").doesNotExist());

        Integer count = jdbc.queryForObject("SELECT count(*) FROM strategy_signal", Integer.class);
        assertThat(count).isZero();
    }

    @Test
    void evaluationPersistsOneIdempotentSignalAndListsIt() throws Exception {
        Instant latest = Instant.now().minusSeconds(30);
        for (int i = 0; i < 21; i++) {
            BigDecimal close = BigDecimal.valueOf(100 + i);
            Instant candleTime = latest.minusSeconds(60L * (20 - i));
            jdbc.update("""
                    INSERT INTO market_candle
                        (instrument_id, exchange, trading_symbol, symbol_token, interval, candle_time,
                         open_price, high_price, low_price, close_price, volume, source, fetched_at)
                    VALUES (?, 'NSE', 'TEST-STRAT', 'strategy-token', 'FIVE_MINUTE', ?, ?, ?, ?, ?, ?, 'TEST_FIXTURE', ?)
                    """,
                    instrumentId, ts(candleTime), close, close.add(BigDecimal.ONE), close.subtract(BigDecimal.ONE),
                    close, i == 20 ? 3_000L : 1_000L, ts(candleTime));
        }

        String first = mvc.perform(post("/api/strategies/" + instrumentId + "/evaluate")
                    .header(KEY_HEADER, API_KEY).param("interval", "FIVE_MINUTE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableCandles").value(21))
                .andExpect(jsonPath("$.signal.action").value("BUY"))
                .andExpect(jsonPath("$.signal.status").value("CANDIDATE_NOT_EXECUTABLE"))
                .andExpect(jsonPath("$.signal.stopLoss").isNumber())
                .andExpect(jsonPath("$.signal.target").isNumber())
                .andReturn().getResponse().getContentAsString();

        String second = mvc.perform(post("/api/strategies/" + instrumentId + "/evaluate")
                    .header(KEY_HEADER, API_KEY).param("interval", "FIVE_MINUTE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.signal.action").value("BUY"))
                .andReturn().getResponse().getContentAsString();

        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        assertThat(mapper.readTree(second).path("signal").path("id").asText())
                .isEqualTo(mapper.readTree(first).path("signal").path("id").asText());

        mvc.perform(get("/api/signals").header(KEY_HEADER, API_KEY).param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].symbol").value("TEST-STRAT"))
                .andExpect(jsonPath("$[0].action").value("BUY"));

        Integer count = jdbc.queryForObject("SELECT count(*) FROM strategy_signal", Integer.class);
        assertThat(count).isEqualTo(1);
    }

    private static OffsetDateTime ts(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
