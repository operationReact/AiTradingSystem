package in.aitradingsystem.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.aitradingsystem.support.AbstractApprovalPaperIT;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

class PaperOrderApiIT extends AbstractApprovalPaperIT {
    private static final String KEY_HEADER = "X-API-Key";
    private static final String SYMBOL = "P6API";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;

    @AfterEach
    void cleanup() {
        deleteApprovals(SYMBOL);
        jdbc.sql("DELETE FROM paper_reconciliation_difference").update();
        jdbc.sql("DELETE FROM paper_reconciliation_run").update();
        jdbc.sql("DELETE FROM risk_decision_rule WHERE risk_decision_id IN (SELECT d.id FROM risk_decision d JOIN paper_order o ON o.id = d.order_id WHERE o.symbol = :symbol)")
            .param("symbol", SYMBOL).update();
        jdbc.sql("DELETE FROM risk_decision WHERE order_id IN (SELECT id FROM paper_order WHERE symbol = :symbol)")
            .param("symbol", SYMBOL).update();
        jdbc.sql("DELETE FROM audit_event WHERE entity_type = 'PAPER_ORDER' AND entity_id IN (SELECT id::text FROM paper_order WHERE symbol = :symbol)")
            .param("symbol", SYMBOL).update();
        jdbc.sql("DELETE FROM paper_fill WHERE order_id IN (SELECT id FROM paper_order WHERE symbol = :symbol)")
            .param("symbol", SYMBOL).update();
        jdbc.sql("""
                UPDATE paper_account
                   SET cash_balance = cash_balance - COALESCE((
                       SELECT SUM(e.amount)
                         FROM paper_cash_entry e
                         JOIN paper_order o ON o.id = e.order_id
                        WHERE o.symbol = :symbol), 0),
                       updated_at = now()
                 WHERE id = 1
                """).param("symbol", SYMBOL).update();
        jdbc.sql("DELETE FROM paper_cash_entry WHERE order_id IN (SELECT id FROM paper_order WHERE symbol = :symbol)")
            .param("symbol", SYMBOL).update();
        jdbc.sql("DELETE FROM paper_order WHERE symbol = :symbol").param("symbol", SYMBOL).update();
        jdbc.sql("DELETE FROM paper_position WHERE symbol = :symbol").param("symbol", SYMBOL).update();
        jdbc.sql("UPDATE trading_control SET new_orders_halted = FALSE, reason = NULL WHERE id = 1").update();
    }

    @Test
    void canonicalMarketOrderCanBeCreatedAndFetched() throws Exception {
        market(SYMBOL, new java.math.BigDecimal("100"));
        String key = "p6-api-" + UUID.randomUUID();
        String body = marketBuy("DELIVERY");
        String created = mvc.perform(post("/api/paper-orders")
                .header(KEY_HEADER, API_KEY).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.order.symbol").value(SYMBOL))
            .andExpect(jsonPath("$.order.productType").value("DELIVERY"))
            .andExpect(jsonPath("$.order.orderType").value("MARKET"))
            .andReturn().getResponse().getContentAsString();

        UUID orderId = UUID.fromString(json.readTree(created).path("order").path("id").asText());
        mvc.perform(get("/api/paper-orders/{id}", orderId).header(KEY_HEADER, API_KEY))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.order.id").value(orderId.toString()));
    }

    @Test
    void canonicalRiskRejectionIsPersistedOrderStateNotCompatibility422() throws Exception {
        jdbc.sql("UPDATE trading_control SET new_orders_halted = TRUE, reason = 'api rejection evidence' WHERE id = 1").update();

        mvc.perform(post("/api/paper-orders")
                .header(KEY_HEADER, API_KEY).header("Idempotency-Key", "p6-reject-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content(marketBuy("DELIVERY")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.order.status").value("REJECTED"))
            .andExpect(jsonPath("$.decision.decision").exists());
    }

    @Test
    void unsupportedLimitOrderIsRejectedBeforeDurableIntent() throws Exception {
        String key = "p6-limit-" + UUID.randomUUID();
        String body = marketBuy("DELIVERY").replace("\"orderType\":\"MARKET\"",
            "\"orderType\":\"LIMIT\",\"limitPrice\":99.50");

        mvc.perform(post("/api/paper-orders")
                .header(KEY_HEADER, API_KEY).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest());

        assertThat(jdbc.sql("SELECT COUNT(*) FROM paper_order WHERE idempotency_key = :key")
            .param("key", key).query(Integer.class).single()).isZero();
    }

    @Test
    void cancellableReadyOrderTransitionsToCancelledWithoutSettlement() throws Exception {
        UUID orderId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                INSERT INTO paper_order (
                    id, idempotency_key, request_hash, symbol, side, quantity, submitted_quantity,
                    filled_quantity, order_type, product_type, reference_price, stop_loss, target_price,
                    status, created_at, updated_at)
                VALUES (:id, :key, :hash, :symbol, 'BUY', 1, 1, 0, 'MARKET', 'DELIVERY', 100, 95, 110,
                        'READY', :now, :now)
                """)
            .param("id", orderId).param("key", "cancel-" + orderId).param("hash", "c".repeat(64))
            .param("symbol", SYMBOL).param("now", now).update();

        mvc.perform(post("/api/paper-orders/{id}/cancel", orderId).header(KEY_HEADER, API_KEY))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.order.status").value("CANCELLED"));

        assertThat(jdbc.sql("SELECT COUNT(*) FROM paper_fill WHERE order_id = :id")
            .param("id", orderId).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM paper_cash_entry WHERE order_id = :id")
            .param("id", orderId).query(Integer.class).single()).isZero();
    }

    @Test
    void positionsEndpointKeepsDeliveryAndIntradaySeparate() throws Exception {
        seedPosition("DELIVERY", 2);
        seedPosition("INTRADAY", 3);

        String content = mvc.perform(get("/api/paper-positions").header(KEY_HEADER, API_KEY))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode root = json.readTree(content);
        long matching = java.util.stream.StreamSupport.stream(root.spliterator(), false)
            .filter(node -> SYMBOL.equals(node.path("symbol").asText()))
            .count();
        assertThat(matching).isEqualTo(2);
    }

    @Test
    void reconciliationCanBeRunAndListed() throws Exception {
        String result = mvc.perform(post("/api/paper-reconciliation").header(KEY_HEADER, API_KEY))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.runId").exists())
            .andReturn().getResponse().getContentAsString();
        String runId = json.readTree(result).path("runId").asText();

        mvc.perform(get("/api/paper-reconciliation/runs").header(KEY_HEADER, API_KEY))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(runId));
    }

    private void seedPosition(String productType, int quantity) {
        jdbc.sql("""
                INSERT INTO paper_position (
                    instrument_id, exchange, symbol, product_type, quantity, cost_basis, average_price,
                    realized_pnl, opened_at, updated_at)
                VALUES (NULL, 'PAPER', :symbol, :productType, :quantity, :costBasis, 100.000000, 0, now(), now())
                """)
            .param("symbol", SYMBOL).param("productType", productType).param("quantity", quantity)
            .param("costBasis", java.math.BigDecimal.valueOf(quantity * 100L)).update();
    }

    private static String marketBuy(String productType) {
        return """
            {"symbol":"%s","side":"BUY","quantity":1,"referencePrice":100.00,
             "stopLoss":95.00,"target":110.00,"signalGeneratedAt":"%s",
             "productType":"%s","orderType":"MARKET"}
            """.formatted(SYMBOL, NOW, productType);
    }
}
