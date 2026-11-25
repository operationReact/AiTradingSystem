package in.aitradingsystem.operations;

import static org.assertj.core.api.Assertions.assertThat;

import in.aitradingsystem.notification.NotificationService;
import in.aitradingsystem.support.AbstractPostgresIT;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

class OperationalEvidenceIT extends AbstractPostgresIT {
    @Autowired OperationalHealthService health;
    @Autowired OperationalIncidentProjector projector;
    @Autowired NotificationService notifications;
    @Autowired JdbcClient jdbc;
    @Autowired MockMvc mvc;
    private final String source = "OPS_TEST";
    private final String id = UUID.randomUUID().toString();

    @AfterEach
    void cleanup() {
        jdbc.sql("DELETE FROM audit_event WHERE entity_type='SYSTEM_NOTIFICATION' AND entity_id IN (SELECT notification_id::text FROM notification_source_projection WHERE source_type=:source AND source_id=:id)")
            .param("source",source).param("id",id).update();
        jdbc.sql("DELETE FROM notification_source_projection WHERE source_type=:source AND source_id=:id")
            .param("source",source).param("id",id).update();
        jdbc.sql("DELETE FROM system_notification WHERE entity_type=:source AND entity_id=:id")
            .param("source",source).param("id",id).update();
    }

    @Test
    void healthIncludesAllSupportedComponentsAndNeverClaimsLiveAvailable() {
        var result = health.snapshot();
        assertThat(result.components()).extracting(OperationalHealthService.Component::component)
            .containsExactly("database","angelOne","catalogue","marketData","news","scanner","reconciliation","executionMode","emergencyStop");
        assertThat(result.liveAvailable()).isFalse();
        assertThat(result.executionMode()).isEqualTo("PAPER");
    }

    @Test
    void healthApiRequiresOperatorAuthentication() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/operations/health"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/operations/health").header("X-API-Key",API_KEY))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.liveAvailable").value(false))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.executionMode").value("PAPER"));
    }

    @Test
    void acknowledgedHistoricalFailureIsNotReopenedByRepeatedProjection() {
        var first = projector.project(source,id,"Test failure","Review persisted evidence","ops-test:"+id);
        notifications.acknowledge(first.id(),"operator");
        var repeated = projector.project(source,id,"Test failure","Review persisted evidence","ops-test:"+id);
        assertThat(repeated.id()).isEqualTo(first.id());
        assertThat(repeated.acknowledgedAt()).isNotNull();
        assertThat(jdbc.sql("SELECT count(*) FROM system_notification WHERE entity_type=:source AND entity_id=:id")
            .param("source",source).param("id",id).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void concurrentProjectionCommitsOneCompleteReceiptAndIncident() throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new java.util.concurrent.CyclicBarrier(2);
            Callable<UUID> task = () -> { start.await(); return projector.project(source,id,"Test failure","Review persisted evidence","ops-test:"+id).id(); };
            var results = pool.invokeAll(List.of(task,task));
            assertThat(results.get(0).get()).isEqualTo(results.get(1).get());
        }
        assertThat(jdbc.sql("SELECT notification_id FROM notification_source_projection WHERE source_type=:source AND source_id=:id")
            .param("source",source).param("id",id).query(UUID.class).single()).isNotNull();
    }
}
