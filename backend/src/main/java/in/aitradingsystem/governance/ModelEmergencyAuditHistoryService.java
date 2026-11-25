package in.aitradingsystem.governance;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADMIN-only read model from existing immutable audit_event writes.
 *
 * Return a strict whitelist of governance fields; never forward arbitrary
 * audit details, token references, authentication headers or model artifacts.
 * No mutations or broker authority exist in this read path.
 */
@Service
@Transactional(readOnly = true)
public class ModelEmergencyAuditHistoryService {
    private static final Map<String, String> EVENT_CONTROL_TYPES = Map.of(
        "MODEL_VERSION_EMERGENCY_CONTROL_CHANGED", "MODEL_VERSION_DISABLED",
        "MODEL_FAMILY_EMERGENCY_CONTROL_CHANGED", "MODEL_FAMILY_DISABLED",
        "MODEL_STATIC_FALLBACK_CHANGED", "STATIC_FALLBACK"
    );
    private static final Map<String, String> CONTROL_ENTITY_TYPES = Map.of(
        "MODEL_VERSION_DISABLED", "MODEL_VERSION",
        "MODEL_FAMILY_DISABLED", "MODEL_FAMILY",
        "STATIC_FALLBACK", "MODEL_GOVERNANCE"
    );
    private static final int MAX_LIMIT = 50;

    private final JdbcClient jdbc;
    private final Clock clock;

    public ModelEmergencyAuditHistoryService(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public record HistoryEvent(
        long id, String eventType, String controlType, String scopeKey,
        boolean previousActive, boolean active, String reason,
        String actor, Instant occurredAt, String stateFingerprint,
        boolean canAuthorizeCapital
    ) {}

    public List<HistoryEvent> recent(String controlType, String scopeKey, int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("Emergency history limit must be 1..50");
        }
        String type = controlType == null || controlType.isBlank() ? null : controlType.trim();
        if (type != null && !CONTROL_ENTITY_TYPES.containsKey(type)) {
            throw new IllegalArgumentException("Unknown model emergency-control type");
        }
        String scope = scopeKey == null || scopeKey.isBlank() ? null : scopeKey.trim();
        if (scope != null && (scope.length() > 64 || !scope.matches("[A-Za-z0-9_.-]+"))) {
            throw new IllegalArgumentException("Invalid emergency history scope");
        }
        if (scope != null && type == null) {
            throw new IllegalArgumentException("Scope filter requires a control type");
        }
        if (scope != null) {
            validateScope(type, scope);
        }

        Instant end = clock.instant().plusNanos(1_000);
        Instant start = end.minus(Duration.ofDays(31));
        return jdbc.sql("""
                SELECT id, event_type, entity_type, entity_id, actor, actor_source,
                       created_at, details ->> 'controlType' AS control_type,
                       details ->> 'scopeKey' AS scope_key,
                       details ->> 'previousActive' AS previous_active,
                       details ->> 'active' AS active,
                       details ->> 'reason' AS reason,
                       details ->> 'backendValidationResult' AS backend_validation_result,
                       details ->> 'newConfigurationFingerprint' AS new_configuration_fingerprint,
                       details ->> 'canAuthorizeCapital' AS can_authorize_capital
                  FROM audit_event
                 WHERE event_type IN (
                       'MODEL_VERSION_EMERGENCY_CONTROL_CHANGED',
                       'MODEL_FAMILY_EMERGENCY_CONTROL_CHANGED',
                       'MODEL_STATIC_FALLBACK_CHANGED')
                   AND created_at >= :start AND created_at < :end
                   AND (:controlType IS NULL OR details ->> 'controlType' = :controlType)
                   AND (:scopeKey IS NULL OR details ->> 'scopeKey' = :scopeKey)
                 ORDER BY created_at DESC, id DESC
                 LIMIT :limit
                """)
            .param("start", start.atOffset(ZoneOffset.UTC))
            .param("end", end.atOffset(ZoneOffset.UTC))
            .param("controlType", type, Types.VARCHAR)
            .param("scopeKey", scope, Types.VARCHAR)
            .param("limit", limit)
            .query((rs, index) -> map(rs))
            .list();
    }

    private static HistoryEvent map(ResultSet rs) throws SQLException {
        String eventType = rs.getString("event_type");
        String controlType = rs.getString("control_type");
        String scope = rs.getString("scope_key");
        String entityType = rs.getString("entity_type");
        String entityId = rs.getString("entity_id");
        String actor = rs.getString("actor");
        String actorSource = rs.getString("actor_source");
        String reason = rs.getString("reason");
        String fingerprint = rs.getString("new_configuration_fingerprint");
        String validation = rs.getString("backend_validation_result");
        String noCapital = rs.getString("can_authorize_capital");

        if (!EVENT_CONTROL_TYPES.getOrDefault(eventType, "").equals(controlType)
                || !CONTROL_ENTITY_TYPES.getOrDefault(controlType, "").equals(entityType)
                || scope == null || !scope.equals(entityId)
                || actor == null || actor.isBlank() || actor.length() > 120
                || !"AUTHENTICATED".equals(actorSource)
                || reason == null || reason.isBlank() || reason.length() > 512
                || !"ACCEPTED".equals(validation)
                || !"false".equals(noCapital)
                || fingerprint == null || !fingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalStateException("Unverified model emergency-control audit history");
        }
        validateScope(controlType, scope);
        return new HistoryEvent(
            rs.getLong("id"), eventType, controlType, scope,
            exactBoolean(rs.getString("previous_active")),
            exactBoolean(rs.getString("active")),
            reason, actor, rs.getObject("created_at", OffsetDateTime.class).toInstant(),
            fingerprint, false
        );
    }

    private static boolean exactBoolean(String value) {
        if ("true".equals(value)) return true;
        if ("false".equals(value)) return false;
        throw new IllegalStateException("Invalid persisted emergency-control audit boolean");
    }

    private static void validateScope(String type, String scope) {
        boolean valid = switch (type) {
            case "MODEL_VERSION_DISABLED" -> {
                try {
                    yield UUID.fromString(scope).toString().equals(scope.toLowerCase(java.util.Locale.ROOT));
                } catch (IllegalArgumentException ex) {
                    yield false;
                }
            }
            case "MODEL_FAMILY_DISABLED" -> scope.matches("[A-Z0-9][A-Z0-9_]{1,39}");
            case "STATIC_FALLBACK" -> "GLOBAL".equals(scope);
            default -> false;
        };
        if (!valid) {
            throw new IllegalArgumentException("Invalid model emergency-control audit scope");
        }
    }
}
