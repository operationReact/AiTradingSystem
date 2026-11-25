package in.aitradingsystem.notification;

import in.aitradingsystem.audit.AuditService;
import in.aitradingsystem.common.NotFoundException;
import in.aitradingsystem.common.PageResponse;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationService {
    private final JdbcClient jdbc;
    private final AuditService audit;
    private final Clock clock;
    public NotificationService(JdbcClient jdbc, AuditService audit, Clock clock) {
        this.jdbc = jdbc; this.audit = audit; this.clock = clock;
    }

    /** Repeated raises preserve the original active incident. A new occurrence after acknowledgement gets a new ID. */
    @Transactional
    public SystemNotification raise(String type, NotificationSeverity severity, String title, String message,
                                     String entityType, String entityId, String dedupeKey) {
        type = required(type, 60, "type"); title = required(title, 160, "title"); message = required(message, 500, "message");
        if (severity == null) throw new IllegalArgumentException("severity is required");
        if ((entityType == null) != (entityId == null)) throw new IllegalArgumentException("Entity type and ID must be supplied together");
        if (entityType != null) { entityType = required(entityType, 60, "entityType"); entityId = required(entityId, 120, "entityId"); }
        if (dedupeKey != null) dedupeKey = required(dedupeKey, 160, "dedupeKey");
        UUID id = UUID.randomUUID();
        // The no-op conflict update locks/returns the existing active incident atomically, even while another
        // transaction acknowledges it. No title/message/severity/provenance is silently rewritten by deduplication.
        UUID persisted = jdbc.sql("""
            INSERT INTO system_notification(id,type,severity,title,message,entity_type,entity_id,dedupe_key,created_at)
            VALUES (:id,:type,:severity,:title,:message,:entityType,:entityId,:key,:now)
            ON CONFLICT (dedupe_key) WHERE acknowledged_at IS NULL AND dedupe_key IS NOT NULL
            DO UPDATE SET dedupe_key = EXCLUDED.dedupe_key RETURNING id
            """).param("id", id).param("type", type).param("severity", severity.name()).param("title", title)
            .param("message", message).param("entityType", entityType, Types.VARCHAR).param("entityId", entityId, Types.VARCHAR)
            .param("key", dedupeKey, Types.VARCHAR).param("now", clock.instant().atOffset(ZoneOffset.UTC))
            .query(UUID.class).single();
        return require(persisted);
    }

    @Transactional
    public SystemNotification acknowledge(UUID id, String actor) {
        actor = required(actor, 120, "actor");
        int changed = jdbc.sql("""
            UPDATE system_notification SET acknowledged_at = :now, acknowledged_by = :actor
             WHERE id = :id AND acknowledged_at IS NULL
            """).param("now", clock.instant().atOffset(ZoneOffset.UTC)).param("actor", actor).param("id", id).update();
        SystemNotification result = require(id);
        if (changed == 1) audit.record("NOTIFICATION_ACKNOWLEDGED", "SYSTEM_NOTIFICATION", id.toString(), Map.of("actor", actor));
        return result;
    }

    public SystemNotification require(UUID id) {
        return jdbc.sql("SELECT * FROM system_notification WHERE id = :id").param("id", id)
            .query((rs, row) -> map(rs)).optional().orElseThrow(() -> new NotFoundException("Notification not found: " + id));
    }

    public PageResponse<SystemNotification> list(boolean activeOnly, NotificationSeverity severity, int page, int size) {
        if (page < 0 || page > 1_000_000 || size < 1 || size > 100) throw new IllegalArgumentException("Invalid notification page/size");
        String filter = " WHERE (:active = FALSE OR acknowledged_at IS NULL) AND (:severity IS NULL OR severity = :severity)";
        long count = jdbc.sql("SELECT count(*) FROM system_notification" + filter).param("active", activeOnly)
            .param("severity", severity == null ? null : severity.name(), Types.VARCHAR).query(Long.class).single();
        var items = jdbc.sql("SELECT * FROM system_notification" + filter + " ORDER BY created_at DESC,id DESC LIMIT :limit OFFSET :offset")
            .param("active", activeOnly).param("severity", severity == null ? null : severity.name(), Types.VARCHAR)
            .param("limit", size).param("offset", (long) page * size).query((rs, row) -> map(rs)).list();
        return PageResponse.of(items, page, size, count);
    }

    private static String required(String text, int max, String field) {
        if (text == null || text.isBlank() || text.trim().length() > max) throw new IllegalArgumentException(field + " must be 1–" + max + " characters");
        return text.trim();
    }
    private static SystemNotification map(ResultSet rs) throws SQLException {
        var ack = rs.getObject("acknowledged_at", OffsetDateTime.class);
        return new SystemNotification(rs.getObject("id", UUID.class), rs.getString("type"),
            NotificationSeverity.valueOf(rs.getString("severity")), rs.getString("title"), rs.getString("message"),
            rs.getString("entity_type"), rs.getString("entity_id"), rs.getString("dedupe_key"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant(), ack == null ? null : ack.toInstant(), rs.getString("acknowledged_by"));
    }
}
