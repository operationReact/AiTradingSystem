package in.aitradingsystem.api;

import in.aitradingsystem.governance.ExperimentJobOrchestrationService;
import in.aitradingsystem.governance.ExperimentJobOrchestrationService.CreateJobInput;
import in.aitradingsystem.governance.ExperimentJobOrchestrationService.EnqueueResult;
import in.aitradingsystem.governance.ExperimentJobOrchestrationService.JobStatus;
import in.aitradingsystem.governance.ExperimentJobOrchestrationService.JobView;
import in.aitradingsystem.governance.ExperimentJobOrchestrationService.ResultAssessment;
import in.aitradingsystem.governance.ExperimentJobOrchestrationService.ResultInput;
import in.aitradingsystem.governance.ExperimentJobOrchestrationService.ResultView;
import in.aitradingsystem.governance.ExperimentJobOrchestrationService.RunnerType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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

/** ADMIN-only V4 durable experiment orchestration and immutable result evidence surface. */
@RestController
@RequestMapping("/api/admin/model-governance/experiment-jobs")
@PreAuthorize("hasRole('ADMIN')")
public class ExperimentJobController {
    private final ExperimentJobOrchestrationService jobs;

    public ExperimentJobController(ExperimentJobOrchestrationService jobs) {
        this.jobs = jobs;
    }

    public record EnqueueJobRequest(
        @NotNull UUID experimentId,
        @NotNull RunnerType runnerType,
        @NotBlank String runnerVersion,
        @Min(1) @Max(10) int maxAttempts) {}

    public record ClaimJobRequest(
        @NotNull RunnerType runnerType,
        @NotBlank String workerId,
        @Min(30) @Max(3600) int leaseSeconds) {}

    public record ClaimTokenRequest(@NotNull UUID claimToken) {}

    public record FailJobRequest(
        @NotNull UUID claimToken,
        @NotBlank String failureCode,
        @NotBlank String failureMessage,
        boolean retryable) {}

    public record CancelJobRequest(@NotBlank String reason) {}

    public record RegisterResultRequest(
        @NotNull UUID claimToken,
        @NotNull ResultAssessment assessment,
        @Min(0) long sampleCount,
        @NotBlank String resultArtifactFingerprint,
        @NotBlank String metricsFingerprint,
        @NotBlank String evidenceFingerprint) {}

    @PostMapping
    public ResponseEntity<JobView> enqueue(@Valid @RequestBody EnqueueJobRequest request) {
        EnqueueResult result = jobs.enqueue(new CreateJobInput(
            request.experimentId(),
            request.runnerType(),
            request.runnerVersion(),
            request.maxAttempts()));
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.job());
    }

    @GetMapping
    public List<JobView> list(
            @RequestParam(required = false) UUID experimentId,
            @RequestParam(required = false) JobStatus status) {
        return jobs.list(experimentId, status);
    }

    @GetMapping("/{jobId}")
    public JobView byId(@PathVariable UUID jobId) {
        return jobs.byId(jobId);
    }

    @PostMapping("/claim")
    public ResponseEntity<JobView> claim(@Valid @RequestBody ClaimJobRequest request) {
        return jobs.claimNext(request.runnerType(), request.workerId(), request.leaseSeconds())
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/{jobId}/start")
    public JobView start(@PathVariable UUID jobId, @Valid @RequestBody ClaimTokenRequest request) {
        return jobs.start(jobId, request.claimToken());
    }

    @PostMapping("/{jobId}/fail")
    public JobView fail(@PathVariable UUID jobId, @Valid @RequestBody FailJobRequest request) {
        return jobs.fail(
            jobId,
            request.claimToken(),
            request.failureCode(),
            request.failureMessage(),
            request.retryable());
    }

    @PostMapping("/{jobId}/cancel")
    public JobView cancel(@PathVariable UUID jobId, @Valid @RequestBody CancelJobRequest request) {
        return jobs.cancel(jobId, request.reason());
    }

    @PostMapping("/{jobId}/result")
    public ResponseEntity<ResultView> result(
            @PathVariable UUID jobId,
            @Valid @RequestBody RegisterResultRequest request) {
        ResultView result = jobs.complete(jobId, request.claimToken(), new ResultInput(
            request.assessment(),
            request.sampleCount(),
            request.resultArtifactFingerprint(),
            request.metricsFingerprint(),
            request.evidenceFingerprint()));
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    @GetMapping("/{jobId}/result")
    public ResultView resultByJob(@PathVariable UUID jobId) {
        return jobs.resultByJob(jobId)
            .orElseThrow(() -> new NoSuchElementException("No immutable result exists for experiment job " + jobId));
    }

    @GetMapping("/results")
    public List<ResultView> results(
            @RequestParam(required = false) UUID experimentId,
            @RequestParam(required = false) UUID modelVersionId) {
        return jobs.results(experimentId, modelVersionId);
    }

    @ExceptionHandler(NoSuchElementException.class)
    ProblemDetail notFound(NoSuchElementException ex) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        detail.setTitle("Experiment orchestration dependency not found");
        return detail;
    }

    @ExceptionHandler(DuplicateKeyException.class)
    ProblemDetail duplicate(DuplicateKeyException ex) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(
            HttpStatus.CONFLICT,
            "Immutable experiment orchestration evidence already exists.");
        detail.setTitle("Duplicate experiment evidence");
        return detail;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalid(IllegalArgumentException ex) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        detail.setTitle("Invalid experiment orchestration request");
        return detail;
    }

    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail conflict(IllegalStateException ex) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        detail.setTitle("Experiment orchestration state conflict");
        return detail;
    }
}
