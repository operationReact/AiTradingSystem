package in.aitradingsystem.paper;

import static org.assertj.core.api.Assertions.assertThat;

import in.aitradingsystem.audit.AuditService;
import in.aitradingsystem.execution.OrderExecutionMode;
import in.aitradingsystem.execution.TradingExecutionAuthority;
import in.aitradingsystem.risk.RiskPolicyConfigRepository;
import in.aitradingsystem.strategy.StrategySignal;
import in.aitradingsystem.strategy.StrategySignalRepository;
import in.aitradingsystem.support.AbstractApprovalPaperIT;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Connected acceptance for the automated PAPER lifecycle.
 *
 * <p>This test intentionally uses the real PostgreSQL repositories, Risk Engine, approval/readiness path,
 * execution model, fills, positions and ledger. The clock is fixed only to make signal and market freshness
 * deterministic. No LIVE adapter exists on this path.
 */
class ConnectedAutomatedPaperLifecycleIT extends AbstractApprovalPaperIT {
    private static final String SYMBOL = "P8AUTO";

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired TradingExecutionAuthority authority;
    @Autowired StrategySignalRepository signals;
    @Autowired PaperOrderService orders;
    @Autowired PaperTradingService trading;
    @Autowired AutomatedPaperPositionRepository monitoredPositions;
    @Autowired RiskPolicyConfigRepository riskPolicies;
    @Autowired AuditService audit;

    @BeforeEach
    void enableAutomatedPaper() {
        approvalJdbc.sql("""
                UPDATE trading_execution_mode
                   SET active_mode = 'PAPER',
                       order_execution_mode = 'AUTOMATED',
                       updated_at = now(),
                       updated_by = 'PAPER_08_IT'
                 WHERE id = 1
                """).update();
        approvalJdbc.sql("UPDATE trading_control SET new_orders_halted = FALSE, reason = NULL WHERE id = 1").update();
    }

    @AfterEach
    void restoreManualPaperAndSignal() {
        approvalJdbc.sql("DELETE FROM strategy_signal WHERE trading_symbol = :symbol")
            .param("symbol", SYMBOL).update();
        approvalJdbc.sql("""
                UPDATE trading_execution_mode
                   SET active_mode = 'PAPER',
                       order_execution_mode = 'MANUAL',
                       updated_at = now(),
                       updated_by = 'PAPER_08_IT_CLEANUP'
                 WHERE id = 1
                """).update();
    }

    @Test
    void strategySignalTraversesRiskFillPositionProtectiveExitAndRealizedHistory() {
        market(SYMBOL, new BigDecimal("100.00"));
        Long instrumentId = approvalJdbc.sql("""
                SELECT instrument_id
                  FROM instrument_master
                 WHERE trading_symbol = :symbol
                   AND catalogue_version = (SELECT active_version FROM instrument_catalogue_state WHERE singleton_id = 1)
                """).param("symbol", SYMBOL).query(Long.class).single();
        String token = approvalJdbc.sql("SELECT symbol_token FROM instrument_master WHERE instrument_id = :id")
            .param("id", instrumentId).query(String.class).single();

        StrategySignal signal = new StrategySignal(
            UUID.randomUUID().toString(),
            instrumentId,
            SYMBOL,
            "NSE",
            "ONE_MINUTE",
            "BUY",
            "SMA_TREND_VOLUME_V1",
            new BigDecimal("100.00"),
            new BigDecimal("95.00"),
            new BigDecimal("110.00"),
            new BigDecimal("101.00"),
            new BigDecimal("99.00"),
            new BigDecimal("1000"),
            new BigDecimal("1800"),
            0.86,
            List.of("TREND_CONFIRMED", "VOLUME_CONFIRMED"),
            "PAPER_08_IT",
            NOW.minusSeconds(5),
            NOW.minusSeconds(10),
            "CANDIDATE_EXECUTABLE");
        signals.save(signal, token);

        AutomatedPaperOrderService entry = new AutomatedPaperOrderService(
            authority, signals, orders, audit, 2, 1);
        AutomatedPaperOrderService.RunResult entryRun = entry.runOnce();

        assertThat(entryRun.automationEnabled()).isTrue();
        assertThat(entryRun.eligibleSignals()).isEqualTo(1);
        assertThat(entryRun.submitted()).isEqualTo(1);
        assertThat(approvalJdbc.sql("""
                SELECT COUNT(*)
                  FROM paper_order
                 WHERE symbol = :symbol
                   AND side = 'BUY'
                   AND status = 'FILLED'
                   AND execution_origin = 'AUTOMATED'
                   AND lifecycle_reason = 'STRATEGY_SIGNAL'
                """).param("symbol", SYMBOL).query(Integer.class).single()).isEqualTo(1);
        assertThat(approvalJdbc.sql("""
                SELECT COUNT(*) FROM risk_decision d
                JOIN paper_order o ON o.id = d.order_id
                WHERE o.symbol = :symbol AND d.decision = 'APPROVED'
                """).param("symbol", SYMBOL).query(Integer.class).single()).isEqualTo(1);
        assertThat(approvalJdbc.sql("""
                SELECT quantity FROM paper_position
                 WHERE symbol = :symbol AND product_type = 'INTRADAY'
                """).param("symbol", SYMBOL).query(Integer.class).single()).isEqualTo(2);

        // A fresh mark below the persisted stop must use the same Risk Engine/order/accounting path.
        market(SYMBOL, new BigDecimal("94.00"));
        AutomatedPaperPositionMonitorService monitor = new AutomatedPaperPositionMonitorService(
            authority,
            monitoredPositions,
            trading,
            riskPolicies,
            audit,
            Clock.fixed(NOW, ZoneOffset.UTC));
        AutomatedPaperPositionMonitorService.MonitorRun exitRun = monitor.runOnce();

        assertThat(exitRun.automationEnabled()).isTrue();
        assertThat(exitRun.triggered()).isEqualTo(1);
        assertThat(exitRun.submitted()).isEqualTo(1);
        assertThat(exitRun.attempts()).singleElement().satisfies(attempt -> {
            assertThat(attempt.reason()).isEqualTo("STOP_LOSS");
            assertThat(attempt.status()).isEqualTo("FILLED");
        });

        assertThat(approvalJdbc.sql("""
                SELECT COUNT(*)
                  FROM paper_order
                 WHERE symbol = :symbol
                   AND side = 'SELL'
                   AND status = 'FILLED'
                   AND execution_origin = 'AUTOMATED'
                   AND lifecycle_reason = 'STOP_LOSS'
                """).param("symbol", SYMBOL).query(Integer.class).single()).isEqualTo(1);
        assertThat(approvalJdbc.sql("""
                SELECT COUNT(*) FROM risk_decision d
                JOIN paper_order o ON o.id = d.order_id
                WHERE o.symbol = :symbol AND d.decision = 'APPROVED'
                """).param("symbol", SYMBOL).query(Integer.class).single()).isEqualTo(2);
        assertThat(approvalJdbc.sql("SELECT COUNT(*) FROM paper_position WHERE symbol = :symbol")
            .param("symbol", SYMBOL).query(Integer.class).single()).isZero();
        assertThat(approvalJdbc.sql("""
                SELECT COALESCE(SUM(f.realized_pnl), 0)
                  FROM paper_fill f
                  JOIN paper_order o ON o.id = f.order_id
                 WHERE o.symbol = :symbol
                """).param("symbol", SYMBOL).query(BigDecimal.class).single()).isNegative();

        List<String> lifecycle = approvalJdbc.sql("""
                SELECT side || ':' || execution_origin || ':' || lifecycle_reason || ':' || status
                  FROM paper_order
                 WHERE symbol = :symbol
                 ORDER BY created_at, id
                """).param("symbol", SYMBOL).query(String.class).list();
        assertThat(lifecycle).containsExactly(
            "BUY:AUTOMATED:STRATEGY_SIGNAL:FILLED",
            "SELL:AUTOMATED:STOP_LOSS:FILLED");
    }
}
