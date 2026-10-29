package in.aitradingsystem.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import in.aitradingsystem.risk.CreateRiskPolicyRequest;
import in.aitradingsystem.risk.RiskPolicyConfigService;
import in.aitradingsystem.support.AbstractPostgresIT;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

class PaperRiskV2IT extends AbstractPostgresIT {
    private static final String KEY_HEADER = "X-API-Key";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired RiskPolicyConfigService policies;

    @BeforeEach
    void activatePaperCompatibilityPolicy() {
        policies.createAndActivate(compatibilityPolicy("PAPER V2 integration"), "PAPER_V2_IT");
    }

    @Test
    void paperRejectionPersistsV2PolicyAndAllOrderedRulesExactlyOnceAcrossReplay() throws Exception {
        String key = "p05-v2-" + UUID.randomUUID();
        String symbol = "V2" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        String body = buy(symbol, 100);

        mvc.perform(post("/api/paper-trades/simulate")
                        .header(KEY_HEADER, API_KEY)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity());

        UUID decisionId = jdbc.queryForObject("""
                SELECT d.id FROM risk_decision d
                JOIN paper_order o ON o.id = d.order_id
                WHERE o.idempotency_key = ?
                """, UUID.class, key);
        UUID originalPolicyId = jdbc.queryForObject(
                "SELECT policy_config_id FROM risk_decision WHERE id = ?", UUID.class, decisionId);
        Integer ruleCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM risk_decision_rule WHERE risk_decision_id = ?", Integer.class, decisionId);

        assertThat(jdbc.queryForObject("SELECT result_class FROM risk_decision WHERE id = ?", String.class, decisionId))
                .isEqualTo("REJECTED");
        assertThat(jdbc.queryForObject("SELECT policy_snapshot->>'id' FROM risk_decision WHERE id = ?", String.class, decisionId))
                .isEqualTo(originalPolicyId.toString());
        assertThat(ruleCount).isEqualTo(18);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM risk_decision_rule
                WHERE risk_decision_id = ? AND reason_code = 'RISK_BUDGET_EXCEEDED'
                """, Integer.class, decisionId)).isEqualTo(1);

        policies.createAndActivate(compatibilityPolicy("PAPER V2 policy changed after evaluation"), "PAPER_V2_IT");

        mvc.perform(post("/api/paper-trades/simulate")
                        .header(KEY_HEADER, API_KEY)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity());

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM risk_decision d
                JOIN paper_order o ON o.id = d.order_id
                WHERE o.idempotency_key = ?
                """, Integer.class, key)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM risk_decision_rule WHERE risk_decision_id = ?", Integer.class, decisionId))
                .isEqualTo(ruleCount);
        assertThat(jdbc.queryForObject(
                "SELECT policy_config_id FROM risk_decision WHERE id = ?", UUID.class, decisionId))
                .isEqualTo(originalPolicyId);
    }

    private static CreateRiskPolicyRequest compatibilityPolicy(String name) {
        return new CreateRiskPolicyRequest(
                name,
                new BigDecimal("0.25"),
                new BigDecimal("1.00"),
                3,
                BigDecimal.ONE,
                new BigDecimal("999999999999999.99"),
                new BigDecimal("100.00"),
                new BigDecimal("100.00"),
                1000,
                30,
                3600,
                false,
                true,
                false,
                new BigDecimal("0.00050000"),
                new BigDecimal("0.00100000"),
                "paper-v2-it");
    }

    private static String buy(String symbol, int quantity) {
        return "{\"symbol\":\"" + symbol + "\",\"side\":\"BUY\",\"quantity\":" + quantity
                + ",\"entryPrice\":500,\"stopLoss\":490,\"target\":530,\"signalGeneratedAt\":\""
                + Instant.now() + "\"}";
    }
}
