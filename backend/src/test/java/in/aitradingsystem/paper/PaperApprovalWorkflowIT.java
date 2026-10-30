package in.aitradingsystem.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.aitradingsystem.support.AbstractApprovalPaperIT;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

class PaperApprovalWorkflowIT extends AbstractApprovalPaperIT {
    private static final String SYMBOL = "P7FLOW";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    private BigDecimal cashBefore;

    @BeforeEach
    void seedMarketAndAccount() {
        market(SYMBOL, new BigDecimal("100"));
        cashBefore = approvalJdbc.sql("SELECT cash_balance FROM paper_account WHERE id = 1").query(BigDecimal.class).single();
        approvalJdbc.sql("UPDATE trading_control SET new_orders_halted = FALSE WHERE id = 1").update();
    }

    @AfterEach
    void cleanupOrders() {
        deleteApprovals(SYMBOL);
        approvalJdbc.sql("DELETE FROM risk_decision_rule WHERE risk_decision_id IN (SELECT id FROM risk_decision WHERE order_id IN (SELECT id FROM paper_order WHERE symbol = :s))")
            .param("s", SYMBOL).update();
        for (String table : List.of("risk_decision", "paper_fill", "paper_cash_entry")) {
            approvalJdbc.sql("DELETE FROM " + table + " WHERE order_id IN (SELECT id FROM paper_order WHERE symbol = :s)")
                .param("s", SYMBOL).update();
        }
        approvalJdbc.sql("DELETE FROM paper_position WHERE symbol = :s").param("s", SYMBOL).update();
        approvalJdbc.sql("DELETE FROM paper_order WHERE symbol = :s").param("s", SYMBOL).update();
        approvalJdbc.sql("UPDATE paper_account SET cash_balance = :cash WHERE id = 1").param("cash", cashBefore).update();
        approvalJdbc.sql("UPDATE trading_control SET new_orders_halted = FALSE WHERE id = 1").update();
    }

    @Test
    void manualOrderWaitsThenApprovalRevalidatesAndSettlesExactlyOnce() throws Exception {
        mode("MANUAL");
        String key = "p7-workflow-" + UUID.randomUUID();
        JsonNode created = create(key, false);
        UUID order = orderId(created);
        assertThat(created.at("/order/status").asText()).isEqualTo("APPROVAL_PENDING");
        assertNoSettlement(order);
        UUID approval = approvalId(order);
        JsonNode view = json.readTree(mvc.perform(get("/api/approvals/{id}", approval).header("X-API-Key", API_KEY))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(json.readTree(view.path("requestSnapshot").asText()).path("symbol").asText()).isEqualTo(SYMBOL);

        decide(approval, "approve");
        decide(approval, "approve");
        JsonNode replay = create(key, false);
        assertThat(replay.at("/order/status").asText()).isEqualTo("FILLED");
        assertThat(replay.path("replayed").asBoolean()).isTrue();
        assertThat(count("paper_fill", order)).isEqualTo(1);
        assertThat(count("paper_cash_entry", order)).isEqualTo(1);
        assertThat(audits("EXECUTION_REVALIDATED", order)).isEqualTo(1);
        assertThat(audits("APPROVAL_APPROVED", approval)).isEqualTo(1);
    }

    @Test
    void legacySimulationCannotBypassManualApproval() throws Exception {
        mode("MANUAL");
        JsonNode created = create("p7-legacy-" + UUID.randomUUID(), true);
        UUID order = orderId(created);
        assertThat(created.at("/order/status").asText()).isEqualTo("APPROVAL_PENDING");
        assertNoSettlement(order);
        decide(approvalId(order), "approve");
        assertThat(orderStatus(order)).isEqualTo("FILLED");
    }

    @Test
    void autoOrderPersistsApprovalAndRevalidationBeforeFill() throws Exception {
        UUID order = orderId(create("p7-auto-" + UUID.randomUUID(), false));
        assertThat(orderStatus(order)).isEqualTo("FILLED");
        assertThat(audits("AUTO_APPROVAL_GRANTED", approvalId(order))).isEqualTo(1);
        assertThat(audits("EXECUTION_REVALIDATED", order)).isEqualTo(1);
        assertThat(count("paper_fill", order)).isEqualTo(1);
    }

    @Test
    void hybridThresholdFallsBackToManualWithoutBypassingHardRisk() throws Exception {
        mode("HYBRID");
        approvalJdbc.sql("UPDATE execution_policy SET max_order_notional = 50 WHERE id = :id")
            .param("id", executionPolicyId).update();
        UUID order = orderId(create("p7-hybrid-" + UUID.randomUUID(), false));
        assertThat(orderStatus(order)).isEqualTo("APPROVAL_PENDING");
        assertNoSettlement(order);
        decide(approvalId(order), "approve");
        assertThat(orderStatus(order)).isEqualTo("FILLED");
    }

    @Test
    void emergencyStopAfterManualRequestBlocksSettlementDespiteApproval() throws Exception {
        mode("MANUAL");
        UUID order = orderId(create("p7-stop-" + UUID.randomUUID(), false));
        approvalJdbc.sql("UPDATE trading_control SET new_orders_halted = TRUE WHERE id = 1").update();
        decide(approvalId(order), "approve");
        assertThat(orderStatus(order)).isEqualTo("FAILED");
        assertNoSettlement(order);
        assertThat(audits("ORDER_EXECUTION_BLOCKED", order)).isEqualTo(1);
    }

    @Test
    void staleQuoteAfterRequestBlocksSettlement() throws Exception {
        mode("MANUAL");
        UUID order = orderId(create("p7-stale-" + UUID.randomUUID(), false));
        approvalJdbc.sql("UPDATE market_candle SET candle_time = :at WHERE trading_symbol = :s")
            .param("at", NOW.minusSeconds(86400).atOffset(java.time.ZoneOffset.UTC)).param("s", SYMBOL).update();
        decide(approvalId(order), "approve");
        assertThat(orderStatus(order)).isEqualTo("FAILED");
        assertNoSettlement(order);
    }

    @Test
    void missingExecutionPolicyFailsClosedInsteadOfUsingLegacyImmediateFill() throws Exception {
        approvalJdbc.sql("UPDATE execution_policy SET active = FALSE WHERE id = :id").param("id", executionPolicyId).update();
        UUID order = orderId(create("p7-no-policy-" + UUID.randomUUID(), false));
        assertThat(orderStatus(order)).isEqualTo("REJECTED");
        assertNoSettlement(order);
    }

    @Test
    void simultaneousIdenticalApprovalsProduceOneSettlementAndAudit() throws Exception {
        mode("MANUAL");
        UUID order = orderId(create("p7-race-" + UUID.randomUUID(), false));
        UUID approval = approvalId(order);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new java.util.concurrent.CyclicBarrier(2);
            Callable<Void> task = () -> { start.await(); decide(approval, "approve"); return null; };
            for (var result : pool.invokeAll(List.of(task, task))) result.get();
        }
        assertThat(orderStatus(order)).isEqualTo("FILLED");
        assertThat(count("paper_fill", order)).isEqualTo(1);
        assertThat(count("paper_cash_entry", order)).isEqualTo(1);
        assertThat(audits("APPROVAL_APPROVED", approval)).isEqualTo(1);
    }

    @Test
    void rejectCancelAndExpiryNeverSettle() throws Exception {
        mode("MANUAL");
        UUID rejected = orderId(create("p7-reject-" + UUID.randomUUID(), false));
        decide(approvalId(rejected), "reject");
        assertThat(orderStatus(rejected)).isEqualTo("REJECTED");
        assertNoSettlement(rejected);
        UUID cancelled = orderId(create("p7-cancel-" + UUID.randomUUID(), false));
        decide(approvalId(cancelled), "cancel");
        assertThat(orderStatus(cancelled)).isEqualTo("CANCELLED");
        assertNoSettlement(cancelled);
        UUID expired = orderId(create("p7-expire-" + UUID.randomUUID(), false));
        UUID approval = approvalId(expired);
        approvalJdbc.sql("UPDATE order_approval SET requested_at = :from, expires_at = :to WHERE id = :id")
            .param("from", NOW.minusSeconds(300).atOffset(java.time.ZoneOffset.UTC))
            .param("to", NOW.atOffset(java.time.ZoneOffset.UTC)).param("id", approval).update();
        decide(approval, "approve");
        assertThat(orderStatus(expired)).isEqualTo("EXPIRED");
        assertNoSettlement(expired);
    }

    @Test
    void queueReadExpiresPendingIntentAndReleasesItWithoutAnApprovalClick() throws Exception {
        mode("MANUAL");
        UUID order = orderId(create("p7-queue-expiry-" + UUID.randomUUID(), false));
        UUID approval = approvalId(order);
        approvalJdbc.sql("UPDATE order_approval SET requested_at = :from, expires_at = :to WHERE id = :id")
            .param("from", NOW.minusSeconds(300).atOffset(java.time.ZoneOffset.UTC))
            .param("to", NOW.atOffset(java.time.ZoneOffset.UTC)).param("id", approval).update();
        mvc.perform(get("/api/approvals").param("state", "PENDING").header("X-API-Key", API_KEY))
            .andExpect(status().isOk());
        assertThat(orderStatus(order)).isEqualTo("EXPIRED");
        assertThat(audits("APPROVAL_EXPIRED", approval)).isEqualTo(1);
        assertNoSettlement(order);
    }

    @Test
    void orderCancellationRevokesApprovalAndLaterApproveCannotSettle() throws Exception {
        mode("MANUAL");
        UUID order = orderId(create("p7-order-cancel-" + UUID.randomUUID(), false));
        UUID approval = approvalId(order);
        mvc.perform(post("/api/paper-orders/{id}/cancel", order).header("X-API-Key", API_KEY))
            .andExpect(status().isOk());
        decide(approval, "approve");
        assertThat(orderStatus(order)).isEqualTo("CANCELLED");
        assertThat(audits("APPROVAL_CANCELLED", approval)).isEqualTo(1);
        assertNoSettlement(order);
    }

    private void mode(String mode) {
        approvalJdbc.sql("UPDATE execution_policy SET approval_mode = :mode WHERE id = :id")
            .param("mode", mode).param("id", executionPolicyId).update();
    }

    private JsonNode create(String key, boolean legacy) throws Exception {
        String body = legacy ? """
            {"symbol":"P7FLOW","side":"BUY","quantity":1,"entryPrice":100,"stopLoss":95,
             "target":110,"signalGeneratedAt":"%s"}
            """.formatted(NOW) : """
            {"symbol":"P7FLOW","side":"BUY","quantity":1,"referencePrice":100,"stopLoss":95,
             "target":110,"signalGeneratedAt":"%s","productType":"DELIVERY","orderType":"MARKET"}
            """.formatted(NOW);
        var response = mvc.perform(post(legacy ? "/api/paper-trades/simulate" : "/api/paper-orders")
            .header("X-API-Key", API_KEY).header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
        assertThat(response.getStatus()).isIn(200, 201);
        return json.readTree(response.getContentAsString());
    }

    private void decide(UUID approval, String action) throws Exception {
        mvc.perform(post("/api/approvals/{id}/{action}", approval, action).header("X-API-Key", API_KEY)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"expectedRowVersion\":0,\"actor\":\"test-operator\",\"reason\":\"test\"}"))
            .andExpect(status().isOk());
    }

    private UUID orderId(JsonNode created) { return UUID.fromString(created.at("/order/id").asText()); }
    private UUID approvalId(UUID order) {
        return approvalJdbc.sql("SELECT approval_id FROM paper_order WHERE id = :id").param("id", order).query(UUID.class).single();
    }
    private String orderStatus(UUID order) {
        return approvalJdbc.sql("SELECT status FROM paper_order WHERE id = :id").param("id", order).query(String.class).single();
    }
    private long count(String table, UUID order) {
        return approvalJdbc.sql("SELECT count(*) FROM " + table + " WHERE order_id = :id").param("id", order).query(Long.class).single();
    }
    private long audits(String event, UUID entity) {
        return approvalJdbc.sql("SELECT count(*) FROM audit_event WHERE event_type = :event AND entity_id = :id")
            .param("event", event).param("id", entity.toString()).query(Long.class).single();
    }
    private void assertNoSettlement(UUID order) {
        assertThat(count("paper_fill", order)).isZero();
        assertThat(count("paper_cash_entry", order)).isZero();
        assertThat(approvalJdbc.sql("SELECT cash_balance FROM paper_account WHERE id = 1").query(BigDecimal.class).single())
            .isEqualByComparingTo(cashBefore);
    }
}
