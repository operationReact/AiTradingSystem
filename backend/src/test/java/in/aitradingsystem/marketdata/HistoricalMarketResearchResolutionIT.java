package in.aitradingsystem.marketdata;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import in.aitradingsystem.support.AbstractPostgresIT;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

class HistoricalMarketResearchResolutionIT extends AbstractPostgresIT {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private static final long HISTORICAL_INSTRUMENT_ID = 900_001L;
    private static final UUID CATALOGUE_VERSION = UUID.fromString("12345678-1234-1234-1234-123456789abc");
    private static final UUID BACKFILL_ID = UUID.fromString("abcdefab-cdef-abcd-efab-cdefabcdefab");

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM market_candle");
        jdbc.update("DELETE FROM candle_backfill_checkpoint");
        jdbc.update("DELETE FROM candle_backfill_run");
        jdbc.update("DELETE FROM instrument_cash_catalogue_history");
        jdbc.update("DELETE FROM instrument_master");
        jdbc.update("UPDATE instrument_catalogue_state SET active_version = NULL, activated_at = NULL WHERE singleton_id = 1");
        jdbc.update("DELETE FROM instrument_import_run");
    }

    @Test
    void marketHistoryExportsPurgedCashInstrumentUsingDurableCatalogueLineage() throws Exception {
        OffsetDateTime catalogueAvailableAt = OffsetDateTime.parse("2026-10-25T10:00:00Z");
        jdbc.update("""
                INSERT INTO instrument_import_run
                    (id, status, source_url, completed_at, accepted_count, rejected_count, catalogue_version)
                VALUES (?, 'SUCCEEDED', 'test-history', ?, 1, 0, ?)
                """, CATALOGUE_VERSION, catalogueAvailableAt, CATALOGUE_VERSION);
        jdbc.update("""
                INSERT INTO instrument_cash_catalogue_history
                    (catalogue_version, instrument_id, exchange_segment, exchange, symbol_token, trading_symbol,
                     instrument_name, instrument_type, expiry, strike, lot_size, tick_size, source_updated_at)
                VALUES (?, ?, 'NSE_EQ', 'NSE', '2885', 'RELIANCE-EQ',
                        'Reliance Industries', 'EQ', NULL, NULL, 1, 0.05, ?)
                """, CATALOGUE_VERSION, HISTORICAL_INSTRUMENT_ID, catalogueAvailableAt);
        jdbc.update("""
                INSERT INTO candle_backfill_run
                    (id, instrument_id, exchange, trading_symbol, symbol_token, catalogue_version, interval,
                     from_date, to_date, next_from_date, status, chunks_completed, fetched_rows, persisted_rows,
                     completed_at)
                VALUES (?, ?, 'NSE', 'RELIANCE-EQ', '2885', ?, 'ONE_DAY',
                        '2026-10-26', '2026-10-26', '2026-10-27', 'SUCCEEDED', 1, 1, 1,
                        '2026-10-27T08:00:00Z')
                """, BACKFILL_ID, HISTORICAL_INSTRUMENT_ID, CATALOGUE_VERSION);
        jdbc.update("""
                INSERT INTO market_candle
                    (instrument_id, exchange, trading_symbol, symbol_token, catalogue_version, interval,
                     candle_time, open_price, high_price, low_price, close_price, volume, source, fetched_at)
                VALUES (?, 'NSE', 'RELIANCE-EQ', '2885', ?, 'ONE_DAY',
                        '2026-10-26T03:45:00Z', 100, 105, 99, 104, 2500,
                        'ANGEL_ONE_SMARTAPI', '2026-10-27T08:00:00Z')
                """, HISTORICAL_INSTRUMENT_ID, CATALOGUE_VERSION);

        mvc.perform(get("/api/market-data/research/candles")
                        .header("X-API-Key", API_KEY)
                        .param("instrumentId", Long.toString(HISTORICAL_INSTRUMENT_ID))
                        .param("interval", "ONE_DAY")
                        .param("fromDate", "2026-10-26")
                        .param("toDate", "2026-10-26")
                        .param("limit", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contractVersion").value("market-history.v1"))
                .andExpect(jsonPath("$.instrumentId").value(HISTORICAL_INSTRUMENT_ID))
                .andExpect(jsonPath("$.exchange").value("NSE"))
                .andExpect(jsonPath("$.tradingSymbol").value("RELIANCE-EQ"))
                .andExpect(jsonPath("$.symbolToken").value("2885"))
                .andExpect(jsonPath("$.catalogueVersion").value(CATALOGUE_VERSION.toString()))
                .andExpect(jsonPath("$.providerSources[0]").value("ANGEL_ONE_SMARTAPI"))
                .andExpect(jsonPath("$.candles[0].close").value(104))
                .andExpect(jsonPath("$.candles[0].volume").value(2500));
    }

    @Test
    void missingInstrumentWithoutActiveOrDurableHistoryStillFailsClosed() throws Exception {
        mvc.perform(get("/api/market-data/research/candles")
                        .header("X-API-Key", API_KEY)
                        .param("instrumentId", "999999")
                        .param("interval", "ONE_DAY")
                        .param("fromDate", "2026-10-26")
                        .param("toDate", "2026-10-26"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INSTRUMENT_NOT_FOUND"));
    }
}
