package in.aitradingsystem.marketdata;

import static org.assertj.core.api.Assertions.assertThat;

import in.aitradingsystem.support.AbstractPostgresIT;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The runner-local PostgreSQL + Flyway context proves that the forward-only
 * V70/V71 migrations execute, their JSONB payloads round-trip, and upserts work.
 */
class MarketSnapshotMigrationIT extends AbstractPostgresIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired MarketOverviewSnapshotStore overviewStore;
    @Autowired MarketDetailSnapshotStore detailStore;

    private static final long TEST_INSTRUMENT = 900009999L;
    private static final Instant TICK = Instant.parse("2026-11-09T04:00:00Z");

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM market_detail_snapshot WHERE instrument_id = ?", TEST_INSTRUMENT);
        jdbc.update("DELETE FROM market_overview_snapshot WHERE universe_code = 'NIFTY50'");
    }

    @Test
    void flywaySeedsOfficial2026TradingHolidaysForSessionValidation() {
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM flyway_schema_history
            WHERE version = '75' AND success = TRUE
            """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM market_holiday
            WHERE trade_date = DATE '2026-11-10'
                AND market = 'NSE_CASH'
                AND source = 'NSE_TRADING_HOLIDAYS_2026'
            """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM market_holiday
            WHERE trade_date = DATE '2026-11-24' AND market = 'NSE_CASH'
            """, Integer.class)).isEqualTo(1);
    }

    @Test
    void flywayAppliesBothMigrationsAndRepositoriesRoundTripActualJsonb() {
        Integer installed = jdbc.queryForObject("""
                SELECT COUNT(*) FROM flyway_schema_history
                WHERE version IN ('70', '71') AND success = TRUE
                """, Integer.class);
        assertThat(installed).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT to_regclass('public.market_overview_snapshot')", String.class))
            .isEqualTo("market_overview_snapshot");
        assertThat(jdbc.queryForObject("SELECT to_regclass('public.market_detail_snapshot')", String.class))
            .isEqualTo("market_detail_snapshot");

        var overview = new MarketOverviewService.Overview(
            TICK, "ANGEL_ONE_SMARTAPI_FULL_BULK", "NIFTY50_CONSTITUENTS", TICK,
            new MarketOverviewService.Universe("NIFTY50", LocalDate.of(2026, 11, 9), 50, 50, 1),
            new MarketOverviewService.Breadth(1, 0, 0, 1),
            List.of(), List.of(), List.of(), List.of(), List.of(),
            List.of(new MarketOverviewService.IndexRow("NIFTY50", "Nifty 50",
                new BigDecimal("24000"), new BigDecimal("23900"), new BigDecimal("0.42"),
                null, null, null, 0L, TICK, "LIVE")),
            List.of(new MarketOverviewService.Idea("TEST-EQ", "Test equity",
                new BigDecimal("110"), new BigDecimal("10"), new BigDecimal("5"), "BREAKOUT", TICK))
        );
        overviewStore.save(overview);
        overviewStore.save(overview); // Same universe must update, not insert duplicate rows.
        var restored = overviewStore.load().orElseThrow();
        assertThat(restored.generatedAt()).isEqualTo(TICK);
        assertThat(restored.indices()).hasSize(1);
        assertThat(restored.indices().get(0).ltp()).isEqualByComparingTo("24000");
        assertThat(restored.ideas()).hasSize(1);
        assertThat(MarketOverviewService.asStored(restored).ideas()).isEmpty();
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM market_overview_snapshot WHERE universe_code='NIFTY50'",
            Integer.class)).isEqualTo(1);

        var detail = new CustomerMarketDataController.DetailQuote(
            TEST_INSTRUMENT, "NSE", "TEST-EQ", new BigDecimal("110"),
            new BigDecimal("105"), new BigDecimal("111"), new BigDecimal("104"),
            new BigDecimal("100"), 1200L, TICK, TICK, "ANGEL_ONE_SMARTAPI_FULL_APP",
            "ON_DEMAND", new BigDecimal("108"), new BigDecimal("120"),
            new BigDecimal("90"), 400L, 500L, List.of(), List.of()
        );
        detailStore.save(detail);
        detailStore.save(detail);
        var restoredDetail = detailStore.load(TEST_INSTRUMENT).orElseThrow();
        assertThat(restoredDetail.exchangeAt()).isEqualTo(TICK);
        assertThat(restoredDetail.ltp()).isEqualByComparingTo("110");
        assertThat(restoredDetail.asStored().status()).isEqualTo("LAST_CLOSE");
        assertThat(restoredDetail.asStored().source()).isEqualTo("STORED_LAST_DETAIL");
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM market_detail_snapshot WHERE instrument_id = ?",
            Integer.class, TEST_INSTRUMENT)).isEqualTo(1);
    }
}
