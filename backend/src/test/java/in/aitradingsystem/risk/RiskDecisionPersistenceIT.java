package in.aitradingsystem.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import in.aitradingsystem.domain.Side;
import in.aitradingsystem.support.AbstractPostgresIT;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

class RiskDecisionPersistenceIT extends AbstractPostgresIT {
    private static final String KEY_HEADER = "X-API-Key";
    private static final Instant NOW = Instant.parse("2026-10-29T10:00:00Z");

    @Autowired JdbcTemplate jdbc;
    @Autowired RiskPolicyConfigRepository policies;
    @Autowired RiskDecisionPersistenceService persistence;
    @Autowired MockMvc mvc;

    private UUID orderId;
    private RiskPolicySnapshot policy;

    @BeforeEach
    void resetAndSeed() {
        jdbc.update("DELETE FROM risk_decision_rule");
        jdbc.update("DELETE FROM risk_decision");
        jdbc.update("DELETE FROM paper_fill");
        jdbc.update("DELETE FROM paper_cash_entry WHERE order_id IS NOT NULL");
        jdbc.update("DELETE FROM paper_order");
        jdbc.update("DELETE FROM audit_event WHERE entity_type = 'RISK_POLICY_CONFIG'");
        jdbc.update("DELETE FROM risk_policy_config");

        policy = policy();
        policies.insert(policy, "Persistence test policy", true, "TEST");
        orderId = UUID.randomUUID();
        seedOrder(orderId, "decision-v2-" + orderId);
    }

    @Test
    void persistsV2DecisionPolicySnapshotAndOrderedRuleEvidence() throws Exception {
        RiskContext context = context(orderId, policy);
        UUID decisionId = UUID.randomUUID();
        RiskDecisionV2 decision = new RiskDecisionV2(
                decisionId,
                RiskDecisionClass.REJECTED,
                List.of(
                        RiskRuleResult.pass(RiskRuleCode.EMERGENCY_STOP),
                        new RiskRuleResult(RiskRuleCode.MARKET_STATE_FRESHNESS, RiskOutcome.HARD_REJECT,
                                "MARKET_STATE_STALE", new BigDecimal("61"), new BigDecimal("60"),
                                "seconds", Map.of("source", "market-candle")),
                        RiskRuleResult.pass(RiskRuleCode.MARKET_SESSION)),
                new BigDecimal("500.5000"),
                new BigDecimal("5005.00"),
                new BigDecimal("2.50"),
                new BigDecimal("5.00"),
                new BigDecimal("105.00"),
                new BigDecimal("250.00"),
                new BigDecimal("2.50000000"),
                new BigDecimal("15005.00"),
                new BigDecimal("7005.00"),
                4,
                policy.id(),
                policy.version(),
                NOW);

        persistence.persist(orderId, context, decision);

        assertThat(jdbc.queryForObject("SELECT result_class FROM risk_decision WHERE id = ?", String.class, decisionId))
                .isEqualTo("REJECTED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM risk_decision_rule WHERE risk_decision_id = ?", Integer.class, decisionId))
                .isEqualTo(3);
        assertThat(jdbc.queryForList("""
                SELECT rule_sequence FROM risk_decision_rule
                WHERE risk_decision_id = ? ORDER BY rule_sequence
                """, Integer.class, decisionId)).containsExactly(10, 50, 60);

        RiskDecisionDetail detail = persistence.require(decisionId);
        assertThat(detail.summary().resultClass()).isEqualTo(RiskDecisionClass.REJECTED);
        assertThat(detail.summary().failedRuleCount()).isEqualTo(1);
        assertThat(detail.summary().policyConfigId()).isEqualTo(policy.id());
        assertThat(detail.summary().correlationId()).isEqualTo("risk-persistence-it");
        assertThat(detail.policySnapshot()).isEqualTo(policy);
        assertThat(detail.ruleResults()).hasSize(3);
        assertThat(detail.ruleResults().get(1).details()).containsEntry("source", "market-candle");

        mvc.perform(get("/api/risk/decisions/{id}", decisionId).header(KEY_HEADER, API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.id").value(decisionId.toString()))
                .andExpect(jsonPath("$.summary.resultClass").value("REJECTED"))
                .andExpect(jsonPath("$.summary.failedRuleCount").value(1))
                .andExpect(jsonPath("$.ruleResults[1].code").value("MARKET_STATE_FRESHNESS"))
                .andExpect(jsonPath("$.ruleResults[1].outcome").value("HARD_REJECT"));

        mvc.perform(get("/api/risk/decisions").header(KEY_HEADER, API_KEY)
                        .param("page", "0").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(decisionId.toString()))
                .andExpect(jsonPath("$.items[0].resultClass").value("REJECTED"))
                .andExpect(jsonPath("$.items[0].rewardRiskRatio").value(2.5));
    }

    @Test
    void detailNotFoundReturnsStableRiskErrorCode() throws Exception {
        mvc.perform(get("/api/risk/decisions/{id}", UUID.randomUUID()).header(KEY_HEADER, API_KEY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("RISK_DECISION_NOT_FOUND"));
    }

    @Test
    void unauthenticatedDecisionHistoryAndDetailRemainProtected() throws Exception {
        mvc.perform(get("/api/risk/decisions"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/risk/decisions/{id}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void legacyDecisionRowsRemainReadableAfterForwardMigration() throws Exception {
        UUID legacyOrder = UUID.randomUUID();
        seedOrder(legacyOrder, "legacy-decision-" + legacyOrder);
        UUID decisionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO risk_decision (
                    id, order_id, decision, reason_codes, risk_budget, estimated_risk,
                    estimated_notional, available_cash, open_positions, realized_pnl_today,
                    daily_loss_limit, policy_version, evaluated_at)
                VALUES (?, ?, 'APPROVED', '[]'::jsonb, 250, 100, 5000, 80000, 0, 0, 1000, 'risk-policy-v1', ?)
                """, decisionId, legacyOrder, java.sql.Timestamp.from(NOW.minusSeconds(60)));

        RiskDecisionDetail detail = persistence.require(decisionId);
        assertThat(detail.summary().decision()).isEqualTo("APPROVED");
        assertThat(detail.summary().resultClass()).isEqualTo(RiskDecisionClass.APPROVED);
        assertThat(detail.summary().policyConfigId()).isNull();
        assertThat(detail.policySnapshot()).isNull();
        assertThat(detail.ruleResults()).isEmpty();

        mvc.perform(get("/api/risk/decisions/{id}", decisionId).header(KEY_HEADER, API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.resultClass").value("APPROVED"))
                .andExpect(jsonPath("$.ruleResults").isEmpty());
    }

    private void seedOrder(UUID id, String idempotencyKey) {
        jdbc.update("""
                INSERT INTO paper_order (
                    id, idempotency_key, request_hash, symbol, side, quantity,
                    reference_price, stop_loss, target_price, signal_generated_at,
                    status, correlation_id)
                VALUES (?, ?, ?, 'TEST', 'BUY', 10, 500, 490, 530, ?, 'NEW', 'risk-persistence-it')
                """, id, idempotencyKey, "a".repeat(64), java.sql.Timestamp.from(NOW.minusSeconds(30)));
    }

    private static RiskContext context(UUID orderId, RiskPolicySnapshot policy) {
        return new RiskContext(
                orderId, "risk-persistence-it", ExecutionMode.PAPER, Side.BUY,
                1L, "NSE", "TEST-EQ", "TEST_STRATEGY", TradingStyle.INTRADAY,
                10, new BigDecimal("500"), new BigDecimal("490"), new BigDecimal("530"),
                NOW.minusSeconds(30), NOW.minusSeconds(10), NOW,
                new BigDecimal("100000"), new BigDecimal("80000"), new BigDecimal("100000"), BigDecimal.ZERO,
                0, BigDecimal.ZERO, 0, new BigDecimal("10000"), new BigDecimal("2000"),
                3, false, false, MarketSessionState.OPEN, true, policy);
    }

    private static RiskPolicySnapshot policy() {
        return new RiskPolicySnapshot(
                UUID.randomUUID(), 1,
                new BigDecimal("0.25"), new BigDecimal("1.00"), 3,
                new BigDecimal("1.50"), new BigDecimal("100000"),
                new BigDecimal("80"), new BigDecimal("25"), 20,
                15, 60, true, true, true,
                new BigDecimal("0.00050000"), new BigDecimal("0.00100000"), "test-v1");
    }
}
