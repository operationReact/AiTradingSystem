package in.aitradingsystem.execution;

import in.aitradingsystem.audit.AuditService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Backend-owned authority for trading destination and order initiation mode. */
@Service
public class TradingControlService {
    public static final String PAPER = "PAPER";
    public static final String LIVE = "LIVE";
    public static final String LIVE_CONFIRMATION = "SWITCH TO LIVE";
    public static final List<String> LIVE_BLOCKERS = List.of(
        "LIVE_ORDER_PATH_NOT_IMPLEMENTED",
        "BROKER_LIVE_CREDENTIALS_NOT_VERIFIED",
        "LIVE_SAFETY_CONDITIONS_NOT_VERIFIED");

    private final JdbcClient jdbc;
    private final AuditService audit;
    private final Clock clock;

    public TradingControlService(JdbcClient jdbc, AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
    }

    public record TradingControlView(
        String tradingMode,
        OrderExecutionMode executionMode,
        boolean liveAvailable,
        List<String> liveBlockers,
        Instant checkedAt
    ) {}

    public TradingControlView current() {
        return view();
    }

    @Transactional
    public TradingControlView change(String tradingMode, OrderExecutionMode executionMode, String confirmation) {
        ControlRow current = currentForUpdate();
        String requestedTradingMode = tradingMode == null || tradingMode.isBlank()
            ? current.tradingMode()
            : tradingMode.trim().toUpperCase(Locale.ROOT);
        OrderExecutionMode requestedExecutionMode = executionMode == null
            ? current.executionMode()
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
                || current.executionMode() != requestedExecutionMode) {
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
            audit.record("TRADING_CONTROL_CHANGED", "TRADING_EXECUTION_MODE", "1", java.util.Map.of(
                "fromTradingMode", current.tradingMode(),
                "toTradingMode", requestedTradingMode,
                "fromExecutionMode", current.executionMode().name(),
                "toExecutionMode", requestedExecutionMode.name()));
        }
        return view();
    }

    public OrderExecutionMode currentExecutionMode() {
        return jdbc.sql("SELECT order_execution_mode FROM trading_execution_mode WHERE id = 1")
            .query(String.class)
            .optional()
            .map(OrderExecutionMode::valueOf)
            .orElse(OrderExecutionMode.MANUAL);
    }

    private ControlRow currentForUpdate() {
        return jdbc.sql("SELECT active_mode, order_execution_mode FROM trading_execution_mode WHERE id = 1 FOR UPDATE")
            .query((rs, row) -> new ControlRow(
                rs.getString("active_mode"),
                OrderExecutionMode.valueOf(rs.getString("order_execution_mode"))))
            .optional()
            .orElseThrow(() -> new IllegalStateException("Trading control has not been initialized"));
    }

    private TradingControlView view() {
        ControlRow row = jdbc.sql("SELECT active_mode, order_execution_mode FROM trading_execution_mode WHERE id = 1")
            .query((rs, index) -> new ControlRow(
                rs.getString("active_mode"),
                OrderExecutionMode.valueOf(rs.getString("order_execution_mode"))))
            .optional()
            .orElse(new ControlRow(PAPER, OrderExecutionMode.MANUAL));
        return new TradingControlView(row.tradingMode(), row.executionMode(), false, LIVE_BLOCKERS, clock.instant());
    }

    private record ControlRow(String tradingMode, OrderExecutionMode executionMode) {}

    public static final class LiveModeUnavailableException extends RuntimeException {
        public LiveModeUnavailableException() {
            super("LIVE trading is unavailable until broker order submission and live safety requirements are verified.");
        }
    }
}
