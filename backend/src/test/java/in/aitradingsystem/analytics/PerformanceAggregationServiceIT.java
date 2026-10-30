package in.aitradingsystem.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.aitradingsystem.support.AbstractPostgresIT;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class PerformanceAggregationServiceIT extends AbstractPostgresIT {
    private static final LocalDate DAY = LocalDate.of(2001, 1, 8);
    private static final OffsetDateTime START = DAY.atStartOfDay(in.aitradingsystem.marketdata.IndianCashMarketCalendar.ZONE)
        .toOffsetDateTime();
    @Autowired JdbcClient jdbc;
    @Autowired PerformanceAggregationService service;
    private final List<UUID> orders = new ArrayList<>();
    private long capitalId;
    private UUID catalogue;
    private long instrument;

    @BeforeEach
    void seed() {
        capitalId = jdbc.sql("""
            INSERT INTO paper_cash_entry(entry_type,amount,balance_after,created_at)
            VALUES ('CAPITAL_SEED',10000,10000,:at) RETURNING id
            """).param("at", START.minusHours(1)).query(Long.class).single();
        catalogue = UUID.randomUUID();
        jdbc.sql("""
            INSERT INTO instrument_import_run(id,status,source_url,started_at,completed_at,accepted_count,active_count,catalogue_version)
            VALUES (:id,'SUCCEEDED','performance-it',now(),now(),1,1,:id)
            """).param("id", catalogue).update();
        instrument = jdbc.sql("""
            INSERT INTO instrument_master(exchange_segment,exchange,symbol_token,trading_symbol,instrument_name,
                instrument_type,lot_size,tick_size,active,catalogue_version)
            VALUES ('NSE','NSE',:token,'P8PERF','P8PERF','EQ',1,0.05,TRUE,:v) RETURNING instrument_id
            """).param("token", UUID.randomUUID().toString().replace("-", ""))
            .param("v", catalogue).query(Long.class).single();
    }

    @AfterEach
    void cleanup() {
        jdbc.sql("DELETE FROM portfolio_performance_daily WHERE trade_date = :day").param("day", DAY).update();
        jdbc.sql("DELETE FROM strategy_performance_daily WHERE trade_date = :day").param("day", DAY).update();
        for (UUID id : orders) {
            jdbc.sql("DELETE FROM paper_cash_entry WHERE order_id = :id").param("id", id).update();
            jdbc.sql("DELETE FROM paper_fill WHERE order_id = :id").param("id", id).update();
            jdbc.sql("DELETE FROM paper_order WHERE id = :id").param("id", id).update();
        }
        jdbc.sql("DELETE FROM paper_cash_entry WHERE id = :id").param("id", capitalId).update();
        jdbc.sql("DELETE FROM market_candle WHERE instrument_id = :id").param("id", instrument).update();
        jdbc.sql("DELETE FROM instrument_master WHERE instrument_id = :id").param("id", instrument).update();
        jdbc.sql("DELETE FROM instrument_import_run WHERE id = :id").param("id", catalogue).update();
    }

    @Test
    void roundTripReconcilesToLedgerAndRebuildDoesNotChangeSourceOrDuplicateCache() {
        fill(true, "1000", "1001", "-1001", "0", START.plusHours(10));
        fill(false, "1100", "1001", "1099", "98", START.plusHours(11));
        var result = service.recalculate(DAY);
        var repeated = service.recalculate(DAY);
        assertThat(repeated).isEqualTo(result);
        assertThat(result.openingEquity()).isEqualByComparingTo("10000");
        assertThat(result.closingEquity()).isEqualByComparingTo("10098");
        assertThat(result.netPnl()).isEqualByComparingTo("98");
        assertThat(result.realizedPnl()).isEqualByComparingTo("98");
        assertThat(result.grossPnl()).isEqualByComparingTo("102");
        assertThat(result.returnPercent()).isEqualByComparingTo("0.98");
        assertThat(result.tradeCount()).isEqualTo(2);
        assertThat(result.winCount()).isEqualTo(1);
        assertThat(result.maxIntradayDrawdownPercent()).isNull();
        assertThat(jdbc.sql("SELECT count(*) FROM portfolio_performance_daily WHERE trade_date = :day")
            .param("day", DAY).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT net_pnl FROM strategy_performance_daily WHERE trade_date = :day AND strategy = 'P8_TEST'")
            .param("day", DAY).query(BigDecimal.class).single()).isEqualByComparingTo("98");
        assertThat(jdbc.sql("SELECT sum(amount) FROM paper_cash_entry WHERE id = :id OR order_id IN (:a,:b)")
            .param("id", capitalId).param("a", orders.get(0)).param("b", orders.get(1)).query(BigDecimal.class).single())
            .isEqualByComparingTo("10098");
    }

    @Test
    void openPositionUsesHistoricalMarkAndExcludesNextDayData() {
        fill(true, "1000", "1001", "-1001", "0", START.plusHours(10));
        candle("110", START.plusHours(15));
        candle("999", START.plusDays(1).plusHours(10));
        var result = service.recalculate(DAY);
        assertThat(result.closingEquity()).isEqualByComparingTo("10099");
        assertThat(result.unrealizedPnl()).isEqualByComparingTo("99");
        assertThat(result.netPnl()).isEqualByComparingTo("99");
        String evidence = jdbc.sql("SELECT valuation_evidence::text FROM portfolio_performance_daily WHERE trade_date = :day")
            .param("day", DAY).query(String.class).single();
        assertThat(evidence).contains("110").doesNotContain("999");
    }

    @Test
    void missingMarkFailsWithoutPublishingAnInventedValuation() {
        fill(true, "1000", "1001", "-1001", "0", START.plusHours(10));
        assertThatThrownBy(() -> service.recalculate(DAY)).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("PERFORMANCE_MARK_UNAVAILABLE");
        assertThat(jdbc.sql("SELECT count(*) FROM portfolio_performance_daily WHERE trade_date = :day")
            .param("day", DAY).query(Long.class).single()).isZero();
    }

    @Test
    void capitalDepositIsExcludedFromProfitAndZeroOpeningCapitalHasNoInventedReturnRate() {
        jdbc.sql("UPDATE paper_cash_entry SET created_at = :at WHERE id = :id")
            .param("at", START.plusHours(1)).param("id", capitalId).update();
        fill(true, "1000", "1001", "-1001", "0", START.plusHours(10));
        fill(false, "1100", "1001", "1099", "98", START.plusHours(11));
        var result = service.recalculate(DAY);
        assertThat(result.openingEquity()).isZero();
        assertThat(result.capitalFlow()).isEqualByComparingTo("10000");
        assertThat(result.netPnl()).isEqualByComparingTo("98");
        assertThat(result.returnPercent()).isNull();
    }

    @Test
    void crossStrategyInventoryIsNotSilentlyAttributedAndRollsBackTheWholeCache() {
        fill(true, "1000", "1001", "-1001", "0", START.plusHours(10));
        fill(false, "1100", "1001", "1099", "98", START.plusHours(11));
        jdbc.sql("UPDATE paper_order SET strategy = 'OTHER_STRATEGY' WHERE id = :id")
            .param("id", orders.get(1)).update();
        candle("110", START.plusHours(15));
        assertThatThrownBy(() -> service.recalculate(DAY)).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("PERFORMANCE_ATTRIBUTION_UNAVAILABLE");
        assertThat(jdbc.sql("SELECT count(*) FROM portfolio_performance_daily WHERE trade_date = :day")
            .param("day", DAY).query(Long.class).single()).isZero();
    }

    @Test
    void ledgerMismatchCannotPublishPerformance() {
        fill(true, "1000", "1001", "-1000", "0", START.plusHours(10));
        candle("110", START.plusHours(15));
        assertThatThrownBy(() -> service.recalculate(DAY)).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("PERFORMANCE_LEDGER_MISMATCH");
    }

    @Test
    void simultaneousRebuildsConvergeOnOneDeterministicRow() throws Exception {
        fill(true, "1000", "1001", "-1001", "0", START.plusHours(10));
        fill(false, "1100", "1001", "1099", "98", START.plusHours(11));
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<PerformanceAggregationService.DailyPerformance> task = () -> service.recalculate(DAY);
            var results = pool.invokeAll(List.of(task, task));
            assertThat(results.get(0).get()).isEqualTo(results.get(1).get());
        }
    }

    @Test
    void currentAndFutureDaysAreNotReportedAsComplete() {
        assertThatThrownBy(() -> service.recalculate(LocalDate.now(ZoneOffset.UTC).plusDays(1)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private void fill(boolean buy, String gross, String cost, String net, String realized, OffsetDateTime at) {
        UUID order = UUID.randomUUID(); orders.add(order);
        jdbc.sql("""
            INSERT INTO paper_order(id,idempotency_key,request_hash,symbol,side,quantity,reference_price,stop_loss,
                target_price,status,submitted_quantity,filled_quantity,product_type,instrument_id,strategy)
            VALUES (:id,:key,:hash,'P8PERF',:side,10,100,95,110,'FILLED',10,10,'DELIVERY',:instrument,'P8_TEST')
            """).param("id", order).param("key", "p8-" + order).param("hash", "0".repeat(64))
            .param("side", buy ? "BUY" : "SELL").param("instrument", instrument).update();
        jdbc.sql("""
            INSERT INTO paper_fill(id,order_id,symbol,side,quantity,reference_price,fill_price,gross_amount,fee_amount,
                slippage_amount,net_amount,cost_basis,realized_pnl,filled_at,fill_sequence)
            VALUES (:id,:order,'P8PERF',:side,10,100,100,:gross,1,1,:net,:cost,:realized,:at,1)
            """).param("id", UUID.randomUUID()).param("order", order).param("side", buy ? "BUY" : "SELL")
            .param("gross", new BigDecimal(gross)).param("net", new BigDecimal(net)).param("cost", new BigDecimal(cost))
            .param("realized", new BigDecimal(realized)).param("at", at).update();
        jdbc.sql("""
            INSERT INTO paper_cash_entry(order_id,entry_type,amount,balance_after,created_at)
            VALUES (:id,:type,:net,10000,:at)
            """).param("id", order).param("type", buy ? "BUY_SETTLEMENT" : "SELL_SETTLEMENT")
            .param("net", new BigDecimal(net)).param("at", at).update();
    }

    private void candle(String price, OffsetDateTime at) {
        jdbc.sql("""
            INSERT INTO market_candle(instrument_id,exchange,trading_symbol,symbol_token,catalogue_version,interval,
                candle_time,open_price,high_price,low_price,close_price,volume)
            SELECT instrument_id,exchange,trading_symbol,symbol_token,catalogue_version,'ONE_MINUTE',:at,
                :p,:p,:p,:p,1000 FROM instrument_master WHERE instrument_id = :id
            """).param("id", instrument).param("p", new BigDecimal(price)).param("at", at).update();
    }
}
