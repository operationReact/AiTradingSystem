package in.aitradingsystem.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import in.aitradingsystem.support.AbstractPostgresIT;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

class RiskPolicyConfigIT extends AbstractPostgresIT {
    private static final String KEY_HEADER = "X-API-Key";

    @Autowired JdbcTemplate jdbc;
    @Autowired RiskPolicyConfigRepository repository;
    @Autowired MockMvc mvc;

    private UUID activeId;

    @BeforeEach
    void resetPolicies() {
        jdbc.update("DELETE FROM audit_event WHERE entity_type = 'RISK_POLICY_CONFIG'");
        jdbc.update("DELETE FROM risk_policy_config");
        activeId = UUID.randomUUID();
        repository.insert(snapshot(activeId, 1, new BigDecimal("0.25")), "Policy v1", true, "TEST");
    }

    @Test
    void exposesActivePolicyOnlyToAuthenticatedOperator() throws Exception {
        mvc.perform(get("/api/risk/policies/active"))
                .andExpect(status().isUnauthorized());

        mvc.perform(get("/api/risk/policies/active").header(KEY_HEADER, API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(activeId.toString()))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.snapshot.riskPerTradePercent").value(0.25));
    }

    @Test
    void activatesNewVersionAndWritesAuditWithoutMutatingHistoricalLimits() throws Exception {
        UUID nextId = UUID.randomUUID();
        repository.insert(snapshot(nextId, 2, new BigDecimal("0.20")), "Policy v2", false, "TEST");

        mvc.perform(put("/api/risk/policies/{id}/activate", nextId).header(KEY_HEADER, API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(nextId.toString()))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.active").value(true));

        assertThat(repository.requireActive().id()).isEqualTo(nextId);
        assertThat(repository.requireView(activeId).active()).isFalse();
        assertThat(repository.requireView(activeId).snapshot().riskPerTradePercent()).isEqualByComparingTo("0.25");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_event
                WHERE event_type = 'RISK_POLICY_ACTIVATED'
                  AND entity_type = 'RISK_POLICY_CONFIG'
                  AND entity_id = ?
                """, Integer.class, nextId.toString())).isEqualTo(1);
    }

    @Test
    void databaseRejectsInPlacePolicyLimitTampering() {
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE risk_policy_config SET risk_per_trade_percent = 0.10 WHERE id = ?", activeId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("risk policy values are immutable");

        assertThat(repository.requireActive().riskPerTradePercent()).isEqualByComparingTo("0.25");
    }

    @Test
    void concurrentActivationLeavesExactlyOneActivePolicy() throws Exception {
        UUID secondId = UUID.randomUUID();
        UUID thirdId = UUID.randomUUID();
        repository.insert(snapshot(secondId, 2, new BigDecimal("0.20")), "Policy v2", false, "TEST");
        repository.insert(snapshot(thirdId, 3, new BigDecimal("0.15")), "Policy v3", false, "TEST");

        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<RiskPolicyView> second = pool.submit(() -> {
                start.await();
                return repository.activate(secondId);
            });
            Future<RiskPolicyView> third = pool.submit(() -> {
                start.await();
                return repository.activate(thirdId);
            });
            start.countDown();
            List<UUID> completed = List.of(second.get().id(), third.get().id());
            assertThat(completed).containsExactlyInAnyOrder(secondId, thirdId);
        }

        Integer activeCount = jdbc.queryForObject("SELECT COUNT(*) FROM risk_policy_config WHERE active = TRUE", Integer.class);
        assertThat(activeCount).isEqualTo(1);
        assertThat(repository.requireActive().id()).isIn(secondId, thirdId);
    }

    @Test
    void rejectsUnauthenticatedActivation() throws Exception {
        mvc.perform(put("/api/risk/policies/{id}/activate", activeId))
                .andExpect(status().isUnauthorized());
    }

    private static RiskPolicySnapshot snapshot(UUID id, int version, BigDecimal riskPerTrade) {
        return new RiskPolicySnapshot(
                id,
                version,
                riskPerTrade,
                new BigDecimal("1.00"),
                3,
                new BigDecimal("1.50"),
                new BigDecimal("100000.00"),
                new BigDecimal("80.00"),
                new BigDecimal("25.00"),
                20,
                15,
                60,
                true,
                true,
                true,
                new BigDecimal("0.00050000"),
                new BigDecimal("0.00100000"),
                "test-v1");
    }
}
