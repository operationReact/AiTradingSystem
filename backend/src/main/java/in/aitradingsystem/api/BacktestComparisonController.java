package in.aitradingsystem.api;

import in.aitradingsystem.governance.BacktestComparisonService;
import in.aitradingsystem.governance.BacktestComparisonService.CandidateInput;
import in.aitradingsystem.governance.BacktestComparisonService.ComparisonMetrics;
import in.aitradingsystem.governance.BacktestComparisonService.ComparisonSummary;
import in.aitradingsystem.governance.BacktestComparisonService.ComparisonView;
import in.aitradingsystem.governance.BacktestComparisonService.CreateComparisonInput;
import in.aitradingsystem.governance.BacktestComparisonService.CreateComparisonResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** ADMIN-only immutable V4 side-by-side model/backtest comparison evidence surface. */
@RestController
@RequestMapping("/api/admin/model-governance/backtest-comparisons")
@PreAuthorize("hasRole('ADMIN')")
public class BacktestComparisonController {
    private final BacktestComparisonService comparisons;

    public BacktestComparisonController(BacktestComparisonService comparisons) {
        this.comparisons = comparisons;
    }

    public record MetricsRequest(
        BigDecimal netPnl,
        BigDecimal totalReturnPercent,
        BigDecimal sharpeRatio,
        BigDecimal sortinoRatio,
        BigDecimal maxDrawdownPercent,
        BigDecimal winRatePercent,
        BigDecimal profitFactor,
        BigDecimal expectancy,
        BigDecimal turnover,
        BigDecimal transactionCosts,
        BigDecimal brierScore,
        BigDecimal logLoss,
        BigDecimal expectedCalibrationError,
        BigDecimal precisionAtK,
        BigDecimal netReturnAtK,
        BigDecimal coverageRate) {

        ComparisonMetrics toDomain() {
            return new ComparisonMetrics(
                netPnl,
                totalReturnPercent,
                sharpeRatio,
                sortinoRatio,
                maxDrawdownPercent,
                winRatePercent,
                profitFactor,
                expectancy,
                turnover,
                transactionCosts,
                brierScore,
                logLoss,
                expectedCalibrationError,
                precisionAtK,
                netReturnAtK,
                coverageRate);
        }
    }

    public record CandidateRequest(
        @NotNull UUID resultId,
        @NotNull @Valid MetricsRequest metrics) {}

    public record CreateComparisonRequest(
        @NotEmpty @Size(min = 2, max = 20) List<@Valid CandidateRequest> candidates) {}

    @PostMapping
    public ResponseEntity<ComparisonView> create(@Valid @RequestBody CreateComparisonRequest request) {
        CreateComparisonResult result = comparisons.create(new CreateComparisonInput(
            request.candidates().stream()
                .map(candidate -> new CandidateInput(candidate.resultId(), candidate.metrics().toDomain()))
                .toList()));
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
            .body(result.comparison());
    }

    @GetMapping
    public List<ComparisonSummary> list() {
        return comparisons.list();
    }

    @GetMapping("/{comparisonId}")
    public ComparisonView byId(@PathVariable UUID comparisonId) {
        return comparisons.byId(comparisonId);
    }

    @ExceptionHandler(NoSuchElementException.class)
    ProblemDetail notFound(NoSuchElementException ex) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        detail.setTitle("Backtest comparison dependency not found");
        return detail;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalid(IllegalArgumentException ex) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        detail.setTitle("Invalid backtest comparison request");
        return detail;
    }

    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail conflict(IllegalStateException ex) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        detail.setTitle("Backtest comparison evidence conflict");
        return detail;
    }
}
