package in.aitradingsystem.api;

import in.aitradingsystem.governance.ExperimentSpecService;
import in.aitradingsystem.governance.ExperimentSpecService.DatasetBinding;
import in.aitradingsystem.governance.ExperimentSpecService.EconomicCostBinding;
import in.aitradingsystem.governance.ExperimentSpecService.ExperimentSpecInput;
import in.aitradingsystem.governance.ExperimentSpecService.ExperimentSpecView;
import in.aitradingsystem.governance.ExperimentSpecService.FoldBinding;
import in.aitradingsystem.governance.ExperimentSpecService.FoldProtocol;
import in.aitradingsystem.governance.ExperimentSpecService.TargetBinding;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
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

/** ADMIN-only immutable V4 experiment specification surface. */
@RestController
@RequestMapping("/api/admin/model-governance/experiments")
@PreAuthorize("hasRole('ADMIN')")
public class ExperimentSpecController {
    private final ExperimentSpecService experiments;

    public ExperimentSpecController(ExperimentSpecService experiments) {
        this.experiments = experiments;
    }

    public record DatasetBindingRequest(
        String datasetFingerprint,
        String featureSchemaFingerprint,
        String universeFingerprint,
        Instant asOf,
        boolean pointInTime) {}

    public record TargetBindingRequest(
        String targetName,
        int horizonBars,
        String labelDefinitionFingerprint) {}

    public record FoldBindingRequest(
        FoldProtocol protocol,
        int folds,
        int trainBars,
        int testBars,
        int purgeBars,
        int embargoBars) {}

    public record EconomicCostBindingRequest(
        BigDecimal brokerageRate,
        BigDecimal slippageRate,
        BigDecimal spreadBps,
        BigDecimal statutoryCostRate) {}

    public record CreateExperimentSpecRequest(
        @NotNull UUID modelVersionId,
        @NotNull Instant fromAt,
        @NotNull Instant toAt,
        long seed,
        @NotNull @Valid DatasetBindingRequest dataset,
        @NotNull @Valid TargetBindingRequest target,
        @NotNull @Valid FoldBindingRequest folds,
        @NotNull @Valid EconomicCostBindingRequest economicCosts) {}

    @PostMapping
    public ResponseEntity<ExperimentSpecView> create(@Valid @RequestBody CreateExperimentSpecRequest request) {
        ExperimentSpecView created = experiments.create(new ExperimentSpecInput(
            request.modelVersionId(),
            request.fromAt(),
            request.toAt(),
            request.seed(),
            new DatasetBinding(
                request.dataset().datasetFingerprint(),
                request.dataset().featureSchemaFingerprint(),
                request.dataset().universeFingerprint(),
                request.dataset().asOf(),
                request.dataset().pointInTime()),
            new TargetBinding(
                request.target().targetName(),
                request.target().horizonBars(),
                request.target().labelDefinitionFingerprint()),
            new FoldBinding(
                request.folds().protocol(),
                request.folds().folds(),
                request.folds().trainBars(),
                request.folds().testBars(),
                request.folds().purgeBars(),
                request.folds().embargoBars()),
            new EconomicCostBinding(
                request.economicCosts().brokerageRate(),
                request.economicCosts().slippageRate(),
                request.economicCosts().spreadBps(),
                request.economicCosts().statutoryCostRate())));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping
    public List<ExperimentSpecView> list(@RequestParam(required = false) UUID modelVersionId) {
        return experiments.list(modelVersionId);
    }

    @GetMapping("/{experimentId}")
    public ExperimentSpecView byId(@PathVariable UUID experimentId) {
        return experiments.byId(experimentId);
    }

    @ExceptionHandler(NoSuchElementException.class)
    ProblemDetail notFound(NoSuchElementException ex) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        detail.setTitle("Experiment dependency not found");
        return detail;
    }

    @ExceptionHandler(DuplicateKeyException.class)
    ProblemDetail conflict(DuplicateKeyException ex) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(
            HttpStatus.CONFLICT,
            "An immutable experiment with the same canonical specification already exists.");
        detail.setTitle("Duplicate experiment specification");
        return detail;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalid(IllegalArgumentException ex) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        detail.setTitle("Invalid experiment specification");
        return detail;
    }

    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail blocked(IllegalStateException ex) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        detail.setTitle("Experiment blocked by governance");
        return detail;
    }
}
