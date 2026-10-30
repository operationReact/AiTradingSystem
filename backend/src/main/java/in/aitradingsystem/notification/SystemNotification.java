package in.aitradingsystem.notification;

import java.time.Instant;
import java.util.UUID;

public record SystemNotification(UUID id, String type, NotificationSeverity severity, String title, String message,
    String entityType, String entityId, String dedupeKey, Instant createdAt, Instant acknowledgedAt, String acknowledgedBy) {}
