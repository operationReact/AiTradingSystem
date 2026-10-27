package in.aitradingsystem.marketdata;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.aitradingsystem.provider.angelone.AngelOneHistoricalService;
import in.aitradingsystem.support.AbstractPostgresIT;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@TestPropertySource(properties = {
        "app.security.api-key=test-operator-key-0123456789",
        "app.market-data.max-chunks-per-run=1"
})
class MarketDataApiIT extends AbstractPostgresIT {
    private static final String KEY_HEADER = "X-API-Key";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @MockBean AngelOneHistoricalService provider;

    private long instrumentId;

    @BeforeEach
    void resetAndSeed() throws Exception {
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
                VALUES ('NSE_EQ', 'NSE', 'test-token', 'TEST-EQ', 'Test Equity', 'EQ',
                        NULL, NULL, 1, 0.05, TRUE, ?)
                """, catalogue);
        instrumentId = jdbc.queryForObject("SELECT instrument_id FROM instrument_master WHERE trading_symbol = 'TEST-EQ'",
                Long.class);
        jdbc.update("UPDATE instrument_catalogue_state SET active_version = ?, activated_at = now() WHERE singleton_id = 1",
                catalogue);
    }

    @Test
    void protectedEndpointsRequireOperatorKey() throws Exception {
        mvc.perform(get("/api/market-data/backfills")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/market-data/candles")
                .param("instrumentId", Long.toString(instrumentId))
                .param("interval", "ONE_DAY").param("fromDate", "2026-10-26").param("toDate", "2026-10-26"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void backfillPersistsCandlesAndRepeatedFetchUpsertsInsteadOfDuplicating() throws Exception {
        var payload = mapper.readTree("""
                [["2026-10-26T09:15:00+05:30",100,105,99,104,2500]]
                """);
        when(provider.fetch(eq("NSE"), eq("test-token"), eq("ONE_DAY"), anyString(), anyString()))
            .thenReturn(payload);

        String request = """
                {"instrumentId":%d,"interval":"ONE_DAY","fromDate":"2026-10-26","toDate":"2026-10-26"}
                """.formatted(instrumentId);
        mvc.perform(post("/api/market-data/backfills").header(KEY_HEADER, AbstractPostgresIT.API_KEY)
                .contentType(MediaType.APPLICATION_JSON).content(request))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUCCEEDED"))
            .andExpect(jsonPath("$.chunksCompleted").value(1))
            .andExpect(jsonPath("$.persistedRows").value(1));

        mvc.perform(get("/api/market-data/candles").header(KEY_HEADER, AbstractPostgresIT.API_KEY)
                .param("instrumentId", Long.toString(instrumentId))
                .param("interval", "ONE_DAY").param("fromDate", "2026-10-26").param("toDate", "2026-10-26"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalItems").value(1))
            .andExpect(jsonPath("$.items[0].tradingSymbol").value("TEST-EQ"))
            .andExpect(jsonPath("$.items[0].close").value(104));

        mvc.perform(post("/api/market-data/backfills").header(KEY_HEADER, AbstractPostgresIT.API_KEY)
                .contentType(MediaType.APPLICATION_JSON).content(request))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUCCEEDED"));
        Integer count = jdbc.queryForObject("SELECT count(*) FROM market_candle WHERE instrument_id = ?", Integer.class,
                instrumentId);
        org.assertj.core.api.Assertions.assertThat(count).isEqualTo(1);
        verify(provider, org.mockito.Mockito.times(2))
            .fetch(eq("NSE"), eq("test-token"), eq("ONE_DAY"), anyString(), anyString());
    }

    @Test
    void longRangeUsesBoundedChunksAndCanResumeFromItsCheckpoint() throws Exception {
        var first = mapper.readTree("""
                [["2026-07-01T09:15:00+05:30",100,101,99,100,10]]
                """);
        var second = mapper.readTree("""
                [["2026-07-31T09:15:00+05:30",101,102,100,101,11]]
                """);
        var third = mapper.readTree("""
                [["2026-08-30T09:15:00+05:30",102,103,101,102,12]]
                """);
        var fourth = mapper.readTree("""
                [["2026-09-29T09:15:00+05:30",103,104,102,103,13]]
                """);
        when(provider.fetch(eq("NSE"), eq("test-token"), eq("ONE_MINUTE"), anyString(), anyString()))
            .thenReturn(first).thenReturn(second).thenReturn(third).thenReturn(fourth);
        String request = """
                {"instrumentId":%d,"interval":"ONE_MINUTE","fromDate":"2026-07-01","toDate":"2026-10-26"}
                """.formatted(instrumentId);

        String body = mvc.perform(post("/api/market-data/backfills").header(KEY_HEADER, AbstractPostgresIT.API_KEY)
                .contentType(MediaType.APPLICATION_JSON).content(request))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PARTIAL"))
            .andExpect(jsonPath("$.chunksCompleted").value(1)).andReturn().getResponse().getContentAsString();
        String runId = mapper.readTree(body).get("id").asText();

        for (int i = 0; i < 3; i++) {
            var response = mvc.perform(post("/api/market-data/backfills/" + runId + "/resume")
                    .header(KEY_HEADER, AbstractPostgresIT.API_KEY))
                .andExpect(status().isOk());
            if (i < 2) response.andExpect(jsonPath("$.status").value("PARTIAL"));
            else response.andExpect(jsonPath("$.status").value("SUCCEEDED"));
        }
        CandleBackfillRun run = mapper.readValue(
                mvc.perform(get("/api/market-data/backfills/" + runId).header(KEY_HEADER, AbstractPostgresIT.API_KEY))
                    .andReturn().getResponse().getContentAsString(), CandleBackfillRun.class);
        org.assertj.core.api.Assertions.assertThat(run.chunksCompleted()).isEqualTo(4);
        org.assertj.core.api.Assertions.assertThat(run.persistedRows()).isEqualTo(4);
        Integer checkpoints = jdbc.queryForObject("SELECT count(*) FROM candle_backfill_checkpoint WHERE run_id = ?",
                Integer.class, UUID.fromString(runId));
        org.assertj.core.api.Assertions.assertThat(checkpoints).isEqualTo(4);
    }

    @Test
    void rejectsReversedDateRangeBeforeCallingProvider() throws Exception {
        String request = """
                {"instrumentId":%d,"interval":"ONE_DAY","fromDate":"2026-10-27","toDate":"2026-10-26"}
                """.formatted(instrumentId);
        mvc.perform(post("/api/market-data/backfills").header(KEY_HEADER, AbstractPostgresIT.API_KEY)
                .contentType(MediaType.APPLICATION_JSON).content(request))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_DATE_RANGE"));
        org.mockito.Mockito.verifyNoInteractions(provider);
    }
}
