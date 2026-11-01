package in.aitradingsystem.api;

import in.aitradingsystem.execution.OrderExecutionMode;
import in.aitradingsystem.execution.TradingControlService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Customer-facing control surface for PAPER/LIVE and MANUAL/AUTOMATED behavior. */
@RestController
@RequestMapping("/api/system/trading-control")
public class TradingControlController {
    private final TradingControlService controls;

    public TradingControlController(TradingControlService controls) {
        this.controls = controls;
    }

    public record ChangeRequest(
        @Size(max = 8) String tradingMode,
        OrderExecutionMode executionMode,
        @Size(max = 40) String confirmation
    ) {}

    @GetMapping
    public TradingControlService.TradingControlView current() {
        return controls.current();
    }

    @PutMapping
    public TradingControlService.TradingControlView change(@Valid @RequestBody ChangeRequest request) {
        return controls.change(request.tradingMode(), request.executionMode(), request.confirmation());
    }

    @ExceptionHandler(TradingControlService.LiveModeUnavailableException.class)
    ProblemDetail liveUnavailable(TradingControlService.LiveModeUnavailableException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        pd.setTitle("Live trading unavailable");
        pd.setProperty("tradingMode", TradingControlService.PAPER);
        pd.setProperty("liveAvailable", false);
        pd.setProperty("liveBlockers", TradingControlService.LIVE_BLOCKERS);
        return pd;
    }
}
