package in.aitradingsystem.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import in.aitradingsystem.support.AbstractPostgresIT;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

class RiskPolicyConfigIT extends AbstractPostgresIT {
    private static final String KEY_HEADER = "X-API-Key";

    @Autowired JdbcTemplate jdbc;
    @Autowired RiskPolicyConfigRepository repository;
    @Autowired RiskPolicyConfigService service;
    @Autowired RiskPolicyBootstrap bootstrap;
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
    void exposesActiveAndPagedHistoryOnlyToAuthenticatedOperator() throws Exception {
        mvc.perform(get("/api/risk/policies/active"))
                .andExpect(status().isUnauthorized());

        mvc.perform(get("/api/risk/policies/active").header(KEY_HEADER, API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(activeId.toString()))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.snapshot.riskPerTradePercent").value(0.25));

        mvc.perform(get("/api/risk/policies").header(KEY_HEADER, API_KEY).param("page", "0").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(activeId.toString()))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalItems").value(1));
    }

    @Test
    void createsServerVersionActivatesAtomicallyAndAuditsActor() throws Exception {
        String response = mvc.perform(post("/api/risk/policies")
                        .header(KEY_HEADER, API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson("Policy v2", "0.20")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.createdBy").value("operator"))
                .andExpect(jsonPath("$.snapshot.riskPerTradePercent").value(0.20))
                .andReturn().getResponse().getContentAsString();

        String nextId = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response).path("id").asText();
        assertThat(repository.requireActive().id().toString()).isEqualTo(nextId);
        assertThat(repository.requireView(activeId).active()).isFalse();
        assertThat(repository.requireView(activeId).snapshot().riskPerTradePercent()).isEqualByComparingTo("0.25");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_event
                WHERE event_type = 'RISK_POLICY_CREATED_AND_ACTIVATED'
                  AND entity_type = 'RISK_POLICY_CONFIG'
                  AND entity_id = ?
                """, Integer.class, nextId)).isEqualTo(1);
    }

    @Test
    void rejectsInvalidCrossFieldPolicyBeforePersistence() throws Exception {
        String invalid = requestJson("Invalid policy", "2.00").replace("\"maxDailyDrawdownPercent\":1.00", "\"maxDailyDrawdownPercent\":1.50");
        mvc.perform(post("/api/risk/policies")
                        .header(KEY_HEADER, API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalid))
                .andExpect(status().isBadRequest());
        assertThat(repository.count()).isEqualTo(1);
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
    void concurrentCreateAndActivateSerializesVersionAllocationAndLeavesOneActive() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<RiskPolicyView> second = pool.submit(() -> {
                start.await();
                return service.createAndActivate(request("Concurrent v2", "0.20"), "operator-a");
            });
            Future<RiskPolicyView> third = pool.submit(() -> {
                start.await();
                return service.createAndActivate(request("Concurrent v3", "0.15"), "operator-b");
            });
            start.countDown();
            List<RiskPolicyView> completed = List.of(second.get(), third.get());
            assertThat(completed.stream().map(RiskPolicyView::version).collect(java.util.stream.Collectors.toSet()))
                    .isEqualTo(Set.of(2, 3));
        }

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM risk_policy_config WHERE active = TRUE", Integer.class))
                .isEqualTo(1);
        assertThat(repository.count()).isEqualTo(3);
        assertThat(repository.requireActive().version()).isEqualTo(3);
    }

    @Test
    void bootstrapCreatesVersionOneOnlyWhenNoPolicyExists() {
        jdbc.update("DELETE FROM risk_policy_config");
        bootstrap.run(null);
        RiskPolicyView bootstrapped = repository.requireActiveView();
        assertThat(bootstrapped.version()).isEqualTo(1);
        assertThat(bootstrapped.createdBy()).isEqualTo("SYSTEM_BOOTSTRAP");
        assertThat(bootstrapped.snapshot().minRewardRiskRatio()).isEqualByComparingTo("1");
        assertThat(bootstrapped.snapshot().maxTradesPerDay()).isEqualTo(1000);

        bootstrap.run(null);
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void rejectsUnauthenticatedPolicyCreation() throws Exception {
        mvc.perform(post("/api/risk/policies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson("Unauthorized", "0.20")))
                .andExpect(status().isUnauthorized());
        assertThat(repository.count()).isEqualTo(1);
    }

    private static CreateRiskPolicyRequest request(String name, String riskPerTrade) {
        return new CreateRiskPolicyRequest(
                name,
                new BigDecimal(riskPerTrade),
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

    private static RiskPolicySnapshot snapshot(UUID id, int version, BigDecimal riskPerTrade) {
        CreateRiskPolicyRequest request = request("Policy " + version, riskPerTrade.toPlainString());
        return new RiskPolicySnapshot(
                id,
                version,
                request.riskPerTradePercent(),
                request.maxDailyDrawdownPercent(),
                request.maxOpenPositions(),
                request.minRewardRiskRatio(),
                request.maxOrderNotional(),
                request.maxGrossExposurePercent(),
                request.maxSymbolExposurePercent(),
                request.maxTradesPerDay(),
                request.signalMaxAgeMinutes(),
                request.marketStateMaxAgeSeconds(),
                request.enforceMarketSession(),
                request.rejectExistingSymbolPosition(),
                request.rejectDuplicatePendingExposure(),
                request.feeRate(),
                request.slippageRate(),
                request.feeSlippageProfileVersion());
    }

    private static String requestJson(String name, String riskPerTrade) {
        return """
                {
                  "policyName":"%s",
                  "riskPerTradePercent":%s,
                  "maxDailyDrawdownPercent":1.00,
                  "maxOpenPositions":3,
                  "minRewardRiskRatio":1.50,
                  "maxOrderNotional":100000.00,
                  "maxGrossExposurePercent":80.00,
                  "maxSymbolExposurePercent":25.00,
                  "maxTradesPerDay":20,
                  "signalMaxAgeMinutes":15,
                  "marketStateMaxAgeSeconds":60,
                  "enforceMarketSession":true,
                  "rejectExistingSymbolPosition":true,
                  "rejectDuplicatePendingExposure":true,
                  "feeRate":0.00050000,
                  "slippageRate":0.00100000,
                  "feeSlippageProfileVersion":"test-v1"
                }
                """.formatted(name, riskPerTrade);
    }
}
