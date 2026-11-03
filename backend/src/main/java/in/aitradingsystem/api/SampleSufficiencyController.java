package in.aitradingsystem.api;

import in.aitradingsystem.governance.SampleSufficiencyService;
import in.aitradingsystem.governance.SampleSufficiencyService.CreateEvaluationResult;
import in.aitradingsystem.governance.SampleSufficiencyService.EvaluationSummary;
import in.aitradingsystem.governance.SampleSufficiencyService.EvaluationView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** ADMIN-only immutable V4 sample-sufficiency evidence surface. */
@RestController
@RequestMapping("/api/admin/model-governance/sample-sufficiency")
@PreAuthorize("hasRole('ADMIN')")
public class SampleSufficiencyController {
    private final SampleSufficiencyService sufficiency;

    public SampleSufficiencyController(SampleSufficiencyService sufficiency) {
        this.sufficiency = sufficiency;
    }

    public record EvaluateRequest(
        @NotNull UUID comparisonId,
        @Min(1) @Max(1_000_000_000L) long minimumSampleCount) {}

    @PostMapping
    public ResponseEntity<EvaluationView> evaluate(@Valid @RequestBody EvaluateRequest request) {
        CreateEvaluationResult result = sufficiency.evaluate(
            request.comparisonId(),
            request.minimumSampleCount());
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
            .body(result.evaluation());
    }

    @GetMapping
    public List<EvaluationSummary> list(@RequestParam(required = false) UUID comparisonId) {
        return sufficiency.list(comparisonId);
    }

    @GetMapping("/{evaluationId}")
    public EvaluationView byId(@PathVariable UUID evaluationId) {
        return sufficiency.byId(evaluationId);
    }

    @ExceptionHandler(NoSuchElementException.class)
    ProblemDetail notFound(NoSuchElementException ex) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        detail.setTitle("Sample sufficiency dependency not found");
        return detail;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalid(IllegalArgumentException ex) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        detail.setTitle("Invalid sample sufficiency policy");
        return detail;
    }

    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail conflict(IllegalStateException ex) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        detail.setTitle("Sample sufficiency evidence conflict");
        return detail;
    }
}
