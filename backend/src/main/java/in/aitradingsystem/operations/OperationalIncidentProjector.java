package in.aitradingsystem.operations;

import in.aitradingsystem.notification.NotificationService;
import in.aitradingsystem.notification.NotificationSeverity;
import in.aitradingsystem.notification.SystemNotification;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Durable projection receipts prevent polling from reopening acknowledged historical job incidents. */
@Service
public class OperationalIncidentProjector {
    private final JdbcClient jdbc;
    private final NotificationService notifications;
    public OperationalIncidentProjector(JdbcClient jdbc, NotificationService notifications) { this.jdbc = jdbc; this.notifications = notifications; }

    @Transactional
    public SystemNotification project(String source, String id, String title, String message, String activeKey) {
        if (source == null || source.isBlank() || source.length() > 60 || id == null || id.isBlank() || id.length() > 120)
            throw new IllegalArgumentException("Bounded source identity is required");
        int claimed = jdbc.sql("""
            INSERT INTO notification_source_projection(source_type,source_id) VALUES (:source,:id)
            ON CONFLICT (source_type,source_id) DO NOTHING
            """).param("source",source).param("id",id).update();
        if (claimed == 0) {
            UUID notification = jdbc.sql("SELECT notification_id FROM notification_source_projection WHERE source_type=:source AND source_id=:id")
                .param("source",source).param("id",id).query(UUID.class).single();
            return notifications.require(notification);
        }
        var result = notifications.raise(source, NotificationSeverity.WARNING, title, message, source, id, activeKey);
        jdbc.sql("UPDATE notification_source_projection SET notification_id=:notification WHERE source_type=:source AND source_id=:id")
            .param("notification",result.id()).param("source",source).param("id",id).update();
        return result;
    }

}
