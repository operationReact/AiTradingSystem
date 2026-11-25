package in.aitradingsystem.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.aitradingsystem.paper.PaperTradingService;
import in.aitradingsystem.support.AbstractPostgresIT;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class NotificationServiceIT extends AbstractPostgresIT {
    @Autowired NotificationService notifications;
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired PaperTradingService trading;
    private final String entity = UUID.randomUUID().toString();

    @AfterEach
    void cleanup() {
        jdbc.sql("DELETE FROM audit_event WHERE entity_type = 'SYSTEM_NOTIFICATION' AND entity_id IN (SELECT id::text FROM system_notification WHERE entity_type = 'NOTIFICATION_TEST' AND entity_id = :id)")
            .param("id", entity).update();
        jdbc.sql("DELETE FROM system_notification WHERE entity_type = 'NOTIFICATION_TEST' AND entity_id = :id")
            .param("id", entity).update();
        jdbc.sql("UPDATE trading_control SET new_orders_halted = FALSE WHERE id = 1").update();
    }

    @Test
    void duplicateRaisesKeepOriginalIncidentAndAcknowledgementAllowsNewOccurrence() {
        String key = "p8-notification-" + entity;
        var first = raise(key);
        var duplicate = raise(key);
        assertThat(duplicate).isEqualTo(first);
        var acknowledged = notifications.acknowledge(first.id(), "operator-a");
        var retried = notifications.acknowledge(first.id(), "operator-b");
        assertThat(retried.acknowledgedAt()).isEqualTo(acknowledged.acknowledgedAt());
        assertThat(retried.acknowledgedBy()).isEqualTo("operator-a");
        assertThat(raise(key).id()).isNotEqualTo(first.id());
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE event_type = 'NOTIFICATION_ACKNOWLEDGED' AND entity_id = :id")
            .param("id", first.id().toString()).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM system_notification WHERE dedupe_key = :key AND acknowledged_at IS NULL")
            .param("key", key).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void simultaneousRaisesConvergeOnOneActiveIncident() throws Exception {
        String key = "p8-notification-" + entity;
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new java.util.concurrent.CyclicBarrier(2);
            Callable<SystemNotification> task = () -> { start.await(); return raise(key); };
            var results = pool.invokeAll(List.of(task, task));
            assertThat(results.get(0).get().id()).isEqualTo(results.get(1).get().id());
        }
        assertThat(jdbc.sql("SELECT count(*) FROM system_notification WHERE dedupe_key = :key")
            .param("key", key).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void rollbackDoesNotLeaveAnIncidentForAnUncommittedSourceEvent() {
        String key = "p8-notification-" + entity;
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(status -> {
            raise(key);
            throw new IllegalStateException("source event rolled back");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.sql("SELECT count(*) FROM system_notification WHERE dedupe_key = :key")
            .param("key", key).query(Long.class).single()).isZero();
    }

    @Test
    void validatesBoundsAndEntityPairAndKeepsPagesBounded() {
        assertThatThrownBy(() -> notifications.raise("TYPE", NotificationSeverity.INFO, "Title", "Message", "TYPE", null, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> notifications.list(true, null, 0, 101)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> notifications.acknowledge(UUID.randomUUID(), " ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void activatingEmergencyStopCreatesCriticalPersistedNotification() {
        trading.setNewOrdersHalted(true, "notification integration evidence");
        assertThat(jdbc.sql("SELECT severity FROM system_notification WHERE dedupe_key = 'RISK_EMERGENCY_STOP:1' AND acknowledged_at IS NULL")
            .query(String.class).single()).isEqualTo("CRITICAL");
    }

    private SystemNotification raise(String key) {
        return notifications.raise("TEST_INCIDENT", NotificationSeverity.WARNING, "Test incident", "Review persisted test evidence",
            "NOTIFICATION_TEST", entity, key);
    }
}
