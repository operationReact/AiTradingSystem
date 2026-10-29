package in.aitradingsystem.api;

import in.aitradingsystem.backtest.BacktestApiService;
import in.aitradingsystem.backtest.BacktestException;
import in.aitradingsystem.backtest.BacktestRunSummary;
import in.aitradingsystem.backtest.BacktestValidationSummary;
import in.aitradingsystem.backtest.PaperBacktestDetail;
import in.aitradingsystem.backtest.PaperBacktestRequest;
import in.aitradingsystem.backtest.PaperBacktestService;
import in.aitradingsystem.backtest.PaperBacktestValidationDetail;
import in.aitradingsystem.backtest.PaperBacktestValidationRequest;
import in.aitradingsystem.backtest.PaperBacktestValidationService;
import in.aitradingsystem.backtest.WalkForwardDetail;
import in.aitradingsystem.backtest.WalkForwardRequest;
import in.aitradingsystem.backtest.WalkForwardValidationService;
import in.aitradingsystem.instrument.InstrumentMasterException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
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
    private final PaperBacktestValidationService validations;
    private final WalkForwardValidationService walkForward;
    private final BacktestApiService api;

    public PaperBacktestController(
            PaperBacktestService backtests,
            PaperBacktestValidationService validations,
            WalkForwardValidationService walkForward,
            BacktestApiService api) {
        this.backtests = backtests;
        this.validations = validations;
        this.walkForward = walkForward;
        this.api = api;
    }

    @PostMapping
    public PaperBacktestDetail run(@Valid @RequestBody PaperBacktestRequest request) {
        return backtests.run(request);
    }

    @GetMapping("/{id}")
    public PaperBacktestDetail get(@PathVariable UUID id) {
        return backtests.get(id);
    }

    @PostMapping("/validation")
    public PaperBacktestValidationDetail validateOutOfSample(@Valid @RequestBody PaperBacktestValidationRequest request) {
        return validations.run(request);
    }

    @GetMapping("/validation/{id}")
    public PaperBacktestValidationDetail getValidation(@PathVariable UUID id) {
        return validations.get(id);
    }

    @GetMapping("/validation")
    public List<BacktestValidationSummary> recentValidations(
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return validations.recent(limit);
    }

    /**
     * Preserves the legacy unfiltered list response while adding the P03 paginated query contract.
     * Supplying any of instrumentId/strategy/page/size opts into the paginated response.
     */
    @GetMapping
    public Object recent(
            @RequestParam(required = false) Long instrumentId,
            @RequestParam(required = false) String strategy,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Integer limit) {
        boolean paginated = instrumentId != null || (strategy != null && !strategy.isBlank()) || page != null || size != null;
        if (paginated) {
            return api.search(instrumentId, strategy, page == null ? 0 : page, size == null ? 20 : size);
        }
        return backtests.recent(limit == null ? 20 : limit);
    }

    @PostMapping("/walk-forward")
    public WalkForwardDetail runWalkForward(@Valid @RequestBody WalkForwardRequest request) {
        return walkForward.run(request);
    }

    @GetMapping("/walk-forward/{id}")
    public WalkForwardDetail getWalkForward(@PathVariable UUID id) {
        return walkForward.get(id);
    }

    @GetMapping("/{id}/equity")
    public Object equity(@PathVariable UUID id) {
        return api.equity(id);
    }

    @GetMapping(value = "/{id}/export.csv", produces = "text/csv")
    public ResponseEntity<String> exportCsv(@PathVariable UUID id) {
        String csv = api.exportCsv(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=backtest-" + id + ".csv")
                .body(csv);
    }

    @ExceptionHandler(BacktestException.class)
    ProblemDetail backtestError(BacktestException ex) {
        HttpStatus status = switch (ex.code()) {
            case "BACKTEST_NOT_FOUND", "VALIDATION_NOT_FOUND", "WALK_FORWARD_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "INVALID_REQUEST", "INVALID_INSTRUMENT_ID", "INVALID_INTERVAL", "INVALID_DATE_RANGE",
                 "FUTURE_DATE_NOT_ALLOWED", "DATE_RANGE_TOO_LARGE", "INVALID_INITIAL_CAPITAL",
                 "INVALID_LIMIT", "INVALID_SPLIT", "INVALID_PAGE", "INVALID_PAGE_SIZE",
                 "INVALID_WALK_FORWARD_WINDOWS" -> HttpStatus.BAD_REQUEST;
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
