package in.aitradingsystem.execution;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Single backend authority for trading destination and execution style.
 *
 * <p>PAPER/LIVE and MANUAL/AUTOMATED are independent controls. LIVE remains fail-closed until
 * broker submission and the dedicated live-safety gates are explicitly delivered.
 */
@Service
public class TradingExecutionAuthority {
    public static final String PAPER = "PAPER";
    public static final String LIVE = "LIVE";
    public static final String LIVE_CONFIRMATION = "SWITCH TO LIVE";
    public static final List<String> LIVE_BLOCKERS = List.of(
        "LIVE_ORDER_PATH_NOT_IMPLEMENTED",
        "BROKER_LIVE_CREDENTIALS_NOT_VERIFIED",
        "LIVE_SAFETY_CONDITIONS_NOT_VERIFIED");

    private final JdbcClient jdbc;

    public TradingExecutionAuthority(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Snapshot current() {
        return read(false);
    }

    @Transactional
    public Snapshot change(String tradingMode, OrderExecutionMode executionMode, String confirmation) {
        Snapshot current = read(true);
        String requestedTradingMode = tradingMode == null || tradingMode.isBlank()
            ? current.tradingMode()
            : tradingMode.trim().toUpperCase(Locale.ROOT);
        OrderExecutionMode requestedExecutionMode = executionMode == null
            ? current.orderExecutionMode()
            : executionMode;

        if (!PAPER.equals(requestedTradingMode) && !LIVE.equals(requestedTradingMode)) {
            throw new IllegalArgumentException("Unsupported trading mode: " + requestedTradingMode);
        }
        if (LIVE.equals(requestedTradingMode)) {
            if (!LIVE_CONFIRMATION.equals(confirmation)) {
                throw new IllegalArgumentException("Switching to LIVE requires explicit confirmation.");
            }
            throw new LiveModeUnavailableException();
        }

        if (!current.tradingMode().equals(requestedTradingMode)
                || current.orderExecutionMode() != requestedExecutionMode) {
            jdbc.sql("""
                    UPDATE trading_execution_mode
                       SET active_mode = :tradingMode,
                           order_execution_mode = :executionMode,
                           updated_at = now(),
                           updated_by = 'user'
                     WHERE id = 1
                    """)
                .param("tradingMode", requestedTradingMode)
                .param("executionMode", requestedExecutionMode.name())
                .update();
        }
        return current();
    }

    private Snapshot read(boolean forUpdate) {
        String suffix = forUpdate ? " FOR UPDATE" : "";
        return jdbc.sql("SELECT active_mode, order_execution_mode FROM trading_execution_mode WHERE id = 1" + suffix)
            .query((rs, rowNum) -> new Snapshot(
                rs.getString("active_mode"),
                OrderExecutionMode.valueOf(rs.getString("order_execution_mode"))))
            .optional()
            .orElse(new Snapshot(PAPER, OrderExecutionMode.MANUAL));
    }

    public record Snapshot(String tradingMode, OrderExecutionMode orderExecutionMode) {
        public boolean automatedPaper() {
            return PAPER.equals(tradingMode) && orderExecutionMode == OrderExecutionMode.AUTOMATED;
        }

        public boolean liveAvailable() {
            return false;
        }

        public List<String> liveBlockers() {
            return LIVE_BLOCKERS;
        }

        public Instant checkedAt() {
            return Instant.now();
        }
    }

    public static final class LiveModeUnavailableException extends RuntimeException {
        public LiveModeUnavailableException() {
            super("LIVE trading is unavailable until broker order submission and live safety requirements are verified.");
        }
    }
}
