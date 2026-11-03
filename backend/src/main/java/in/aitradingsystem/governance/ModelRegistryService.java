package in.aitradingsystem.governance;

import in.aitradingsystem.audit.AuditService;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Java-authoritative immutable registry for model definitions and exact artifact versions.
 * Lifecycle/environment mutations are intentionally separate from registry identity.
 */
@Service
public class ModelRegistryService {
    public static final String MODEL_DEFINITION_CONTRACT = "model-definition.v1";
    public static final String MODEL_VERSION_CONTRACT = "model-version.v1";

    private final JdbcClient jdbc;
    private final AuditService audit;
    private final Clock clock;

    public ModelRegistryService(JdbcClient jdbc, AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
    }

    public record ModelDefinitionView(
        String contractVersion,
        UUID definitionId,
        String modelKey,
        String displayName,
        String family,
        String role,
        String provider,
        String licenseStatus,
        String description,
        String definitionFingerprint,
        Instant createdAt,
        String createdBy) {}

    public record ModelVersionView(
        String contractVersion,
        UUID versionId,
        UUID definitionId,
        String modelKey,
        String versionLabel,
        String artifactFingerprint,
        String featureSchemaFingerprint,
        String trainingDatasetFingerprint,
        String trainingConfigFingerprint,
        String codeSha,
        String researchRegistryFingerprint,
        Instant trainingCutoff,
        String versionFingerprint,
        Instant createdAt,
        String createdBy) {}

    public record DefinitionSpec(
        String modelKey,
        String displayName,
        String family,
        String role,
        String provider,
        String licenseStatus,
        String description) {}

    public record VersionSpec(
        String versionLabel,
        String artifactFingerprint,
        String featureSchemaFingerprint,
        String trainingDatasetFingerprint,
        String trainingConfigFingerprint,
        String codeSha,
        String researchRegistryFingerprint,
        Instant trainingCutoff) {}

    @Transactional
    public ModelDefinitionView createDefinition(DefinitionSpec input) {
        String actor = currentActor();
        String modelKey = input.modelKey().trim().toLowerCase(Locale.ROOT);
        String displayName = input.displayName().trim();
        String family = input.family().trim().toUpperCase(Locale.ROOT);
        String role = input.role().trim().toUpperCase(Locale.ROOT);
        String provider = input.provider().trim();
        String licenseStatus = input.licenseStatus().trim().toUpperCase(Locale.ROOT);
        String description = normalizeNullable(input.description());
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        String fingerprint = fingerprint(
            MODEL_DEFINITION_CONTRACT, modelKey, displayName, family, role, provider, licenseStatus,
            description == null ? "" : description);

        jdbc.sql("""
                INSERT INTO model_definition (
                    definition_id, model_key, display_name, family, model_role, provider,
                    license_status, description, definition_fingerprint, created_at, created_by)
                VALUES (:id, :modelKey, :displayName, :family, :role, :provider,
                    :licenseStatus, :description, :fingerprint, :createdAt, :createdBy)
                """)
            .param("id", id)
            .param("modelKey", modelKey)
            .param("displayName", displayName)
            .param("family", family)
            .param("role", role)
            .param("provider", provider)
            .param("licenseStatus", licenseStatus)
            .param("description", description)
            .param("fingerprint", fingerprint)
            .param("createdAt", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
            .param("createdBy", actor)
            .update();

        audit.record("MODEL_DEFINITION_REGISTERED", "MODEL_DEFINITION", id.toString(), Map.of(
            "modelKey", modelKey,
            "family", family,
            "role", role,
            "licenseStatus", licenseStatus,
            "definitionFingerprint", fingerprint));
        return definitionByKey(modelKey);
    }

    public List<ModelDefinitionView> definitions() {
        return jdbc.sql("""
                SELECT definition_id, model_key, display_name, family, model_role, provider,
                       license_status, description, definition_fingerprint, created_at, created_by
                  FROM model_definition
                 ORDER BY model_key
                """)
            .query((rs, rowNum) -> mapDefinition(rs))
            .list();
    }

    public ModelDefinitionView definitionByKey(String rawModelKey) {
        String modelKey = rawModelKey.trim().toLowerCase(Locale.ROOT);
        return jdbc.sql("""
                SELECT definition_id, model_key, display_name, family, model_role, provider,
                       license_status, description, definition_fingerprint, created_at, created_by
                  FROM model_definition
                 WHERE model_key = :modelKey
                """)
            .param("modelKey", modelKey)
            .query((rs, rowNum) -> mapDefinition(rs))
            .optional()
            .orElseThrow(() -> new NoSuchElementException("Unknown model definition: " + modelKey));
    }

    @Transactional
    public ModelVersionView createVersion(String rawModelKey, VersionSpec input) {
        String actor = currentActor();
        ModelDefinitionView definition = definitionByKey(rawModelKey);
        String versionLabel = input.versionLabel().trim();
        String artifact = input.artifactFingerprint().trim().toLowerCase(Locale.ROOT);
        String featureSchema = input.featureSchemaFingerprint().trim().toLowerCase(Locale.ROOT);
        String dataset = input.trainingDatasetFingerprint().trim().toLowerCase(Locale.ROOT);
        String trainingConfig = input.trainingConfigFingerprint().trim().toLowerCase(Locale.ROOT);
        String codeSha = input.codeSha().trim().toLowerCase(Locale.ROOT);
        String researchRegistry = normalizeNullable(input.researchRegistryFingerprint());
        if (researchRegistry != null) {
            researchRegistry = researchRegistry.toLowerCase(Locale.ROOT);
        }
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        String versionFingerprint = fingerprint(
            MODEL_VERSION_CONTRACT,
            definition.definitionFingerprint(),
            versionLabel,
            artifact,
            featureSchema,
            dataset,
            trainingConfig,
            codeSha,
            researchRegistry == null ? "" : researchRegistry,
            input.trainingCutoff() == null ? "" : input.trainingCutoff().toString());

        jdbc.sql("""
                INSERT INTO model_version (
                    version_id, definition_id, version_label, artifact_fingerprint,
                    feature_schema_fingerprint, training_dataset_fingerprint,
                    training_config_fingerprint, code_sha, research_registry_fingerprint,
                    training_cutoff, version_fingerprint, created_at, created_by)
                VALUES (:id, :definitionId, :versionLabel, :artifact, :featureSchema,
                    :dataset, :trainingConfig, :codeSha, :researchRegistry,
                    :trainingCutoff, :versionFingerprint, :createdAt, :createdBy)
                """)
            .param("id", id)
            .param("definitionId", definition.definitionId())
            .param("versionLabel", versionLabel)
            .param("artifact", artifact)
            .param("featureSchema", featureSchema)
            .param("dataset", dataset)
            .param("trainingConfig", trainingConfig)
            .param("codeSha", codeSha)
            .param("researchRegistry", researchRegistry)
            .param("trainingCutoff", input.trainingCutoff() == null
                ? null : OffsetDateTime.ofInstant(input.trainingCutoff(), ZoneOffset.UTC))
            .param("versionFingerprint", versionFingerprint)
            .param("createdAt", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
            .param("createdBy", actor)
            .update();

        audit.record("MODEL_VERSION_REGISTERED", "MODEL_VERSION", id.toString(), Map.of(
            "modelKey", definition.modelKey(),
            "versionLabel", versionLabel,
            "artifactFingerprint", artifact,
            "versionFingerprint", versionFingerprint));
        return versionById(id);
    }

    public List<ModelVersionView> versions(String rawModelKey) {
        ModelDefinitionView definition = definitionByKey(rawModelKey);
        return jdbc.sql("""
                SELECT v.version_id, v.definition_id, d.model_key, v.version_label,
                       v.artifact_fingerprint, v.feature_schema_fingerprint,
                       v.training_dataset_fingerprint, v.training_config_fingerprint,
                       v.code_sha, v.research_registry_fingerprint, v.training_cutoff,
                       v.version_fingerprint, v.created_at, v.created_by
                  FROM model_version v
                  JOIN model_definition d ON d.definition_id = v.definition_id
                 WHERE v.definition_id = :definitionId
                 ORDER BY v.created_at DESC, v.version_id DESC
                """)
            .param("definitionId", definition.definitionId())
            .query((rs, rowNum) -> mapVersion(rs))
            .list();
    }

    private ModelVersionView versionById(UUID id) {
        return jdbc.sql("""
                SELECT v.version_id, v.definition_id, d.model_key, v.version_label,
                       v.artifact_fingerprint, v.feature_schema_fingerprint,
                       v.training_dataset_fingerprint, v.training_config_fingerprint,
                       v.code_sha, v.research_registry_fingerprint, v.training_cutoff,
                       v.version_fingerprint, v.created_at, v.created_by
                  FROM model_version v
                  JOIN model_definition d ON d.definition_id = v.definition_id
                 WHERE v.version_id = :id
                """)
            .param("id", id)
            .query((rs, rowNum) -> mapVersion(rs))
            .optional()
            .orElseThrow(() -> new IllegalStateException("Registered model version cannot be read back"));
    }

    private static ModelDefinitionView mapDefinition(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ModelDefinitionView(
            MODEL_DEFINITION_CONTRACT,
            rs.getObject("definition_id", UUID.class),
            rs.getString("model_key"),
            rs.getString("display_name"),
            rs.getString("family"),
            rs.getString("model_role"),
            rs.getString("provider"),
            rs.getString("license_status"),
            rs.getString("description"),
            rs.getString("definition_fingerprint"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant(),
            rs.getString("created_by"));
    }

    private static ModelVersionView mapVersion(java.sql.ResultSet rs) throws java.sql.SQLException {
        OffsetDateTime cutoff = rs.getObject("training_cutoff", OffsetDateTime.class);
        return new ModelVersionView(
            MODEL_VERSION_CONTRACT,
            rs.getObject("version_id", UUID.class),
            rs.getObject("definition_id", UUID.class),
            rs.getString("model_key"),
            rs.getString("version_label"),
            rs.getString("artifact_fingerprint"),
            rs.getString("feature_schema_fingerprint"),
            rs.getString("training_dataset_fingerprint"),
            rs.getString("training_config_fingerprint"),
            rs.getString("code_sha"),
            rs.getString("research_registry_fingerprint"),
            cutoff == null ? null : cutoff.toInstant(),
            rs.getString("version_fingerprint"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant(),
            rs.getString("created_by"));
    }

    private String currentActor() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken
                || authentication.getName() == null || authentication.getName().isBlank()) {
            throw new IllegalStateException("Authenticated actor is required for model registry mutation");
        }
        String actor = authentication.getName().trim();
        if (actor.length() > 120) {
            throw new IllegalStateException("Authenticated actor identity exceeds 120 characters");
        }
        return actor;
    }

    private static String normalizeNullable(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    static String fingerprint(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
