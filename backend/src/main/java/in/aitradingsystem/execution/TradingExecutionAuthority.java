package in.aitradingsystem.execution;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Read-only view of the single global trading/execution authority row. */
@Service
public class TradingExecutionAuthority {
    private final JdbcClient jdbc;

    public TradingExecutionAuthority(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Snapshot current() {
        return jdbc.sql("SELECT active_mode, order_execution_mode FROM trading_execution_mode WHERE id = 1")
            .query((rs, rowNum) -> new Snapshot(
                rs.getString("active_mode"),
                OrderExecutionMode.valueOf(rs.getString("order_execution_mode"))))
            .optional()
            .orElse(new Snapshot("PAPER", OrderExecutionMode.MANUAL));
    }

    public record Snapshot(String tradingMode, OrderExecutionMode orderExecutionMode) {
        public boolean automatedPaper() {
            return "PAPER".equals(tradingMode) && orderExecutionMode == OrderExecutionMode.AUTOMATED;
        }
    }
}
