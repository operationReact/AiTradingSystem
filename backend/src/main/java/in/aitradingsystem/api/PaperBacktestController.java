package in.aitradingsystem.api;

import in.aitradingsystem.backtest.BacktestException;
import in.aitradingsystem.backtest.BacktestRunSummary;
import in.aitradingsystem.backtest.PaperBacktestDetail;
import in.aitradingsystem.backtest.PaperBacktestRequest;
import in.aitradingsystem.backtest.PaperBacktestService;
import in.aitradingsystem.instrument.InstrumentMasterException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** PAPER-only historical simulator. No endpoint here submits or routes broker orders. */
@RestController
@Validated
@RequestMapping("/api/backtests")
public class PaperBacktestController {
    private final PaperBacktestService backtests;

    public PaperBacktestController(PaperBacktestService backtests) {
        this.backtests = backtests;
    }

    @PostMapping
    public PaperBacktestDetail run(@Valid @RequestBody PaperBacktestRequest request) {
        return backtests.run(request);
    }

    @GetMapping("/{id}")
    public PaperBacktestDetail get(@PathVariable UUID id) {
        return backtests.get(id);
    }

    @GetMapping
    public List<BacktestRunSummary> recent(@RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return backtests.recent(limit);
    }

    @ExceptionHandler(BacktestException.class)
    ProblemDetail backtestError(BacktestException ex) {
        HttpStatus status = switch (ex.code()) {
            case "BACKTEST_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "INVALID_REQUEST", "INVALID_INSTRUMENT_ID", "INVALID_INTERVAL", "INVALID_DATE_RANGE",
                 "FUTURE_DATE_NOT_ALLOWED", "DATE_RANGE_TOO_LARGE", "INVALID_INITIAL_CAPITAL",
                 "INVALID_LIMIT" -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, ex.getMessage());
        problem.setProperty("code", ex.code());
        return problem;
    }

    @ExceptionHandler(InstrumentMasterException.class)
    ProblemDetail instrumentError(InstrumentMasterException ex) {
        HttpStatus status = "INSTRUMENT_NOT_FOUND".equals(ex.code())
            ? HttpStatus.NOT_FOUND : HttpStatus.UNPROCESSABLE_ENTITY;
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, ex.getMessage());
        problem.setProperty("code", ex.code());
        return problem;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalidRequest(IllegalArgumentException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }
}