package in.aitradingsystem.api;

import in.aitradingsystem.execution.OrderExecutionMode;
import in.aitradingsystem.execution.TradingExecutionAuthority;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Customer-facing authority for PAPER/LIVE and MANUAL/AUTOMATED controls. */
@RestController
@RequestMapping("/api/system/trading-control")
public class TradingControlController {
    private final TradingExecutionAuthority authority;

    public TradingControlController(TradingExecutionAuthority authority) {
        this.authority = authority;
    }

    public record ChangeRequest(
        @Size(max = 8) String tradingMode,
        OrderExecutionMode executionMode,
        @Size(max = 40) String confirmation
    ) {}

    public record TradingControlView(
        String tradingMode,
        OrderExecutionMode executionMode,
        boolean liveAvailable,
        List<String> liveBlockers,
        Instant checkedAt
    ) {
        static TradingControlView from(TradingExecutionAuthority.Snapshot snapshot) {
            return new TradingControlView(
                snapshot.tradingMode(),
                snapshot.orderExecutionMode(),
                false,
                TradingExecutionAuthority.LIVE_BLOCKERS,
                Instant.now());
        }
    }

    @GetMapping
    public TradingControlView current() {
        return TradingControlView.from(authority.current());
    }

    @PutMapping
    public TradingControlView change(@Valid @RequestBody ChangeRequest request) {
        return TradingControlView.from(
            authority.change(request.tradingMode(), request.executionMode(), request.confirmation()));
    }

    @ExceptionHandler(TradingExecutionAuthority.LiveModeUnavailableException.class)
    ProblemDetail liveUnavailable(TradingExecutionAuthority.LiveModeUnavailableException ex) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        detail.setTitle("Live trading unavailable");
        detail.setProperty("tradingMode", TradingExecutionAuthority.PAPER);
        detail.setProperty("liveAvailable", false);
        detail.setProperty("liveBlockers", TradingExecutionAuthority.LIVE_BLOCKERS);
        return detail;
    }
}
