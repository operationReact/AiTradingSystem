package in.aitradingsystem.governance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.aitradingsystem.audit.AuditService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Immutable V4 side-by-side comparison registry.
 *
 * A comparison is accepted only when every candidate result is bound to the same causal experiment
 * cohort and the supplied structured metrics hash to the immutable metrics fingerprint recorded on
 * experiment-result.v1. Comparisons are research/governance evidence only and never grant promotion
 * or capital authority.
 */
@Service
public class BacktestComparisonService {
    public static final String CONTRACT_VERSION = "backtest-comparison.v1";
    public static final String METRICS_CONTRACT_VERSION = "backtest-comparison-metrics.v1";
    private static final String CANDIDATE_CONTRACT_VERSION = "backtest-comparison-candidate.v1";

    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal THOUSAND = new BigDecimal("1000");
    private static final BigDecimal BILLION = new BigDecimal("1000000000");
    private static final BigDecimal TRILLION = new BigDecimal("1000000000000");
    private static final BigDecimal QUADRILLION = new BigDecimal("1000000000000000");
    private static final BigDecimal QUINTILLION = new BigDecimal("1000000000000000000");

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final AuditService audit;
    private final Clock clock;
    private final ExperimentSpecService experiments;

    public BacktestComparisonService(
            JdbcClient jdbc,
            ObjectMapper mapper,
            AuditService audit,
            Clock clock,
            ExperimentSpecService experiments) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.audit = audit;
        this.clock = clock;
        this.experiments = experiments;
    }

    /**
     * Structured cross-engine metric envelope. Percentage fields use percentage points; calibration,
     * precision and coverage fields use [0,1] fractions. Nullable fields support role-specific models.
     */
    public record ComparisonMetrics(
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
        BigDecimal coverageRate) {}

    public record CandidateInput(UUID resultId, ComparisonMetrics metrics) {}

    public record CreateComparisonInput(List<CandidateInput> candidates) {}

    public record CandidateView(
        int candidateOrder,
        UUID resultId,
        UUID experimentId,
        UUID modelVersionId,
        String modelKey,
        String versionLabel,
        String assessment,
        long sampleCount,
        String metricsFingerprint,
        ComparisonMetrics metrics,
        String candidateFingerprint) {}

    public record ComparisonView(
        String contractVersion,
        UUID comparisonId,
        Instant fromAt,
        Instant toAt,
        long seed,
        String datasetBindingFingerprint,
        String targetBindingFingerprint,
        String foldBindingFingerprint,
        String economicCostBindingFingerprint,
        String comparisonFingerprint,
        List<CandidateView> candidates,
        Instant createdAt,
        String createdBy,
        boolean promotionAllowed,
        boolean canAuthorizeCapital) {}

    public record ComparisonSummary(
        String contractVersion,
        UUID comparisonId,
        String comparisonFingerprint,
        int candidateCount,
        Instant fromAt,
        Instant toAt,
        long seed,
        Instant createdAt,
        String createdBy,
        boolean promotionAllowed,
        boolean canAuthorizeCapital) {}

    public record CreateComparisonResult(ComparisonView comparison, boolean created) {}

    private record ResultEvidence(
        UUID resultId,
        UUID experimentId,
        UUID modelVersionId,
        String modelKey,
        String versionLabel,
        String specFingerprint,
        String assessment,
        long sampleCount,
        String metricsFingerprint,
        String resultFingerprint) {}

    private record ValidatedCandidate(
        ResultEvidence result,
        ExperimentSpecService.ExperimentSpecView experiment,
        ComparisonMetrics metrics,
        String metricsFingerprint,
        String candidateFingerprint) {}

    private record ComparisonHeader(
        UUID comparisonId,
        Instant fromAt,
        Instant toAt,
        long seed,
        String datasetBindingFingerprint,
        String targetBindingFingerprint,
        String foldBindingFingerprint,
        String economicCostBindingFingerprint,
        String comparisonFingerprint,
        Instant createdAt,
        String createdBy) {}

    @Transactional
    public CreateComparisonResult create(CreateComparisonInput input) {
        List<CandidateInput> requested = validateInput(input);
        List<ValidatedCandidate> candidates = new ArrayList<>();
        for (CandidateInput candidate : requested) {
            ResultEvidence result = resultById(candidate.resultId());
            ExperimentSpecService.ExperimentSpecView experiment = experiments.byId(result.experimentId());
            if (!result.modelVersionId().equals(experiment.modelVersionId())) {
                throw new IllegalStateException("Result/model version does not match immutable experiment spec");
            }
            if (!result.specFingerprint().equals(experiment.specFingerprint())) {
                throw new IllegalStateException("Result/spec fingerprint does not match immutable experiment spec");
            }
            String suppliedMetricsFingerprint = metricsFingerprint(candidate.metrics());
            if (!suppliedMetricsFingerprint.equals(result.metricsFingerprint())) {
                throw new IllegalStateException(
                    "Structured comparison metrics do not match immutable experiment-result metrics fingerprint for result "
                        + result.resultId());
            }
            String candidateFingerprint = ModelRegistryService.fingerprint(
                CANDIDATE_CONTRACT_VERSION,
                result.resultId().toString(),
                result.modelVersionId().toString(),
                result.resultFingerprint(),
                suppliedMetricsFingerprint);
            candidates.add(new ValidatedCandidate(
                result, experiment, candidate.metrics(), suppliedMetricsFingerprint, candidateFingerprint));
        }

        candidates.sort((left, right) -> left.result().resultId().toString()
            .compareTo(right.result().resultId().toString()));
        ExperimentSpecService.ExperimentSpecView baseline = candidates.getFirst().experiment();
        for (ValidatedCandidate candidate : candidates) {
            requireComparableCohort(baseline, candidate.experiment());
        }

        List<String> fingerprintParts = new ArrayList<>();
        fingerprintParts.add(CONTRACT_VERSION);
        fingerprintParts.add(baseline.fromAt().toString());
        fingerprintParts.add(baseline.toAt().toString());
        fingerprintParts.add(Long.toString(baseline.seed()));
        fingerprintParts.add(baseline.datasetBindingFingerprint());
        fingerprintParts.add(baseline.targetBindingFingerprint());
        fingerprintParts.add(baseline.foldBindingFingerprint());
        fingerprintParts.add(baseline.economicCostBindingFingerprint());
        for (ValidatedCandidate candidate : candidates) {
            fingerprintParts.add(candidate.result().resultId().toString());
            fingerprintParts.add(candidate.result().resultFingerprint());
            fingerprintParts.add(candidate.metricsFingerprint());
        }
        String comparisonFingerprint = ModelRegistryService.fingerprint(
            fingerprintParts.toArray(String[]::new));

        Optional<UUID> existing = jdbc.sql("""
                SELECT comparison_id
                  FROM backtest_comparison
                 WHERE comparison_fingerprint = :fingerprint
                """)
            .param("fingerprint", comparisonFingerprint)
            .query(UUID.class)
            .optional();
        if (existing.isPresent()) {
            return new CreateComparisonResult(byId(existing.get()), false);
        }

        UUID comparisonId = UUID.randomUUID();
        Instant now = clock.instant();
        String actor = currentActor();
        jdbc.sql("""
                INSERT INTO backtest_comparison (
                    comparison_id, contract_version, from_at, to_at, seed,
                    dataset_binding_fingerprint, target_binding_fingerprint,
                    fold_binding_fingerprint, economic_cost_binding_fingerprint,
                    comparison_fingerprint, created_at, created_by,
                    promotion_allowed, can_authorize_capital)
                VALUES (
                    :comparisonId, :contractVersion, :fromAt, :toAt, :seed,
                    :datasetFingerprint, :targetFingerprint, :foldFingerprint, :costFingerprint,
                    :comparisonFingerprint, :createdAt, :createdBy, FALSE, FALSE)
                """)
            .param("comparisonId", comparisonId)
            .param("contractVersion", CONTRACT_VERSION)
            .param("fromAt", OffsetDateTime.ofInstant(baseline.fromAt(), ZoneOffset.UTC))
            .param("toAt", OffsetDateTime.ofInstant(baseline.toAt(), ZoneOffset.UTC))
            .param("seed", baseline.seed())
            .param("datasetFingerprint", baseline.datasetBindingFingerprint())
            .param("targetFingerprint", baseline.targetBindingFingerprint())
            .param("foldFingerprint", baseline.foldBindingFingerprint())
            .param("costFingerprint", baseline.economicCostBindingFingerprint())
            .param("comparisonFingerprint", comparisonFingerprint)
            .param("createdAt", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
            .param("createdBy", actor)
            .update();

        for (int index = 0; index < candidates.size(); index++) {
            ValidatedCandidate candidate = candidates.get(index);
            jdbc.sql("""
                    INSERT INTO backtest_comparison_candidate (
                        comparison_id, result_id, model_version_id, candidate_order,
                        assessment, sample_count, metrics_fingerprint, metrics_json,
                        candidate_fingerprint)
                    VALUES (
                        :comparisonId, :resultId, :modelVersionId, :candidateOrder,
                        :assessment, :sampleCount, :metricsFingerprint, CAST(:metricsJson AS jsonb),
                        :candidateFingerprint)
                    """)
                .param("comparisonId", comparisonId)
                .param("resultId", candidate.result().resultId())
                .param("modelVersionId", candidate.result().modelVersionId())
                .param("candidateOrder", index)
                .param("assessment", candidate.result().assessment())
                .param("sampleCount", candidate.result().sampleCount())
                .param("metricsFingerprint", candidate.metricsFingerprint())
                .param("metricsJson", toJson(candidate.metrics()))
                .param("candidateFingerprint", candidate.candidateFingerprint())
                .update();
        }

        audit.record("BACKTEST_COMPARISON_REGISTERED", "BACKTEST_COMPARISON", comparisonId.toString(), Map.of(
            "candidateCount", candidates.size(),
            "datasetBindingFingerprint", baseline.datasetBindingFingerprint(),
            "targetBindingFingerprint", baseline.targetBindingFingerprint(),
            "foldBindingFingerprint", baseline.foldBindingFingerprint(),
            "economicCostBindingFingerprint", baseline.economicCostBindingFingerprint(),
            "comparisonFingerprint", comparisonFingerprint,
            "promotionAllowed", false,
            "canAuthorizeCapital", false));
        return new CreateComparisonResult(byId(comparisonId), true);
    }

    public ComparisonView byId(UUID comparisonId) {
        if (comparisonId == null) throw new IllegalArgumentException("comparisonId is required");
        ComparisonHeader header = jdbc.sql("""
                SELECT comparison_id, from_at, to_at, seed,
                       dataset_binding_fingerprint, target_binding_fingerprint,
                       fold_binding_fingerprint, economic_cost_binding_fingerprint,
                       comparison_fingerprint, created_at, created_by
                  FROM backtest_comparison
                 WHERE comparison_id = :comparisonId
                """)
            .param("comparisonId", comparisonId)
            .query((rs, rowNum) -> new ComparisonHeader(
                rs.getObject("comparison_id", UUID.class),
                rs.getObject("from_at", OffsetDateTime.class).toInstant(),
                rs.getObject("to_at", OffsetDateTime.class).toInstant(),
                rs.getLong("seed"),
                rs.getString("dataset_binding_fingerprint"),
                rs.getString("target_binding_fingerprint"),
                rs.getString("fold_binding_fingerprint"),
                rs.getString("economic_cost_binding_fingerprint"),
                rs.getString("comparison_fingerprint"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getString("created_by")))
            .optional()
            .orElseThrow(() -> new NoSuchElementException("Unknown backtest comparison: " + comparisonId));

        List<CandidateView> candidates = jdbc.sql("""
                SELECT c.candidate_order, c.result_id, r.experiment_id, c.model_version_id,
                       d.model_key, v.version_label, c.assessment, c.sample_count,
                       c.metrics_fingerprint, c.metrics_json, c.candidate_fingerprint
                  FROM backtest_comparison_candidate c
                  JOIN experiment_result r ON r.result_id = c.result_id
                  JOIN model_version v ON v.version_id = c.model_version_id
                  JOIN model_definition d ON d.definition_id = v.definition_id
                 WHERE c.comparison_id = :comparisonId
                 ORDER BY c.candidate_order
                """)
            .param("comparisonId", comparisonId)
            .query((rs, rowNum) -> {
                try {
                    return new CandidateView(
                        rs.getInt("candidate_order"),
                        rs.getObject("result_id", UUID.class),
                        rs.getObject("experiment_id", UUID.class),
                        rs.getObject("model_version_id", UUID.class),
                        rs.getString("model_key"),
                        rs.getString("version_label"),
                        rs.getString("assessment"),
                        rs.getLong("sample_count"),
                        rs.getString("metrics_fingerprint"),
                        mapper.readValue(rs.getString("metrics_json"), ComparisonMetrics.class),
                        rs.getString("candidate_fingerprint"));
                } catch (JsonProcessingException ex) {
                    throw new java.sql.SQLException("Stored comparison metrics are not readable", ex);
                }
            })
            .list();

        if (candidates.size() < 2) {
            throw new IllegalStateException("Stored backtest comparison has fewer than two candidates");
        }
        return new ComparisonView(
            CONTRACT_VERSION,
            header.comparisonId(),
            header.fromAt(),
            header.toAt(),
            header.seed(),
            header.datasetBindingFingerprint(),
            header.targetBindingFingerprint(),
            header.foldBindingFingerprint(),
            header.economicCostBindingFingerprint(),
            header.comparisonFingerprint(),
            List.copyOf(candidates),
            header.createdAt(),
            header.createdBy(),
            false,
            false);
    }

    public List<ComparisonSummary> list() {
        return jdbc.sql("""
                SELECT b.comparison_id, b.contract_version, b.comparison_fingerprint,
                       count(c.result_id) AS candidate_count,
                       b.from_at, b.to_at, b.seed, b.created_at, b.created_by
                  FROM backtest_comparison b
                  JOIN backtest_comparison_candidate c ON c.comparison_id = b.comparison_id
                 GROUP BY b.comparison_id, b.contract_version, b.comparison_fingerprint,
                          b.from_at, b.to_at, b.seed, b.created_at, b.created_by
                 ORDER BY b.created_at DESC, b.comparison_id DESC
                 LIMIT 100
                """)
            .query((rs, rowNum) -> new ComparisonSummary(
                rs.getString("contract_version"),
                rs.getObject("comparison_id", UUID.class),
                rs.getString("comparison_fingerprint"),
                rs.getInt("candidate_count"),
                rs.getObject("from_at", OffsetDateTime.class).toInstant(),
                rs.getObject("to_at", OffsetDateTime.class).toInstant(),
                rs.getLong("seed"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getString("created_by"),
                false,
                false))
            .list();
    }

    public static String metricsFingerprint(ComparisonMetrics metrics) {
        validateMetrics(metrics);
        return ModelRegistryService.fingerprint(
            METRICS_CONTRACT_VERSION,
            decimal(metrics.netPnl()),
            decimal(metrics.totalReturnPercent()),
            decimal(metrics.sharpeRatio()),
            decimal(metrics.sortinoRatio()),
            decimal(metrics.maxDrawdownPercent()),
            decimal(metrics.winRatePercent()),
            decimal(metrics.profitFactor()),
            decimal(metrics.expectancy()),
            decimal(metrics.turnover()),
            decimal(metrics.transactionCosts()),
            decimal(metrics.brierScore()),
            decimal(metrics.logLoss()),
            decimal(metrics.expectedCalibrationError()),
            decimal(metrics.precisionAtK()),
            decimal(metrics.netReturnAtK()),
            decimal(metrics.coverageRate()));
    }

    private ResultEvidence resultById(UUID resultId) {
        return jdbc.sql("""
                SELECT r.result_id, r.experiment_id, r.model_version_id,
                       d.model_key, v.version_label, r.spec_fingerprint,
                       r.assessment, r.sample_count, r.metrics_fingerprint, r.result_fingerprint
                  FROM experiment_result r
                  JOIN model_version v ON v.version_id = r.model_version_id
                  JOIN model_definition d ON d.definition_id = v.definition_id
                 WHERE r.result_id = :resultId
                """)
            .param("resultId", resultId)
            .query((rs, rowNum) -> new ResultEvidence(
                rs.getObject("result_id", UUID.class),
                rs.getObject("experiment_id", UUID.class),
                rs.getObject("model_version_id", UUID.class),
                rs.getString("model_key"),
                rs.getString("version_label"),
                rs.getString("spec_fingerprint"),
                rs.getString("assessment"),
                rs.getLong("sample_count"),
                rs.getString("metrics_fingerprint"),
                rs.getString("result_fingerprint")))
            .optional()
            .orElseThrow(() -> new NoSuchElementException("Unknown experiment result: " + resultId));
    }

    private static List<CandidateInput> validateInput(CreateComparisonInput input) {
        if (input == null || input.candidates() == null) {
            throw new IllegalArgumentException("Comparison candidates are required");
        }
        if (input.candidates().size() < 2 || input.candidates().size() > 20) {
            throw new IllegalArgumentException("Backtest comparison requires between 2 and 20 candidates");
        }
        Set<UUID> resultIds = new HashSet<>();
        List<CandidateInput> validated = new ArrayList<>();
        for (CandidateInput candidate : input.candidates()) {
            if (candidate == null || candidate.resultId() == null) {
                throw new IllegalArgumentException("Each comparison candidate requires resultId");
            }
            if (!resultIds.add(candidate.resultId())) {
                throw new IllegalArgumentException("Comparison cannot contain duplicate experiment results");
            }
            validateMetrics(candidate.metrics());
            validated.add(candidate);
        }
        return List.copyOf(validated);
    }

    private static void requireComparableCohort(
            ExperimentSpecService.ExperimentSpecView baseline,
            ExperimentSpecService.ExperimentSpecView candidate) {
        if (!baseline.fromAt().equals(candidate.fromAt())
                || !baseline.toAt().equals(candidate.toAt())
                || baseline.seed() != candidate.seed()
                || !baseline.datasetBindingFingerprint().equals(candidate.datasetBindingFingerprint())
                || !baseline.targetBindingFingerprint().equals(candidate.targetBindingFingerprint())
                || !baseline.foldBindingFingerprint().equals(candidate.foldBindingFingerprint())
                || !baseline.economicCostBindingFingerprint().equals(candidate.economicCostBindingFingerprint())) {
            throw new IllegalStateException(
                "Side-by-side candidates must share the exact window, seed, dataset, target, folds and economic-cost bindings");
        }
    }

    private static void validateMetrics(ComparisonMetrics metrics) {
        if (metrics == null) throw new IllegalArgumentException("Structured comparison metrics are required");
        if (allNull(metrics)) {
            throw new IllegalArgumentException("At least one structured comparison metric is required");
        }
        bounded(metrics.netPnl(), QUADRILLION.negate(), QUADRILLION, "netPnl");
        bounded(metrics.totalReturnPercent(), HUNDRED.negate(), BILLION, "totalReturnPercent");
        bounded(metrics.sharpeRatio(), THOUSAND.negate(), THOUSAND, "sharpeRatio");
        bounded(metrics.sortinoRatio(), THOUSAND.negate(), THOUSAND, "sortinoRatio");
        bounded(metrics.maxDrawdownPercent(), BigDecimal.ZERO, HUNDRED, "maxDrawdownPercent");
        bounded(metrics.winRatePercent(), BigDecimal.ZERO, HUNDRED, "winRatePercent");
        bounded(metrics.profitFactor(), BigDecimal.ZERO, BILLION, "profitFactor");
        bounded(metrics.expectancy(), TRILLION.negate(), TRILLION, "expectancy");
        bounded(metrics.turnover(), BigDecimal.ZERO, QUINTILLION, "turnover");
        bounded(metrics.transactionCosts(), BigDecimal.ZERO, QUADRILLION, "transactionCosts");
        bounded(metrics.brierScore(), BigDecimal.ZERO, ONE, "brierScore");
        bounded(metrics.logLoss(), BigDecimal.ZERO, THOUSAND, "logLoss");
        bounded(metrics.expectedCalibrationError(), BigDecimal.ZERO, ONE, "expectedCalibrationError");
        bounded(metrics.precisionAtK(), BigDecimal.ZERO, ONE, "precisionAtK");
        bounded(metrics.netReturnAtK(), ONE.negate(), BILLION, "netReturnAtK");
        bounded(metrics.coverageRate(), BigDecimal.ZERO, ONE, "coverageRate");
    }

    private static boolean allNull(ComparisonMetrics metrics) {
        return metrics.netPnl() == null
            && metrics.totalReturnPercent() == null
            && metrics.sharpeRatio() == null
            && metrics.sortinoRatio() == null
            && metrics.maxDrawdownPercent() == null
            && metrics.winRatePercent() == null
            && metrics.profitFactor() == null
            && metrics.expectancy() == null
            && metrics.turnover() == null
            && metrics.transactionCosts() == null
            && metrics.brierScore() == null
            && metrics.logLoss() == null
            && metrics.expectedCalibrationError() == null
            && metrics.precisionAtK() == null
            && metrics.netReturnAtK() == null
            && metrics.coverageRate() == null;
    }

    private static void bounded(BigDecimal value, BigDecimal minimum, BigDecimal maximum, String field) {
        if (value == null) return;
        if (value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(field + " must be between " + minimum + " and " + maximum);
        }
    }

    private static String decimal(BigDecimal value) {
        if (value == null) return "";
        BigDecimal normalized = value.stripTrailingZeros();
        return normalized.signum() == 0 ? "0" : normalized.toPlainString();
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Comparison metrics cannot be serialized", ex);
        }
    }

    private static String currentActor() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken
                || authentication.getName() == null || authentication.getName().isBlank()) {
            throw new IllegalStateException("Authenticated ADMIN actor is required for backtest comparison registration");
        }
        String actor = authentication.getName().trim();
        if (actor.length() > 120) {
            throw new IllegalStateException("Authenticated actor identity exceeds 120 characters");
        }
        return actor;
    }
}
