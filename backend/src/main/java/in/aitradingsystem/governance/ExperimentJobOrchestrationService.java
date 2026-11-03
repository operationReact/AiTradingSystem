package in.aitradingsystem.governance;

import in.aitradingsystem.audit.AuditService;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Durable V4 experiment orchestration and immutable result registry.
 *
 * Jobs bind execution to an immutable experiment-spec fingerprint and exact runner version.
 * Results are evidence only: this service never authorizes trading capital or broker execution.
 */
@Service
public class ExperimentJobOrchestrationService {
    public static final String JOB_CONTRACT_VERSION = "experiment-job.v1";
    public static final String RESULT_CONTRACT_VERSION = "experiment-result.v1";
    private static final String HASH64 = "^[a-f0-9]{64}$";

    private final JdbcClient jdbc;
    private final AuditService audit;
    private final Clock clock;
    private final ExperimentSpecService experiments;
    private final ModelEmergencyControlService emergencyControls;

    public ExperimentJobOrchestrationService(
            JdbcClient jdbc,
            AuditService audit,
            Clock clock,
            ExperimentSpecService experiments,
            ModelEmergencyControlService emergencyControls) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
        this.experiments = experiments;
        this.emergencyControls = emergencyControls;
    }

    public enum RunnerType {
        JAVA_BACKTEST,
        PYTHON_INTELLIGENCE
    }

    public enum JobStatus {
        QUEUED,
        CLAIMED,
        RUNNING,
        SUCCEEDED,
        FAILED,
        CANCELLED
    }

    public enum ResultAssessment {
        PASS,
        FAIL,
        INCONCLUSIVE
    }

    public record CreateJobInput(
        UUID experimentId,
        RunnerType runnerType,
        String runnerVersion,
        int maxAttempts) {}

    public record JobView(
        String contractVersion,
        UUID jobId,
        UUID experimentId,
        String specFingerprint,
        RunnerType runnerType,
        String runnerVersion,
        String orchestrationFingerprint,
        JobStatus status,
        int attempt,
        int maxAttempts,
        UUID claimToken,
        String claimedBy,
        Instant claimedAt,
        Instant leaseExpiresAt,
        Instant startedAt,
        Instant finishedAt,
        String failureCode,
        String failureMessage,
        Instant createdAt,
        String createdBy,
        boolean canAuthorizeCapital) {}

    public record EnqueueResult(JobView job, boolean created) {}

    public record ResultInput(
        ResultAssessment assessment,
        long sampleCount,
        String resultArtifactFingerprint,
        String metricsFingerprint,
        String evidenceFingerprint) {}

    public record ResultView(
        String contractVersion,
        UUID resultId,
        UUID jobId,
        UUID experimentId,
        UUID modelVersionId,
        String specFingerprint,
        String orchestrationFingerprint,
        ResultAssessment assessment,
        long sampleCount,
        String resultArtifactFingerprint,
        String metricsFingerprint,
        String evidenceFingerprint,
        String resultFingerprint,
        Instant createdAt,
        String createdBy,
        boolean canAuthorizeCapital) {}

    private record ClaimState(
        UUID jobId,
        UUID experimentId,
        JobStatus status,
        int attempt,
        int maxAttempts,
        UUID claimToken,
        Instant leaseExpiresAt) {}

    @Transactional
    public EnqueueResult enqueue(CreateJobInput input) {
        validateCreate(input);
        ExperimentSpecService.ExperimentSpecView experiment = experiments.byId(input.experimentId());
        emergencyControls.requireModelAllowed(experiment.modelVersionId());

        String runnerVersion = input.runnerVersion().trim();
        String orchestrationFingerprint = ModelRegistryService.fingerprint(
            JOB_CONTRACT_VERSION,
            experiment.specFingerprint(),
            input.runnerType().name(),
            runnerVersion);

        Optional<JobView> existing = byOrchestrationFingerprint(orchestrationFingerprint);
        if (existing.isPresent()) return new EnqueueResult(existing.get(), false);

        UUID jobId = UUID.randomUUID();
        Instant now = clock.instant();
        String actor = currentActor();
        try {
            jdbc.sql("""
                    INSERT INTO experiment_job (
                        job_id, contract_version, experiment_id, spec_fingerprint,
                        runner_type, runner_version, orchestration_fingerprint,
                        status, attempt, max_attempts, created_at, created_by, can_authorize_capital)
                    VALUES (
                        :jobId, :contractVersion, :experimentId, :specFingerprint,
                        :runnerType, :runnerVersion, :orchestrationFingerprint,
                        'QUEUED', 0, :maxAttempts, :createdAt, :createdBy, FALSE)
                    """)
                .param("jobId", jobId)
                .param("contractVersion", JOB_CONTRACT_VERSION)
                .param("experimentId", input.experimentId())
                .param("specFingerprint", experiment.specFingerprint())
                .param("runnerType", input.runnerType().name())
                .param("runnerVersion", runnerVersion)
                .param("orchestrationFingerprint", orchestrationFingerprint)
                .param("maxAttempts", input.maxAttempts())
                .param("createdAt", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .param("createdBy", actor)
                .update();
        } catch (DuplicateKeyException ex) {
            return new EnqueueResult(
                byOrchestrationFingerprint(orchestrationFingerprint)
                    .orElseThrow(() -> ex),
                false);
        }

        audit.record("EXPERIMENT_JOB_QUEUED", "EXPERIMENT_JOB", jobId.toString(), Map.of(
            "experimentId", input.experimentId().toString(),
            "specFingerprint", experiment.specFingerprint(),
            "runnerType", input.runnerType().name(),
            "runnerVersion", runnerVersion,
            "orchestrationFingerprint", orchestrationFingerprint,
            "maxAttempts", input.maxAttempts(),
            "canAuthorizeCapital", false));
        return new EnqueueResult(byId(jobId), true);
    }

    /**
     * Claims the oldest runnable job using a PostgreSQL SKIP LOCKED lease.
     * Jobs whose model became disabled after enqueue are cancelled before a worker can run them.
     */
    @Transactional
    public Optional<JobView> claimNext(RunnerType runnerType, String rawWorkerId, int leaseSeconds) {
        if (runnerType == null) throw new IllegalArgumentException("runnerType is required");
        String workerId = normalizeWorkerId(rawWorkerId);
        if (leaseSeconds < 30 || leaseSeconds > 3600) {
            throw new IllegalArgumentException("leaseSeconds must be between 30 and 3600");
        }

        for (int skipped = 0; skipped < 100; skipped++) {
            Optional<UUID> candidate = jdbc.sql("""
                    SELECT job_id
                      FROM experiment_job
                     WHERE runner_type = :runnerType
                       AND status = 'QUEUED'
                       AND attempt < max_attempts
                     ORDER BY created_at, job_id
                     FOR UPDATE SKIP LOCKED
                     LIMIT 1
                    """)
                .param("runnerType", runnerType.name())
                .query(UUID.class)
                .optional();
            if (candidate.isEmpty()) return Optional.empty();

            JobView current = byId(candidate.get());
            ExperimentSpecService.ExperimentSpecView experiment = experiments.byId(current.experimentId());
            if (!emergencyControls.isModelAllowed(experiment.modelVersionId())) {
                Instant cancelledAt = clock.instant();
                jdbc.sql("""
                        UPDATE experiment_job
                           SET status = 'CANCELLED', finished_at = :finishedAt,
                               failure_code = 'GOVERNANCE_DISABLED',
                               failure_message = 'Model disabled before worker claim',
                               claim_token = NULL, claimed_by = NULL, claimed_at = NULL, lease_expires_at = NULL
                         WHERE job_id = :jobId AND status = 'QUEUED'
                        """)
                    .param("finishedAt", OffsetDateTime.ofInstant(cancelledAt, ZoneOffset.UTC))
                    .param("jobId", current.jobId())
                    .update();
                audit.record("EXPERIMENT_JOB_CANCELLED", "EXPERIMENT_JOB", current.jobId().toString(), Map.of(
                    "reason", "Model disabled before worker claim",
                    "backendValidationResult", "GOVERNANCE_BLOCKED",
                    "canAuthorizeCapital", false));
                continue;
            }

            UUID token = UUID.randomUUID();
            Instant claimedAt = clock.instant();
            Instant leaseExpiresAt = claimedAt.plusSeconds(leaseSeconds);
            int updated = jdbc.sql("""
                    UPDATE experiment_job
                       SET status = 'CLAIMED', attempt = attempt + 1,
                           claim_token = :claimToken, claimed_by = :claimedBy,
                           claimed_at = :claimedAt, lease_expires_at = :leaseExpiresAt,
                           started_at = NULL, finished_at = NULL,
                           failure_code = NULL, failure_message = NULL
                     WHERE job_id = :jobId AND status = 'QUEUED' AND attempt < max_attempts
                    """)
                .param("claimToken", token)
                .param("claimedBy", workerId)
                .param("claimedAt", OffsetDateTime.ofInstant(claimedAt, ZoneOffset.UTC))
                .param("leaseExpiresAt", OffsetDateTime.ofInstant(leaseExpiresAt, ZoneOffset.UTC))
                .param("jobId", current.jobId())
                .update();
            if (updated == 0) continue;

            JobView claimed = byId(current.jobId());
            audit.record("EXPERIMENT_JOB_CLAIMED", "EXPERIMENT_JOB", claimed.jobId().toString(), Map.of(
                "workerId", workerId,
                "runnerType", runnerType.name(),
                "attempt", claimed.attempt(),
                "leaseExpiresAt", claimed.leaseExpiresAt().toString(),
                "canAuthorizeCapital", false));
            return Optional.of(claimed);
        }
        throw new IllegalStateException("Too many governance-blocked experiment jobs in the runnable queue");
    }

    @Transactional
    public JobView start(UUID jobId, UUID claimToken) {
        requireIds(jobId, claimToken);
        ClaimState state = claimStateForUpdate(jobId);
        requireClaim(state, claimToken, JobStatus.CLAIMED, true);
        ExperimentSpecService.ExperimentSpecView experiment = experiments.byId(state.experimentId());
        emergencyControls.requireModelAllowed(experiment.modelVersionId());

        Instant now = clock.instant();
        jdbc.sql("""
                UPDATE experiment_job
                   SET status = 'RUNNING', started_at = :startedAt
                 WHERE job_id = :jobId AND status = 'CLAIMED' AND claim_token = :claimToken
                """)
            .param("startedAt", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
            .param("jobId", jobId)
            .param("claimToken", claimToken)
            .update();
        audit.record("EXPERIMENT_JOB_STARTED", "EXPERIMENT_JOB", jobId.toString(), Map.of(
            "attempt", state.attempt(),
            "canAuthorizeCapital", false));
        return byId(jobId);
    }

    @Transactional
    public JobView fail(
            UUID jobId,
            UUID claimToken,
            String rawFailureCode,
            String rawFailureMessage,
            boolean retryable) {
        requireIds(jobId, claimToken);
        String failureCode = normalizeFailureCode(rawFailureCode);
        String failureMessage = normalizeFailureMessage(rawFailureMessage);
        ClaimState state = claimStateForUpdate(jobId);
        if (state.status() != JobStatus.CLAIMED && state.status() != JobStatus.RUNNING) {
            throw new IllegalStateException("Only a claimed/running experiment job can fail");
        }
        requireClaimToken(state, claimToken, false);

        boolean requeue = retryable && state.attempt() < state.maxAttempts();
        Instant now = clock.instant();
        jdbc.sql("""
                UPDATE experiment_job
                   SET status = :status,
                       finished_at = :finishedAt,
                       failure_code = :failureCode,
                       failure_message = :failureMessage,
                       claim_token = NULL, claimed_by = NULL, claimed_at = NULL, lease_expires_at = NULL
                 WHERE job_id = :jobId
                """)
            .param("status", requeue ? JobStatus.QUEUED.name() : JobStatus.FAILED.name())
            .param("finishedAt", requeue ? null : OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
            .param("failureCode", failureCode)
            .param("failureMessage", failureMessage)
            .param("jobId", jobId)
            .update();
        audit.record(
            requeue ? "EXPERIMENT_JOB_REQUEUED" : "EXPERIMENT_JOB_FAILED",
            "EXPERIMENT_JOB",
            jobId.toString(),
            Map.of(
                "attempt", state.attempt(),
                "maxAttempts", state.maxAttempts(),
                "failureCode", failureCode,
                "retryable", retryable,
                "requeued", requeue,
                "canAuthorizeCapital", false));
        return byId(jobId);
    }

    @Transactional
    public JobView cancel(UUID jobId, String rawReason) {
        if (jobId == null) throw new IllegalArgumentException("jobId is required");
        String reason = normalizeFailureMessage(rawReason);
        ClaimState state = claimStateForUpdate(jobId);
        if (state.status() == JobStatus.SUCCEEDED || state.status() == JobStatus.FAILED
                || state.status() == JobStatus.CANCELLED) {
            throw new IllegalStateException("Terminal experiment job cannot be cancelled");
        }
        Instant now = clock.instant();
        jdbc.sql("""
                UPDATE experiment_job
                   SET status = 'CANCELLED', finished_at = :finishedAt,
                       failure_code = 'ADMIN_CANCELLED', failure_message = :reason,
                       claim_token = NULL, claimed_by = NULL, claimed_at = NULL, lease_expires_at = NULL
                 WHERE job_id = :jobId
                """)
            .param("finishedAt", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
            .param("reason", reason)
            .param("jobId", jobId)
            .update();
        audit.record("EXPERIMENT_JOB_CANCELLED", "EXPERIMENT_JOB", jobId.toString(), Map.of(
            "reason", reason,
            "backendValidationResult", "ACCEPTED",
            "canAuthorizeCapital", false));
        return byId(jobId);
    }

    @Transactional
    public ResultView complete(UUID jobId, UUID claimToken, ResultInput input) {
        requireIds(jobId, claimToken);
        validateResult(input);
        ClaimState state = claimStateForUpdate(jobId);
        requireClaim(state, claimToken, JobStatus.RUNNING, true);

        JobView job = byId(jobId);
        ExperimentSpecService.ExperimentSpecView experiment = experiments.byId(job.experimentId());
        if (!job.specFingerprint().equals(experiment.specFingerprint())) {
            throw new IllegalStateException("Experiment job/spec fingerprint mismatch");
        }
        if (resultByJob(jobId).isPresent()) {
            throw new IllegalStateException("Immutable result already exists for experiment job " + jobId);
        }

        String artifact = normalizeHash(input.resultArtifactFingerprint(), "resultArtifactFingerprint");
        String metrics = normalizeHash(input.metricsFingerprint(), "metricsFingerprint");
        String evidence = normalizeHash(input.evidenceFingerprint(), "evidenceFingerprint");
        String resultFingerprint = ModelRegistryService.fingerprint(
            RESULT_CONTRACT_VERSION,
            job.orchestrationFingerprint(),
            experiment.modelVersionId().toString(),
            input.assessment().name(),
            Long.toString(input.sampleCount()),
            artifact,
            metrics,
            evidence);
        UUID resultId = UUID.randomUUID();
        Instant now = clock.instant();
        String actor = currentActor();

        jdbc.sql("""
                INSERT INTO experiment_result (
                    result_id, contract_version, job_id, experiment_id, model_version_id,
                    spec_fingerprint, orchestration_fingerprint, assessment, sample_count,
                    result_artifact_fingerprint, metrics_fingerprint, evidence_fingerprint,
                    result_fingerprint, created_at, created_by, can_authorize_capital)
                VALUES (
                    :resultId, :contractVersion, :jobId, :experimentId, :modelVersionId,
                    :specFingerprint, :orchestrationFingerprint, :assessment, :sampleCount,
                    :artifactFingerprint, :metricsFingerprint, :evidenceFingerprint,
                    :resultFingerprint, :createdAt, :createdBy, FALSE)
                """)
            .param("resultId", resultId)
            .param("contractVersion", RESULT_CONTRACT_VERSION)
            .param("jobId", jobId)
            .param("experimentId", experiment.experimentId())
            .param("modelVersionId", experiment.modelVersionId())
            .param("specFingerprint", experiment.specFingerprint())
            .param("orchestrationFingerprint", job.orchestrationFingerprint())
            .param("assessment", input.assessment().name())
            .param("sampleCount", input.sampleCount())
            .param("artifactFingerprint", artifact)
            .param("metricsFingerprint", metrics)
            .param("evidenceFingerprint", evidence)
            .param("resultFingerprint", resultFingerprint)
            .param("createdAt", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
            .param("createdBy", actor)
            .update();

        jdbc.sql("""
                UPDATE experiment_job
                   SET status = 'SUCCEEDED', finished_at = :finishedAt,
                       claim_token = NULL, claimed_by = NULL, claimed_at = NULL, lease_expires_at = NULL
                 WHERE job_id = :jobId
                """)
            .param("finishedAt", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
            .param("jobId", jobId)
            .update();

        audit.record("EXPERIMENT_RESULT_REGISTERED", "EXPERIMENT_RESULT", resultId.toString(), Map.of(
            "jobId", jobId.toString(),
            "experimentId", experiment.experimentId().toString(),
            "modelVersionId", experiment.modelVersionId().toString(),
            "assessment", input.assessment().name(),
            "sampleCount", input.sampleCount(),
            "resultFingerprint", resultFingerprint,
            "canAuthorizeCapital", false));
        audit.record("EXPERIMENT_JOB_SUCCEEDED", "EXPERIMENT_JOB", jobId.toString(), Map.of(
            "resultId", resultId.toString(),
            "resultFingerprint", resultFingerprint,
            "canAuthorizeCapital", false));
        return resultByJob(jobId)
            .orElseThrow(() -> new IllegalStateException("Registered experiment result cannot be read back"));
    }

    public JobView byId(UUID jobId) {
        if (jobId == null) throw new IllegalArgumentException("jobId is required");
        return jdbc.sql(jobSelect() + " WHERE job_id = :jobId")
            .param("jobId", jobId)
            .query((rs, rowNum) -> mapJob(rs))
            .optional()
            .orElseThrow(() -> new NoSuchElementException("Unknown experiment job: " + jobId));
    }

    public List<JobView> list(UUID experimentId, JobStatus status) {
        StringBuilder sql = new StringBuilder(jobSelect());
        List<String> clauses = new ArrayList<>();
        if (experimentId != null) clauses.add("experiment_id = :experimentId");
        if (status != null) clauses.add("status = :status");
        if (!clauses.isEmpty()) sql.append(" WHERE ").append(String.join(" AND ", clauses));
        sql.append(" ORDER BY created_at DESC, job_id DESC LIMIT 200");
        JdbcClient.StatementSpec statement = jdbc.sql(sql.toString());
        if (experimentId != null) statement.param("experimentId", experimentId);
        if (status != null) statement.param("status", status.name());
        return statement.query((rs, rowNum) -> mapJob(rs)).list();
    }

    public Optional<ResultView> resultByJob(UUID jobId) {
        if (jobId == null) throw new IllegalArgumentException("jobId is required");
        return jdbc.sql(resultSelect() + " WHERE job_id = :jobId")
            .param("jobId", jobId)
            .query((rs, rowNum) -> mapResult(rs))
            .optional();
    }

    public List<ResultView> results(UUID experimentId, UUID modelVersionId) {
        StringBuilder sql = new StringBuilder(resultSelect());
        List<String> clauses = new ArrayList<>();
        if (experimentId != null) clauses.add("experiment_id = :experimentId");
        if (modelVersionId != null) clauses.add("model_version_id = :modelVersionId");
        if (!clauses.isEmpty()) sql.append(" WHERE ").append(String.join(" AND ", clauses));
        sql.append(" ORDER BY created_at DESC, result_id DESC LIMIT 200");
        JdbcClient.StatementSpec statement = jdbc.sql(sql.toString());
        if (experimentId != null) statement.param("experimentId", experimentId);
        if (modelVersionId != null) statement.param("modelVersionId", modelVersionId);
        return statement.query((rs, rowNum) -> mapResult(rs)).list();
    }

    private Optional<JobView> byOrchestrationFingerprint(String fingerprint) {
        return jdbc.sql(jobSelect() + " WHERE orchestration_fingerprint = :fingerprint")
            .param("fingerprint", fingerprint)
            .query((rs, rowNum) -> mapJob(rs))
            .optional();
    }

    private ClaimState claimStateForUpdate(UUID jobId) {
        return jdbc.sql("""
                SELECT job_id, experiment_id, status, attempt, max_attempts, claim_token, lease_expires_at
                  FROM experiment_job
                 WHERE job_id = :jobId
                 FOR UPDATE
                """)
            .param("jobId", jobId)
            .query((rs, rowNum) -> {
                OffsetDateTime lease = rs.getObject("lease_expires_at", OffsetDateTime.class);
                return new ClaimState(
                    rs.getObject("job_id", UUID.class),
                    rs.getObject("experiment_id", UUID.class),
                    JobStatus.valueOf(rs.getString("status")),
                    rs.getInt("attempt"),
                    rs.getInt("max_attempts"),
                    rs.getObject("claim_token", UUID.class),
                    lease == null ? null : lease.toInstant());
            })
            .optional()
            .orElseThrow(() -> new NoSuchElementException("Unknown experiment job: " + jobId));
    }

    private void requireClaim(ClaimState state, UUID claimToken, JobStatus requiredStatus, boolean requireUnexpired) {
        if (state.status() != requiredStatus) {
            throw new IllegalStateException("Experiment job must be " + requiredStatus + " but is " + state.status());
        }
        requireClaimToken(state, claimToken, requireUnexpired);
    }

    private void requireClaimToken(ClaimState state, UUID claimToken, boolean requireUnexpired) {
        if (state.claimToken() == null || !state.claimToken().equals(claimToken)) {
            throw new IllegalStateException("Experiment job claim token does not match active lease");
        }
        if (requireUnexpired && (state.leaseExpiresAt() == null || !state.leaseExpiresAt().isAfter(clock.instant()))) {
            throw new IllegalStateException("Experiment job worker lease has expired");
        }
    }

    private static void validateCreate(CreateJobInput input) {
        if (input == null) throw new IllegalArgumentException("Experiment job request is required");
        if (input.experimentId() == null) throw new IllegalArgumentException("experimentId is required");
        if (input.runnerType() == null) throw new IllegalArgumentException("runnerType is required");
        if (input.runnerVersion() == null || input.runnerVersion().isBlank()
                || input.runnerVersion().trim().length() > 120) {
            throw new IllegalArgumentException("runnerVersion is required and must be <= 120 characters");
        }
        if (input.maxAttempts() < 1 || input.maxAttempts() > 10) {
            throw new IllegalArgumentException("maxAttempts must be between 1 and 10");
        }
    }

    private static void validateResult(ResultInput input) {
        if (input == null) throw new IllegalArgumentException("Experiment result is required");
        if (input.assessment() == null) throw new IllegalArgumentException("assessment is required");
        if (input.sampleCount() < 0) throw new IllegalArgumentException("sampleCount must be non-negative");
        normalizeHash(input.resultArtifactFingerprint(), "resultArtifactFingerprint");
        normalizeHash(input.metricsFingerprint(), "metricsFingerprint");
        normalizeHash(input.evidenceFingerprint(), "evidenceFingerprint");
    }

    private static String normalizeHash(String value, String field) {
        if (value == null) throw new IllegalArgumentException(field + " is required");
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches(HASH64)) {
            throw new IllegalArgumentException(field + " must be a 64-character SHA-256 hex fingerprint");
        }
        return normalized;
    }

    private static String normalizeWorkerId(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("workerId is required");
        String normalized = value.trim();
        if (normalized.length() > 120 || !normalized.matches("^[A-Za-z0-9][A-Za-z0-9._:-]{0,119}$")) {
            throw new IllegalArgumentException("workerId must be a bounded service identifier");
        }
        return normalized;
    }

    private static String normalizeFailureCode(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("failureCode is required");
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (normalized.length() > 80 || !normalized.matches("^[A-Z0-9][A-Z0-9_:-]{0,79}$")) {
            throw new IllegalArgumentException("failureCode must be <= 80 safe characters");
        }
        return normalized;
    }

    private static String normalizeFailureMessage(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("reason/message is required");
        String normalized = value.trim();
        if (normalized.length() > 512) throw new IllegalArgumentException("reason/message exceeds 512 characters");
        return normalized;
    }

    private static void requireIds(UUID jobId, UUID claimToken) {
        if (jobId == null) throw new IllegalArgumentException("jobId is required");
        if (claimToken == null) throw new IllegalArgumentException("claimToken is required");
    }

    private static String jobSelect() {
        return """
                SELECT contract_version, job_id, experiment_id, spec_fingerprint,
                       runner_type, runner_version, orchestration_fingerprint,
                       status, attempt, max_attempts, claim_token, claimed_by, claimed_at,
                       lease_expires_at, started_at, finished_at, failure_code, failure_message,
                       created_at, created_by, can_authorize_capital
                  FROM experiment_job
                """;
    }

    private static String resultSelect() {
        return """
                SELECT contract_version, result_id, job_id, experiment_id, model_version_id,
                       spec_fingerprint, orchestration_fingerprint, assessment, sample_count,
                       result_artifact_fingerprint, metrics_fingerprint, evidence_fingerprint,
                       result_fingerprint, created_at, created_by, can_authorize_capital
                  FROM experiment_result
                """;
    }

    private static JobView mapJob(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new JobView(
            rs.getString("contract_version"),
            rs.getObject("job_id", UUID.class),
            rs.getObject("experiment_id", UUID.class),
            rs.getString("spec_fingerprint"),
            RunnerType.valueOf(rs.getString("runner_type")),
            rs.getString("runner_version"),
            rs.getString("orchestration_fingerprint"),
            JobStatus.valueOf(rs.getString("status")),
            rs.getInt("attempt"),
            rs.getInt("max_attempts"),
            rs.getObject("claim_token", UUID.class),
            rs.getString("claimed_by"),
            instant(rs, "claimed_at"),
            instant(rs, "lease_expires_at"),
            instant(rs, "started_at"),
            instant(rs, "finished_at"),
            rs.getString("failure_code"),
            rs.getString("failure_message"),
            instant(rs, "created_at"),
            rs.getString("created_by"),
            rs.getBoolean("can_authorize_capital"));
    }

    private static ResultView mapResult(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ResultView(
            rs.getString("contract_version"),
            rs.getObject("result_id", UUID.class),
            rs.getObject("job_id", UUID.class),
            rs.getObject("experiment_id", UUID.class),
            rs.getObject("model_version_id", UUID.class),
            rs.getString("spec_fingerprint"),
            rs.getString("orchestration_fingerprint"),
            ResultAssessment.valueOf(rs.getString("assessment")),
            rs.getLong("sample_count"),
            rs.getString("result_artifact_fingerprint"),
            rs.getString("metrics_fingerprint"),
            rs.getString("evidence_fingerprint"),
            rs.getString("result_fingerprint"),
            instant(rs, "created_at"),
            rs.getString("created_by"),
            rs.getBoolean("can_authorize_capital"));
    }

    private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static String currentActor() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken
                || authentication.getName() == null || authentication.getName().isBlank()) {
            throw new IllegalStateException("Authenticated ADMIN actor is required for experiment orchestration");
        }
        String actor = authentication.getName().trim();
        if (actor.length() > 120) throw new IllegalStateException("Authenticated actor identity exceeds 120 characters");
        return actor;
    }
}
