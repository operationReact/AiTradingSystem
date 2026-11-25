package in.aitradingsystem.governance;

import in.aitradingsystem.audit.AuditService;
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
 * Java-authoritative emergency controls for model intelligence participation.
 * These controls can only remove model influence; they never grant trading or LIVE authority.
 */
@Service
public class ModelEmergencyControlService {
    public static final String CONTRACT_VERSION = "model-emergency-control.v1";
    static final String MODEL_VERSION_DISABLED = "MODEL_VERSION_DISABLED";
    static final String MODEL_FAMILY_DISABLED = "MODEL_FAMILY_DISABLED";
    static final String STATIC_FALLBACK = "STATIC_FALLBACK";
    static final String GLOBAL = "GLOBAL";

    private final JdbcClient jdbc;
    private final AuditService audit;
    private final Clock clock;

    public ModelEmergencyControlService(JdbcClient jdbc, AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
    }

    public record ControlView(
        String contractVersion,
        String controlType,
        String scopeKey,
        boolean active,
        String reason,
        String stateFingerprint,
        Instant updatedAt,
        String updatedBy,
        boolean canAuthorizeCapital) {}

    public record EffectiveModelControlView(
        String contractVersion,
        UUID versionId,
        String modelKey,
        String family,
        boolean allowed,
        boolean modelVersionDisabled,
        boolean modelFamilyDisabled,
        boolean staticFallbackEnabled,
        boolean canAuthorizeCapital) {}

    private record ModelIdentity(UUID versionId, String modelKey, String family) {}
    private record PreviousControl(boolean active, String stateFingerprint) {}

    public List<ControlView> controls() {
        return jdbc.sql("""
                SELECT control_type, scope_key, active, reason, state_fingerprint, updated_at, updated_by
                  FROM model_governance_emergency_control
                 ORDER BY control_type, scope_key
                """)
            .query((rs, rowNum) -> mapControl(rs))
            .list();
    }

    public EffectiveModelControlView effectiveModelControl(UUID versionId) {
        ModelIdentity model = modelIdentity(versionId);
        boolean versionDisabled = isActive(MODEL_VERSION_DISABLED, versionId.toString());
        boolean familyDisabled = isActive(MODEL_FAMILY_DISABLED, model.family());
        boolean staticFallback = isStaticFallbackEnabled();
        return new EffectiveModelControlView(
            CONTRACT_VERSION,
            versionId,
            model.modelKey(),
            model.family(),
            !(versionDisabled || familyDisabled || staticFallback),
            versionDisabled,
            familyDisabled,
            staticFallback,
            false);
    }

    public boolean isModelAllowed(UUID versionId) {
        try {
            return effectiveModelControl(versionId).allowed();
        } catch (NoSuchElementException ex) {
            return false;
        }
    }

    public void requireModelAllowed(UUID versionId) {
        EffectiveModelControlView control = effectiveModelControl(versionId);
        if (!control.allowed()) {
            throw new IllegalStateException(
                "Model intelligence is disabled by governance control for version " + versionId);
        }
    }

    public boolean isStaticFallbackEnabled() {
        return isActive(STATIC_FALLBACK, GLOBAL);
    }

    @Transactional
    public ControlView setModelVersionDisabled(UUID versionId, boolean disabled, String reason) {
        ModelIdentity model = modelIdentity(versionId);
        return setControl(
            MODEL_VERSION_DISABLED,
            versionId.toString(),
            disabled,
            reason,
            "MODEL_VERSION_EMERGENCY_CONTROL_CHANGED",
            "MODEL_VERSION",
            versionId.toString(),
            Map.of("modelKey", model.modelKey(), "family", model.family()));
    }

    @Transactional
    public ControlView setModelFamilyDisabled(String rawFamily, boolean disabled, String reason) {
        String family = normalizeFamily(rawFamily);
        Integer count = jdbc.sql("SELECT count(*) FROM model_definition WHERE family = :family")
            .param("family", family)
            .query(Integer.class)
            .single();
        if (count == null || count == 0) {
            throw new NoSuchElementException("Unknown registered model family: " + family);
        }
        return setControl(
            MODEL_FAMILY_DISABLED,
            family,
            disabled,
            reason,
            "MODEL_FAMILY_EMERGENCY_CONTROL_CHANGED",
            "MODEL_FAMILY",
            family,
            Map.of("family", family));
    }

    @Transactional
    public ControlView setStaticFallbackEnabled(boolean enabled, String reason) {
        return setControl(
            STATIC_FALLBACK,
            GLOBAL,
            enabled,
            reason,
            "MODEL_STATIC_FALLBACK_CHANGED",
            "MODEL_GOVERNANCE",
            GLOBAL,
            Map.of("mode", enabled ? "STATIC_FALLBACK" : "NORMAL"));
    }

    private ControlView setControl(
            String controlType,
            String scopeKey,
            boolean active,
            String rawReason,
            String auditEventType,
            String auditEntityType,
            String auditEntityId,
            Map<String, Object> additionalAuditDetails) {
        String reason = normalizeReason(rawReason);
        String actor = currentActor();
        Instant now = clock.instant();
        PreviousControl previous = currentControlForUpdate(controlType, scopeKey);
        String nextFingerprint = stateFingerprint(controlType, scopeKey, active);

        jdbc.sql("""
                INSERT INTO model_governance_emergency_control (
                    control_type, scope_key, active, reason, state_fingerprint, updated_at, updated_by)
                VALUES (:controlType, :scopeKey, :active, :reason, :fingerprint, :updatedAt, :updatedBy)
                ON CONFLICT (control_type, scope_key) DO UPDATE SET
                    active = EXCLUDED.active,
                    reason = EXCLUDED.reason,
                    state_fingerprint = EXCLUDED.state_fingerprint,
                    updated_at = EXCLUDED.updated_at,
                    updated_by = EXCLUDED.updated_by
                """)
            .param("controlType", controlType)
            .param("scopeKey", scopeKey)
            .param("active", active)
            .param("reason", reason)
            .param("fingerprint", nextFingerprint)
            .param("updatedAt", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
            .param("updatedBy", actor)
            .update();

        java.util.HashMap<String, Object> details = new java.util.HashMap<>(additionalAuditDetails);
        details.put("controlType", controlType);
        details.put("scopeKey", scopeKey);
        details.put("previousActive", previous.active());
        details.put("active", active);
        details.put("previousConfigurationFingerprint", previous.stateFingerprint());
        details.put("newConfigurationFingerprint", nextFingerprint);
        details.put("reason", reason);
        details.put("backendValidationResult", "ACCEPTED");
        details.put("canAuthorizeCapital", false);
        audit.record(auditEventType, auditEntityType, auditEntityId, Map.copyOf(details));
        return control(controlType, scopeKey);
    }

    private PreviousControl currentControlForUpdate(String controlType, String scopeKey) {
        return jdbc.sql("""
                SELECT active, state_fingerprint
                  FROM model_governance_emergency_control
                 WHERE control_type = :controlType AND scope_key = :scopeKey
                 FOR UPDATE
                """)
            .param("controlType", controlType)
            .param("scopeKey", scopeKey)
            .query((rs, rowNum) -> new PreviousControl(
                rs.getBoolean("active"), rs.getString("state_fingerprint")))
            .optional()
            .orElseGet(() -> new PreviousControl(false, stateFingerprint(controlType, scopeKey, false)));
    }

    private ControlView control(String controlType, String scopeKey) {
        return jdbc.sql("""
                SELECT control_type, scope_key, active, reason, state_fingerprint, updated_at, updated_by
                  FROM model_governance_emergency_control
                 WHERE control_type = :controlType AND scope_key = :scopeKey
                """)
            .param("controlType", controlType)
            .param("scopeKey", scopeKey)
            .query((rs, rowNum) -> mapControl(rs))
            .optional()
            .orElseThrow(() -> new IllegalStateException("Emergency control cannot be read back"));
    }

    private static ControlView mapControl(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ControlView(
            CONTRACT_VERSION,
            rs.getString("control_type"),
            rs.getString("scope_key"),
            rs.getBoolean("active"),
            rs.getString("reason"),
            rs.getString("state_fingerprint"),
            rs.getObject("updated_at", OffsetDateTime.class).toInstant(),
            rs.getString("updated_by"),
            false);
    }

    private ModelIdentity modelIdentity(UUID versionId) {
        return jdbc.sql("""
                SELECT v.version_id, d.model_key, d.family
                  FROM model_version v
                  JOIN model_definition d ON d.definition_id = v.definition_id
                 WHERE v.version_id = :versionId
                """)
            .param("versionId", versionId)
            .query((rs, rowNum) -> new ModelIdentity(
                rs.getObject("version_id", UUID.class),
                rs.getString("model_key"),
                rs.getString("family")))
            .optional()
            .orElseThrow(() -> new NoSuchElementException("Unknown registered model version: " + versionId));
    }

    private boolean isActive(String controlType, String scopeKey) {
        return jdbc.sql("""
                SELECT active
                  FROM model_governance_emergency_control
                 WHERE control_type = :controlType AND scope_key = :scopeKey
                """)
            .param("controlType", controlType)
            .param("scopeKey", scopeKey)
            .query(Boolean.class)
            .optional()
            .orElse(false);
    }

    private static String stateFingerprint(String controlType, String scopeKey, boolean active) {
        return ModelRegistryService.fingerprint(
            CONTRACT_VERSION, controlType, scopeKey, Boolean.toString(active));
    }

    private static String normalizeFamily(String rawFamily) {
        if (rawFamily == null || rawFamily.isBlank()) {
            throw new IllegalArgumentException("Model family is required");
        }
        String family = rawFamily.trim().toUpperCase(Locale.ROOT);
        if (!family.matches("^[A-Z0-9][A-Z0-9_]{1,39}$")) {
            throw new IllegalArgumentException("Invalid model family: " + rawFamily);
        }
        return family;
    }

    private static String normalizeReason(String rawReason) {
        if (rawReason == null || rawReason.isBlank()) {
            throw new IllegalArgumentException("Emergency-control reason is required");
        }
        String reason = rawReason.trim();
        if (reason.length() > 512) {
            throw new IllegalArgumentException("Emergency-control reason exceeds 512 characters");
        }
        return reason;
    }

    private static String currentActor() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken
                || authentication.getName() == null || authentication.getName().isBlank()) {
            throw new IllegalStateException("Authenticated ADMIN actor is required for emergency model control");
        }
        String actor = authentication.getName().trim();
        if (actor.length() > 120) {
            throw new IllegalStateException("Authenticated actor identity exceeds 120 characters");
        }
        return actor;
    }
}
