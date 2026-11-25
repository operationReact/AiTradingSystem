package in.aitradingsystem.api;

import in.aitradingsystem.audit.AuditService;
import in.aitradingsystem.execution.OrderExecutionMode;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Single global trading authority. PAPER/LIVE selects the trading environment while
 * MANUAL/AUTOMATED selects how orders are initiated. The controls are independent.
 */
@RestController
@RequestMapping("/api/system/execution-mode")
public class ExecutionModeController {
    static final String PAPER = "PAPER";
    static final String LIVE = "LIVE";
    static final String LIVE_CONFIRMATION = "SWITCH TO LIVE";
    static final List<String> LIVE_BLOCKERS = List.of(
        "LIVE_ORDER_PATH_NOT_IMPLEMENTED",
        "BROKER_LIVE_CREDENTIALS_NOT_VERIFIED",
        "LIVE_SAFETY_CONDITIONS_NOT_VERIFIED");

    private final JdbcClient jdbc;
    private final AuditService audit;

    public ExecutionModeController(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public record ModeView(String activeMode, String orderExecutionMode, boolean liveAvailable,
                           List<String> liveBlockers, Instant checkedAt) {}
    public record ModeChangeRequest(@NotBlank @Size(max = 10) String mode, @Size(max = 40) String confirmation) {}
    public record OrderExecutionModeChangeRequest(@NotBlank @Size(max = 12) String mode) {}

    @GetMapping
    public ModeView current() {
        return view();
    }

    /** Backward-compatible PAPER/LIVE trading-environment control. */
    @PutMapping
    @Transactional
    public ModeView change(@Valid @RequestBody ModeChangeRequest request) {
        String requested = request.mode().trim().toUpperCase(Locale.ROOT);
        if (!PAPER.equals(requested) && !LIVE.equals(requested)) {
            throw new IllegalArgumentException("Unsupported trading mode: " + requested);
        }
        if (LIVE.equals(requested)) {
            if (!LIVE_CONFIRMATION.equals(request.confirmation())) {
                throw new IllegalArgumentException("Switching to LIVE requires the confirmation text '" + LIVE_CONFIRMATION + "'.");
            }
            throw new LiveModeUnavailableException();
        }

        String previous = currentTradingModeForUpdate();
        if (!PAPER.equals(previous)) {
            jdbc.sql("UPDATE trading_execution_mode SET active_mode = 'PAPER', updated_at = now(), updated_by = 'operator' WHERE id = 1")
                .update();
            audit.record("TRADING_MODE_CHANGED", "TRADING_EXECUTION_MODE", "1",
                java.util.Map.of("from", previous, "to", PAPER));
        }
        return view();
    }

    /** Select MANUAL or AUTOMATED order initiation without changing PAPER/LIVE. */
    @PutMapping("/order-execution")
    @Transactional
    public ModeView changeOrderExecutionMode(@Valid @RequestBody OrderExecutionModeChangeRequest request) {
        final OrderExecutionMode requested;
        try {
            requested = OrderExecutionMode.valueOf(request.mode().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unsupported order execution mode: " + request.mode());
        }

        String previous = currentOrderExecutionModeForUpdate();
        if (!requested.name().equals(previous)) {
            jdbc.sql("UPDATE trading_execution_mode SET order_execution_mode = :mode, updated_at = now(), updated_by = 'operator' WHERE id = 1")
                .param("mode", requested.name())
                .update();
            audit.record("EXECUTION_MODE_CHANGED", "TRADING_EXECUTION_MODE", "1",
                java.util.Map.of("from", previous, "to", requested.name()));
        }
        return view();
    }

    private String currentTradingModeForUpdate() {
        return jdbc.sql("SELECT active_mode FROM trading_execution_mode WHERE id = 1 FOR UPDATE")
            .query(String.class).optional().orElseThrow(() -> new IllegalStateException("Trading mode has not been initialized"));
    }

    private String currentOrderExecutionModeForUpdate() {
        return jdbc.sql("SELECT order_execution_mode FROM trading_execution_mode WHERE id = 1 FOR UPDATE")
            .query(String.class).optional().orElseThrow(() -> new IllegalStateException("Order execution mode has not been initialized"));
    }

    private ModeView view() {
        return jdbc.sql("SELECT active_mode, order_execution_mode FROM trading_execution_mode WHERE id = 1")
            .query((rs, rowNum) -> new ModeView(
                rs.getString("active_mode"),
                rs.getString("order_execution_mode"),
                false,
                LIVE_BLOCKERS,
                Instant.now()))
            .optional()
            .orElse(new ModeView(PAPER, OrderExecutionMode.MANUAL.name(), false, LIVE_BLOCKERS, Instant.now()));
    }

    static final class LiveModeUnavailableException extends RuntimeException {
        LiveModeUnavailableException() {
            super("LIVE trading is unavailable until the live order path and safety requirements are implemented and verified. The mode remains PAPER.");
        }
    }

    @ExceptionHandler(LiveModeUnavailableException.class)
    ProblemDetail liveUnavailable(LiveModeUnavailableException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        pd.setTitle("Live mode unavailable");
        pd.setProperty("activeMode", PAPER);
        pd.setProperty("liveAvailable", false);
        pd.setProperty("liveBlockers", LIVE_BLOCKERS);
        return pd;
    }
}
