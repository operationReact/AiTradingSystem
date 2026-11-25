package in.aitradingsystem.governance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.aitradingsystem.audit.AuditService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Immutable V4 experiment specification registry. This service records reproducible intent only;
 * it does not execute models, place orders or grant capital authority.
 */
@Service
public class ExperimentSpecService {
    public static final String CONTRACT_VERSION = "experiment-spec.v1";
    private static final String HASH64 = "^[a-f0-9]{64}$";

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final AuditService audit;
    private final Clock clock;
    private final ModelEmergencyControlService emergencyControls;

    public ExperimentSpecService(
            JdbcClient jdbc,
            ObjectMapper mapper,
            AuditService audit,
            Clock clock,
            ModelEmergencyControlService emergencyControls) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.audit = audit;
        this.clock = clock;
        this.emergencyControls = emergencyControls;
    }

    public enum FoldProtocol {
        CHRONOLOGICAL,
        PURGED_WALK_FORWARD,
        CPCV
    }

    public record DatasetBinding(
        String datasetFingerprint,
        String featureSchemaFingerprint,
        String universeFingerprint,
        Instant asOf,
        boolean pointInTime) {}

    public record TargetBinding(
        String targetName,
        int horizonBars,
        String labelDefinitionFingerprint) {}

    public record FoldBinding(
        FoldProtocol protocol,
        int folds,
        int trainBars,
        int testBars,
        int purgeBars,
        int embargoBars) {}

    public record EconomicCostBinding(
        BigDecimal brokerageRate,
        BigDecimal slippageRate,
        BigDecimal spreadBps,
        BigDecimal statutoryCostRate) {}

    public record ExperimentSpecInput(
        UUID modelVersionId,
        Instant fromAt,
        Instant toAt,
        long seed,
        DatasetBinding dataset,
        TargetBinding target,
        FoldBinding folds,
        EconomicCostBinding economicCosts) {}

    public record ExperimentSpecView(
        String contractVersion,
        UUID experimentId,
        UUID modelVersionId,
        Instant fromAt,
        Instant toAt,
        long seed,
        DatasetBinding dataset,
        String datasetBindingFingerprint,
        TargetBinding target,
        String targetBindingFingerprint,
        FoldBinding folds,
        String foldBindingFingerprint,
        EconomicCostBinding economicCosts,
        String economicCostBindingFingerprint,
        String specFingerprint,
        Instant createdAt,
        String createdBy,
        boolean canAuthorizeCapital) {}

    @Transactional
    public ExperimentSpecView create(ExperimentSpecInput input) {
        validate(input);
        ensureRegisteredAndEnabled(input.modelVersionId());

        String actor = currentActor();
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();

        String datasetFingerprint = datasetBindingFingerprint(input.dataset());
        String targetFingerprint = targetBindingFingerprint(input.target());
        String foldFingerprint = foldBindingFingerprint(input.folds());
        String costFingerprint = economicCostBindingFingerprint(input.economicCosts());
        String specFingerprint = ModelRegistryService.fingerprint(
            CONTRACT_VERSION,
            input.modelVersionId().toString(),
            input.fromAt().toString(),
            input.toAt().toString(),
            Long.toString(input.seed()),
            datasetFingerprint,
            targetFingerprint,
            foldFingerprint,
            costFingerprint);

        jdbc.sql("""
                INSERT INTO experiment_spec (
                    experiment_id, contract_version, model_version_id, from_at, to_at, seed,
                    dataset_binding, dataset_binding_fingerprint,
                    target_binding, target_binding_fingerprint,
                    fold_binding, fold_binding_fingerprint,
                    economic_cost_binding, economic_cost_binding_fingerprint,
                    spec_fingerprint, created_at, created_by)
                VALUES (
                    :id, :contractVersion, :modelVersionId, :fromAt, :toAt, :seed,
                    CAST(:datasetBinding AS jsonb), :datasetFingerprint,
                    CAST(:targetBinding AS jsonb), :targetFingerprint,
                    CAST(:foldBinding AS jsonb), :foldFingerprint,
                    CAST(:costBinding AS jsonb), :costFingerprint,
                    :specFingerprint, :createdAt, :createdBy)
                """)
            .param("id", id)
            .param("contractVersion", CONTRACT_VERSION)
            .param("modelVersionId", input.modelVersionId())
            .param("fromAt", OffsetDateTime.ofInstant(input.fromAt(), ZoneOffset.UTC))
            .param("toAt", OffsetDateTime.ofInstant(input.toAt(), ZoneOffset.UTC))
            .param("seed", input.seed())
            .param("datasetBinding", toJson(input.dataset()))
            .param("datasetFingerprint", datasetFingerprint)
            .param("targetBinding", toJson(input.target()))
            .param("targetFingerprint", targetFingerprint)
            .param("foldBinding", toJson(input.folds()))
            .param("foldFingerprint", foldFingerprint)
            .param("costBinding", toJson(input.economicCosts()))
            .param("costFingerprint", costFingerprint)
            .param("specFingerprint", specFingerprint)
            .param("createdAt", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
            .param("createdBy", actor)
            .update();

        audit.record("EXPERIMENT_SPEC_REGISTERED", "EXPERIMENT_SPEC", id.toString(), Map.of(
            "modelVersionId", input.modelVersionId().toString(),
            "datasetBindingFingerprint", datasetFingerprint,
            "targetBindingFingerprint", targetFingerprint,
            "foldBindingFingerprint", foldFingerprint,
            "economicCostBindingFingerprint", costFingerprint,
            "specFingerprint", specFingerprint,
            "canAuthorizeCapital", false));
        return byId(id);
    }

    public ExperimentSpecView byId(UUID id) {
        return jdbc.sql("""
                SELECT experiment_id, contract_version, model_version_id, from_at, to_at, seed,
                       dataset_binding, dataset_binding_fingerprint,
                       target_binding, target_binding_fingerprint,
                       fold_binding, fold_binding_fingerprint,
                       economic_cost_binding, economic_cost_binding_fingerprint,
                       spec_fingerprint, created_at, created_by
                  FROM experiment_spec
                 WHERE experiment_id = :id
                """)
            .param("id", id)
            .query((rs, rowNum) -> map(rs))
            .optional()
            .orElseThrow(() -> new NoSuchElementException("Unknown experiment spec: " + id));
    }

    public List<ExperimentSpecView> list(UUID modelVersionId) {
        String sql = """
                SELECT experiment_id, contract_version, model_version_id, from_at, to_at, seed,
                       dataset_binding, dataset_binding_fingerprint,
                       target_binding, target_binding_fingerprint,
                       fold_binding, fold_binding_fingerprint,
                       economic_cost_binding, economic_cost_binding_fingerprint,
                       spec_fingerprint, created_at, created_by
                  FROM experiment_spec
                """ + (modelVersionId == null ? "" : " WHERE model_version_id = :modelVersionId")
            + " ORDER BY created_at DESC, experiment_id DESC LIMIT 200";
        JdbcClient.StatementSpec statement = jdbc.sql(sql);
        if (modelVersionId != null) statement.param("modelVersionId", modelVersionId);
        return statement.query((rs, rowNum) -> map(rs)).list();
    }

    private ExperimentSpecView map(java.sql.ResultSet rs) throws java.sql.SQLException {
        try {
            return new ExperimentSpecView(
                rs.getString("contract_version"),
                rs.getObject("experiment_id", UUID.class),
                rs.getObject("model_version_id", UUID.class),
                rs.getObject("from_at", OffsetDateTime.class).toInstant(),
                rs.getObject("to_at", OffsetDateTime.class).toInstant(),
                rs.getLong("seed"),
                mapper.readValue(rs.getString("dataset_binding"), DatasetBinding.class),
                rs.getString("dataset_binding_fingerprint"),
                mapper.readValue(rs.getString("target_binding"), TargetBinding.class),
                rs.getString("target_binding_fingerprint"),
                mapper.readValue(rs.getString("fold_binding"), FoldBinding.class),
                rs.getString("fold_binding_fingerprint"),
                mapper.readValue(rs.getString("economic_cost_binding"), EconomicCostBinding.class),
                rs.getString("economic_cost_binding_fingerprint"),
                rs.getString("spec_fingerprint"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getString("created_by"),
                false);
        } catch (JsonProcessingException ex) {
            throw new java.sql.SQLException("Stored experiment binding is not readable", ex);
        }
    }

    private void validate(ExperimentSpecInput input) {
        if (input == null) throw new IllegalArgumentException("Experiment spec is required");
        if (input.modelVersionId() == null) throw new IllegalArgumentException("modelVersionId is required");
        if (input.fromAt() == null || input.toAt() == null || !input.fromAt().isBefore(input.toAt())) {
            throw new IllegalArgumentException("Experiment window requires fromAt < toAt");
        }
        if (input.seed() < 0) throw new IllegalArgumentException("seed must be non-negative");
        validateDataset(input.dataset());
        validateTarget(input.target());
        validateFolds(input.folds());
        validateCosts(input.economicCosts());
    }

    private static void validateDataset(DatasetBinding binding) {
        if (binding == null) throw new IllegalArgumentException("dataset binding is required");
        requireHash(binding.datasetFingerprint(), "datasetFingerprint");
        requireHash(binding.featureSchemaFingerprint(), "featureSchemaFingerprint");
        requireHash(binding.universeFingerprint(), "universeFingerprint");
        if (binding.asOf() == null) throw new IllegalArgumentException("dataset asOf is required");
        if (!binding.pointInTime()) {
            throw new IllegalArgumentException("V4 experiments require a point-in-time dataset binding");
        }
    }

    private static void validateTarget(TargetBinding binding) {
        if (binding == null) throw new IllegalArgumentException("target binding is required");
        if (binding.targetName() == null || binding.targetName().isBlank() || binding.targetName().trim().length() > 80) {
            throw new IllegalArgumentException("targetName is required and must be <= 80 characters");
        }
        if (binding.horizonBars() < 1 || binding.horizonBars() > 10_000) {
            throw new IllegalArgumentException("horizonBars must be between 1 and 10000");
        }
        requireHash(binding.labelDefinitionFingerprint(), "labelDefinitionFingerprint");
    }

    private static void validateFolds(FoldBinding binding) {
        if (binding == null || binding.protocol() == null) {
            throw new IllegalArgumentException("fold protocol is required");
        }
        if (binding.folds() < 2 || binding.folds() > 100) {
            throw new IllegalArgumentException("folds must be between 2 and 100");
        }
        if (binding.trainBars() < 1 || binding.testBars() < 1) {
            throw new IllegalArgumentException("trainBars and testBars must be positive");
        }
        if (binding.purgeBars() < 0 || binding.embargoBars() < 0) {
            throw new IllegalArgumentException("purgeBars and embargoBars must be non-negative");
        }
        if (binding.protocol() == FoldProtocol.CHRONOLOGICAL
                && (binding.purgeBars() != 0 || binding.embargoBars() != 0)) {
            throw new IllegalArgumentException("CHRONOLOGICAL folds cannot declare purge/embargo bars");
        }
    }

    private static void validateCosts(EconomicCostBinding binding) {
        if (binding == null) throw new IllegalArgumentException("economic cost binding is required");
        bounded(binding.brokerageRate(), BigDecimal.ZERO, new BigDecimal("0.05"), "brokerageRate");
        bounded(binding.slippageRate(), BigDecimal.ZERO, new BigDecimal("0.05"), "slippageRate");
        bounded(binding.spreadBps(), BigDecimal.ZERO, new BigDecimal("1000"), "spreadBps");
        bounded(binding.statutoryCostRate(), BigDecimal.ZERO, new BigDecimal("0.05"), "statutoryCostRate");
    }

    private void ensureRegisteredAndEnabled(UUID modelVersionId) {
        Integer count = jdbc.sql("SELECT count(*) FROM model_version WHERE version_id = :versionId")
            .param("versionId", modelVersionId)
            .query(Integer.class)
            .single();
        if (count == null || count == 0) {
            throw new NoSuchElementException("Unknown registered model version: " + modelVersionId);
        }
        if (!emergencyControls.isModelAllowed(modelVersionId)) {
            throw new IllegalStateException("Model version is disabled by governance control: " + modelVersionId);
        }
    }

    private static String datasetBindingFingerprint(DatasetBinding binding) {
        return ModelRegistryService.fingerprint(
            "dataset-binding.v1",
            binding.datasetFingerprint().toLowerCase(Locale.ROOT),
            binding.featureSchemaFingerprint().toLowerCase(Locale.ROOT),
            binding.universeFingerprint().toLowerCase(Locale.ROOT),
            binding.asOf().toString(),
            Boolean.toString(binding.pointInTime()));
    }

    private static String targetBindingFingerprint(TargetBinding binding) {
        return ModelRegistryService.fingerprint(
            "target-binding.v1",
            binding.targetName().trim(),
            Integer.toString(binding.horizonBars()),
            binding.labelDefinitionFingerprint().toLowerCase(Locale.ROOT));
    }

    private static String foldBindingFingerprint(FoldBinding binding) {
        return ModelRegistryService.fingerprint(
            "fold-binding.v1",
            binding.protocol().name(),
            Integer.toString(binding.folds()),
            Integer.toString(binding.trainBars()),
            Integer.toString(binding.testBars()),
            Integer.toString(binding.purgeBars()),
            Integer.toString(binding.embargoBars()));
    }

    private static String economicCostBindingFingerprint(EconomicCostBinding binding) {
        return ModelRegistryService.fingerprint(
            "economic-cost-binding.v1",
            decimal(binding.brokerageRate()),
            decimal(binding.slippageRate()),
            decimal(binding.spreadBps()),
            decimal(binding.statutoryCostRate()));
    }

    private static void requireHash(String value, String field) {
        if (value == null || !value.toLowerCase(Locale.ROOT).matches(HASH64)) {
            throw new IllegalArgumentException(field + " must be a 64-character SHA-256 hex fingerprint");
        }
    }

    private static void bounded(BigDecimal value, BigDecimal min, BigDecimal max, String field) {
        if (value == null || value.compareTo(min) < 0 || value.compareTo(max) > 0) {
            throw new IllegalArgumentException(field + " must be between " + min + " and " + max);
        }
    }

    private static String decimal(BigDecimal value) {
        BigDecimal normalized = value.stripTrailingZeros();
        return normalized.signum() == 0 ? "0" : normalized.toPlainString();
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Experiment binding cannot be serialized", ex);
        }
    }

    private static String currentActor() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken
                || authentication.getName() == null || authentication.getName().isBlank()) {
            throw new IllegalStateException("Authenticated ADMIN actor is required for experiment registration");
        }
        String actor = authentication.getName().trim();
        if (actor.length() > 120) throw new IllegalStateException("Authenticated actor identity exceeds 120 characters");
        return actor;
    }
}
