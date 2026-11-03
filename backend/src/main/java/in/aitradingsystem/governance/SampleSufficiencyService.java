package in.aitradingsystem.governance;

import in.aitradingsystem.audit.AuditService;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
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
 * Immutable, versioned sample-sufficiency evaluation for V4 backtest comparisons.
 *
 * The effective assessment is derived from persisted experiment-result evidence. Callers provide only
 * the comparison and bounded minimum sample policy; they cannot assert PASS/FAIL/INCONCLUSIVE.
 */
@Service
public class SampleSufficiencyService {
    public static final String CONTRACT_VERSION = "sample-sufficiency.v1";
    private static final String CANDIDATE_VERSION = "sample-sufficiency-candidate.v1";
    private static final long MAXIMUM_SAMPLE_POLICY = 1_000_000_000L;

    private final JdbcClient jdbc;
    private final AuditService audit;
    private final Clock clock;

    public SampleSufficiencyService(JdbcClient jdbc, AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
    }

    public enum OverallStatus {
        SUFFICIENT,
        INCONCLUSIVE
    }

    public enum Assessment {
        PASS,
        FAIL,
        INCONCLUSIVE
    }

    public record CandidateEvaluation(
        int candidateOrder,
        UUID resultId,
        UUID modelVersionId,
        String modelKey,
        String versionLabel,
        long observedSampleCount,
        long minimumSampleCount,
        Assessment originalAssessment,
        Assessment effectiveAssessment,
        boolean sufficient,
        long shortfall,
        String evidenceFingerprint) {}

    public record EvaluationView(
        String contractVersion,
        UUID evaluationId,
        UUID comparisonId,
        String comparisonFingerprint,
        long minimumSampleCount,
        String policyFingerprint,
        OverallStatus overallStatus,
        String evaluationFingerprint,
        List<CandidateEvaluation> candidates,
        Instant createdAt,
        String createdBy,
        boolean promotionAllowed,
        boolean canAuthorizeCapital) {}

    public record EvaluationSummary(
        String contractVersion,
        UUID evaluationId,
        UUID comparisonId,
        String comparisonFingerprint,
        long minimumSampleCount,
        OverallStatus overallStatus,
        int candidateCount,
        String evaluationFingerprint,
        Instant createdAt,
        String createdBy,
        boolean promotionAllowed,
        boolean canAuthorizeCapital) {}

    public record CreateEvaluationResult(EvaluationView evaluation, boolean created) {}

    private record ComparisonHeader(UUID comparisonId, String comparisonFingerprint) {}

    private record CandidateEvidence(
        int candidateOrder,
        UUID resultId,
        UUID modelVersionId,
        String modelKey,
        String versionLabel,
        Assessment originalAssessment,
        long sampleCount,
        String resultFingerprint) {}

    private record EvaluationHeader(
        UUID evaluationId,
        UUID comparisonId,
        String comparisonFingerprint,
        long minimumSampleCount,
        String policyFingerprint,
        OverallStatus overallStatus,
        String evaluationFingerprint,
        Instant createdAt,
        String createdBy) {}

    @Transactional
    public CreateEvaluationResult evaluate(UUID comparisonId, long minimumSampleCount) {
        validatePolicy(comparisonId, minimumSampleCount);
        ComparisonHeader comparison = comparisonHeader(comparisonId);
        List<CandidateEvidence> sourceCandidates = comparisonCandidates(comparisonId);
        if (sourceCandidates.size() < 2) {
            throw new IllegalStateException("Sample sufficiency requires a valid comparison with at least two candidates");
        }

        String policyFingerprint = ModelRegistryService.fingerprint(
            CONTRACT_VERSION,
            "minimumSampleCount=" + minimumSampleCount);
        Optional<UUID> existing = jdbc.sql("""
                SELECT evaluation_id
                  FROM sample_sufficiency_evaluation
                 WHERE comparison_id = :comparisonId
                   AND policy_fingerprint = :policyFingerprint
                """)
            .param("comparisonId", comparisonId)
            .param("policyFingerprint", policyFingerprint)
            .query(UUID.class)
            .optional();
        if (existing.isPresent()) {
            return new CreateEvaluationResult(byId(existing.get()), false);
        }

        List<CandidateEvaluation> evaluated = sourceCandidates.stream()
            .map(candidate -> evaluateCandidate(comparison, candidate, minimumSampleCount))
            .toList();
        OverallStatus overallStatus = evaluated.stream()
            .anyMatch(candidate -> candidate.effectiveAssessment() == Assessment.INCONCLUSIVE)
                ? OverallStatus.INCONCLUSIVE
                : OverallStatus.SUFFICIENT;

        List<String> fingerprintParts = new ArrayList<>();
        fingerprintParts.add(CONTRACT_VERSION);
        fingerprintParts.add(comparison.comparisonFingerprint());
        fingerprintParts.add(policyFingerprint);
        fingerprintParts.add(overallStatus.name());
        evaluated.forEach(candidate -> fingerprintParts.add(candidate.evidenceFingerprint()));
        String evaluationFingerprint = ModelRegistryService.fingerprint(
            fingerprintParts.toArray(String[]::new));

        UUID evaluationId = UUID.randomUUID();
        Instant now = clock.instant();
        String actor = currentActor();
        try {
            jdbc.sql("""
                    INSERT INTO sample_sufficiency_evaluation (
                        evaluation_id, contract_version, comparison_id, comparison_fingerprint,
                        minimum_sample_count, policy_fingerprint, overall_status,
                        evaluation_fingerprint, created_at, created_by,
                        promotion_allowed, can_authorize_capital)
                    VALUES (
                        :evaluationId, :contractVersion, :comparisonId, :comparisonFingerprint,
                        :minimumSampleCount, :policyFingerprint, :overallStatus,
                        :evaluationFingerprint, :createdAt, :createdBy, FALSE, FALSE)
                    """)
                .param("evaluationId", evaluationId)
                .param("contractVersion", CONTRACT_VERSION)
                .param("comparisonId", comparisonId)
                .param("comparisonFingerprint", comparison.comparisonFingerprint())
                .param("minimumSampleCount", minimumSampleCount)
                .param("policyFingerprint", policyFingerprint)
                .param("overallStatus", overallStatus.name())
                .param("evaluationFingerprint", evaluationFingerprint)
                .param("createdAt", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .param("createdBy", actor)
                .update();

            for (CandidateEvaluation candidate : evaluated) {
                jdbc.sql("""
                        INSERT INTO sample_sufficiency_candidate (
                            evaluation_id, result_id, model_version_id, candidate_order,
                            observed_sample_count, minimum_sample_count,
                            original_assessment, effective_assessment,
                            sufficient, shortfall, evidence_fingerprint)
                        VALUES (
                            :evaluationId, :resultId, :modelVersionId, :candidateOrder,
                            :observedSampleCount, :minimumSampleCount,
                            :originalAssessment, :effectiveAssessment,
                            :sufficient, :shortfall, :evidenceFingerprint)
                        """)
                    .param("evaluationId", evaluationId)
                    .param("resultId", candidate.resultId())
                    .param("modelVersionId", candidate.modelVersionId())
                    .param("candidateOrder", candidate.candidateOrder())
                    .param("observedSampleCount", candidate.observedSampleCount())
                    .param("minimumSampleCount", candidate.minimumSampleCount())
                    .param("originalAssessment", candidate.originalAssessment().name())
                    .param("effectiveAssessment", candidate.effectiveAssessment().name())
                    .param("sufficient", candidate.sufficient())
                    .param("shortfall", candidate.shortfall())
                    .param("evidenceFingerprint", candidate.evidenceFingerprint())
                    .update();
            }
        } catch (DuplicateKeyException ex) {
            UUID duplicateId = jdbc.sql("""
                    SELECT evaluation_id
                      FROM sample_sufficiency_evaluation
                     WHERE comparison_id = :comparisonId
                       AND policy_fingerprint = :policyFingerprint
                    """)
                .param("comparisonId", comparisonId)
                .param("policyFingerprint", policyFingerprint)
                .query(UUID.class)
                .optional()
                .orElseThrow(() -> ex);
            return new CreateEvaluationResult(byId(duplicateId), false);
        }

        audit.record("SAMPLE_SUFFICIENCY_EVALUATED", "SAMPLE_SUFFICIENCY", evaluationId.toString(), Map.of(
            "comparisonId", comparisonId.toString(),
            "comparisonFingerprint", comparison.comparisonFingerprint(),
            "minimumSampleCount", minimumSampleCount,
            "overallStatus", overallStatus.name(),
            "inconclusiveCandidateCount", evaluated.stream()
                .filter(candidate -> candidate.effectiveAssessment() == Assessment.INCONCLUSIVE)
                .count(),
            "policyFingerprint", policyFingerprint,
            "evaluationFingerprint", evaluationFingerprint,
            "promotionAllowed", false,
            "canAuthorizeCapital", false));
        return new CreateEvaluationResult(byId(evaluationId), true);
    }

    public EvaluationView byId(UUID evaluationId) {
        if (evaluationId == null) throw new IllegalArgumentException("evaluationId is required");
        EvaluationHeader header = jdbc.sql("""
                SELECT evaluation_id, comparison_id, comparison_fingerprint,
                       minimum_sample_count, policy_fingerprint, overall_status,
                       evaluation_fingerprint, created_at, created_by
                  FROM sample_sufficiency_evaluation
                 WHERE evaluation_id = :evaluationId
                """)
            .param("evaluationId", evaluationId)
            .query((rs, rowNum) -> new EvaluationHeader(
                rs.getObject("evaluation_id", UUID.class),
                rs.getObject("comparison_id", UUID.class),
                rs.getString("comparison_fingerprint"),
                rs.getLong("minimum_sample_count"),
                rs.getString("policy_fingerprint"),
                OverallStatus.valueOf(rs.getString("overall_status")),
                rs.getString("evaluation_fingerprint"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getString("created_by")))
            .optional()
            .orElseThrow(() -> new NoSuchElementException("Unknown sample sufficiency evaluation: " + evaluationId));

        List<CandidateEvaluation> candidates = jdbc.sql("""
                SELECT s.candidate_order, s.result_id, s.model_version_id,
                       d.model_key, v.version_label,
                       s.observed_sample_count, s.minimum_sample_count,
                       s.original_assessment, s.effective_assessment,
                       s.sufficient, s.shortfall, s.evidence_fingerprint
                  FROM sample_sufficiency_candidate s
                  JOIN model_version v ON v.version_id = s.model_version_id
                  JOIN model_definition d ON d.definition_id = v.definition_id
                 WHERE s.evaluation_id = :evaluationId
                 ORDER BY s.candidate_order
                """)
            .param("evaluationId", evaluationId)
            .query((rs, rowNum) -> new CandidateEvaluation(
                rs.getInt("candidate_order"),
                rs.getObject("result_id", UUID.class),
                rs.getObject("model_version_id", UUID.class),
                rs.getString("model_key"),
                rs.getString("version_label"),
                rs.getLong("observed_sample_count"),
                rs.getLong("minimum_sample_count"),
                Assessment.valueOf(rs.getString("original_assessment")),
                Assessment.valueOf(rs.getString("effective_assessment")),
                rs.getBoolean("sufficient"),
                rs.getLong("shortfall"),
                rs.getString("evidence_fingerprint")))
            .list();
        if (candidates.size() < 2) {
            throw new IllegalStateException("Stored sample sufficiency evaluation has fewer than two candidates");
        }

        return new EvaluationView(
            CONTRACT_VERSION,
            header.evaluationId(),
            header.comparisonId(),
            header.comparisonFingerprint(),
            header.minimumSampleCount(),
            header.policyFingerprint(),
            header.overallStatus(),
            header.evaluationFingerprint(),
            List.copyOf(candidates),
            header.createdAt(),
            header.createdBy(),
            false,
            false);
    }

    public List<EvaluationSummary> list(UUID comparisonId) {
        String sql = """
                SELECT e.contract_version, e.evaluation_id, e.comparison_id,
                       e.comparison_fingerprint, e.minimum_sample_count,
                       e.overall_status, count(c.result_id) AS candidate_count,
                       e.evaluation_fingerprint, e.created_at, e.created_by
                  FROM sample_sufficiency_evaluation e
                  JOIN sample_sufficiency_candidate c ON c.evaluation_id = e.evaluation_id
                """ + (comparisonId == null ? "" : " WHERE e.comparison_id = :comparisonId") + """
                 GROUP BY e.contract_version, e.evaluation_id, e.comparison_id,
                          e.comparison_fingerprint, e.minimum_sample_count,
                          e.overall_status, e.evaluation_fingerprint,
                          e.created_at, e.created_by
                 ORDER BY e.created_at DESC, e.evaluation_id DESC
                 LIMIT 100
                """;
        JdbcClient.StatementSpec statement = jdbc.sql(sql);
        if (comparisonId != null) statement.param("comparisonId", comparisonId);
        return statement.query((rs, rowNum) -> new EvaluationSummary(
            rs.getString("contract_version"),
            rs.getObject("evaluation_id", UUID.class),
            rs.getObject("comparison_id", UUID.class),
            rs.getString("comparison_fingerprint"),
            rs.getLong("minimum_sample_count"),
            OverallStatus.valueOf(rs.getString("overall_status")),
            rs.getInt("candidate_count"),
            rs.getString("evaluation_fingerprint"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant(),
            rs.getString("created_by"),
            false,
            false)).list();
    }

    private CandidateEvaluation evaluateCandidate(
            ComparisonHeader comparison,
            CandidateEvidence candidate,
            long minimumSampleCount) {
        boolean sufficient = candidate.sampleCount() >= minimumSampleCount;
        long shortfall = sufficient ? 0 : minimumSampleCount - candidate.sampleCount();
        Assessment effective = sufficient ? candidate.originalAssessment() : Assessment.INCONCLUSIVE;
        String evidenceFingerprint = ModelRegistryService.fingerprint(
            CANDIDATE_VERSION,
            comparison.comparisonFingerprint(),
            candidate.resultId().toString(),
            candidate.resultFingerprint(),
            Long.toString(candidate.sampleCount()),
            Long.toString(minimumSampleCount),
            candidate.originalAssessment().name(),
            effective.name(),
            Boolean.toString(sufficient),
            Long.toString(shortfall));
        return new CandidateEvaluation(
            candidate.candidateOrder(),
            candidate.resultId(),
            candidate.modelVersionId(),
            candidate.modelKey(),
            candidate.versionLabel(),
            candidate.sampleCount(),
            minimumSampleCount,
            candidate.originalAssessment(),
            effective,
            sufficient,
            shortfall,
            evidenceFingerprint);
    }

    private ComparisonHeader comparisonHeader(UUID comparisonId) {
        return jdbc.sql("""
                SELECT comparison_id, comparison_fingerprint
                  FROM backtest_comparison
                 WHERE comparison_id = :comparisonId
                """)
            .param("comparisonId", comparisonId)
            .query((rs, rowNum) -> new ComparisonHeader(
                rs.getObject("comparison_id", UUID.class),
                rs.getString("comparison_fingerprint")))
            .optional()
            .orElseThrow(() -> new NoSuchElementException("Unknown backtest comparison: " + comparisonId));
    }

    private List<CandidateEvidence> comparisonCandidates(UUID comparisonId) {
        return jdbc.sql("""
                SELECT c.candidate_order, c.result_id, c.model_version_id,
                       d.model_key, v.version_label,
                       r.assessment, r.sample_count, r.result_fingerprint
                  FROM backtest_comparison_candidate c
                  JOIN experiment_result r ON r.result_id = c.result_id
                  JOIN model_version v ON v.version_id = c.model_version_id
                  JOIN model_definition d ON d.definition_id = v.definition_id
                 WHERE c.comparison_id = :comparisonId
                 ORDER BY c.candidate_order
                """)
            .param("comparisonId", comparisonId)
            .query((rs, rowNum) -> new CandidateEvidence(
                rs.getInt("candidate_order"),
                rs.getObject("result_id", UUID.class),
                rs.getObject("model_version_id", UUID.class),
                rs.getString("model_key"),
                rs.getString("version_label"),
                Assessment.valueOf(rs.getString("assessment")),
                rs.getLong("sample_count"),
                rs.getString("result_fingerprint")))
            .list();
    }

    private static void validatePolicy(UUID comparisonId, long minimumSampleCount) {
        if (comparisonId == null) throw new IllegalArgumentException("comparisonId is required");
        if (minimumSampleCount < 1 || minimumSampleCount > MAXIMUM_SAMPLE_POLICY) {
            throw new IllegalArgumentException(
                "minimumSampleCount must be between 1 and " + MAXIMUM_SAMPLE_POLICY);
        }
    }

    private static String currentActor() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken
                || authentication.getName() == null || authentication.getName().isBlank()) {
            throw new IllegalStateException("Authenticated ADMIN actor is required for sample sufficiency evaluation");
        }
        String actor = authentication.getName().trim();
        if (actor.length() > 120) {
            throw new IllegalStateException("Authenticated actor identity exceeds 120 characters");
        }
        return actor;
    }
}
