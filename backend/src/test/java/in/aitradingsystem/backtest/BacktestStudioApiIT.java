package in.aitradingsystem.backtest;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.aitradingsystem.support.AbstractPostgresIT;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

class BacktestStudioApiIT extends AbstractPostgresIT {
    private static final String KEY_HEADER = "X-API-Key";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;

    private long instrumentId;

    @BeforeEach
    void resetAndSeed() {
        jdbc.update("DELETE FROM backtest_walk_forward_fold");
        jdbc.update("DELETE FROM backtest_walk_forward_run");
        jdbc.update("DELETE FROM paper_backtest_equity_point");
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
            VALUES ('NSE_EQ', 'NSE', 'api-token', 'API-EQ', 'API Equity', 'EQ',
                    NULL, NULL, 1, 0.05, TRUE, ?)
            """, catalogue);
        instrumentId = jdbc.queryForObject(
                "SELECT instrument_id FROM instrument_master WHERE trading_symbol = 'API-EQ'", Long.class);
        jdbc.update("UPDATE instrument_catalogue_state SET active_version = ?, activated_at = now() WHERE singleton_id = 1",
                catalogue);

        Instant start = LocalDate.parse("2026-01-01").atStartOfDay(ZoneId.of("Asia/Kolkata")).toInstant();
        for (int i = 0; i < 40; i++) {
            BigDecimal close = BigDecimal.valueOf(100 + i);
            Instant at = start.plusSeconds(i * 86400L);
            OffsetDateTime timestamp = OffsetDateTime.ofInstant(at, ZoneOffset.UTC);
            jdbc.update("""
                INSERT INTO market_candle
                    (instrument_id, exchange, trading_symbol, symbol_token, catalogue_version, interval,
                     candle_time, open_price, high_price, low_price, close_price, volume, source, fetched_at)
                VALUES (?, 'NSE', 'API-EQ', 'api-token', ?, 'ONE_DAY',
                        ?, ?, ?, ?, ?, ?, 'TEST_FIXTURE', ?)
                """, instrumentId, catalogue, timestamp,
                    close.subtract(new BigDecimal("0.2")), close.add(new BigDecimal("0.8")),
                    close.subtract(new BigDecimal("0.8")), close, 10000L + i * 50L, timestamp);
        }
    }

    @Test
    void exposesFilteredHistoryEquityAndCsvWithoutBreakingLegacyList() throws Exception {
        String response = mvc.perform(post("/api/backtests")
                        .header(KEY_HEADER, API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"instrumentId":%d,"interval":"ONE_DAY","fromDate":"2026-01-01","toDate":"2026-02-09"}
                            """.formatted(instrumentId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode run = objectMapper.readTree(response).path("run");
        String runId = run.path("id").asText();

        mvc.perform(get("/api/backtests")
                        .header(KEY_HEADER, API_KEY)
                        .param("instrumentId", Long.toString(instrumentId))
                        .param("strategy", "SMA_TREND_VOLUME_V1")
                        .param("page", "0").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(runId))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.page").value(0));

        mvc.perform(get("/api/backtests").header(KEY_HEADER, API_KEY).param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(runId));

        mvc.perform(get("/api/backtests/{id}/equity", runId).header(KEY_HEADER, API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].pointTime").exists());

        mvc.perform(get("/api/backtests/{id}/export.csv", runId).header(KEY_HEADER, API_KEY))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=backtest-" + runId + ".csv"))
                .andExpect(content().string(org.hamcrest.Matchers.startsWith("runId,tradeNumber,")));
    }

    @Test
    void exposesWalkForwardRoutesAndValidatesWindowContract() throws Exception {
        mvc.perform(get("/api/backtests/walk-forward/{id}", UUID.randomUUID()).header(KEY_HEADER, API_KEY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WALK_FORWARD_NOT_FOUND"));

        mvc.perform(post("/api/backtests/walk-forward")
                        .header(KEY_HEADER, API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"instrumentId":%d,"interval":"ONE_DAY","fromDate":"2026-01-01","toDate":"2026-02-09",
                             "trainingWindowDays":0,"validationWindowDays":10,"stepDays":10}
                            """.formatted(instrumentId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_WALK_FORWARD_WINDOWS"));
    }

    @Test
    void rejectsInvalidPagination() throws Exception {
        mvc.perform(get("/api/backtests").header(KEY_HEADER, API_KEY).param("page", "-1").param("size", "20"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAGE"));
        mvc.perform(get("/api/backtests").header(KEY_HEADER, API_KEY).param("page", "0").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAGE_SIZE"));
    }
}
