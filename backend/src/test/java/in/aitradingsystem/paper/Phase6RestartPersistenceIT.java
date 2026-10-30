package in.aitradingsystem.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.aitradingsystem.support.AbstractApprovalPaperIT;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Restart evidence for the Phase 6 exit gate. The order is executed through the canonical API, then a fresh
 * query-service instance reconstructs fill/cash evidence exclusively from PostgreSQL rather than process memory.
 */
class Phase6RestartPersistenceIT extends AbstractApprovalPaperIT {
    private static final String KEY_HEADER = "X-API-Key";
    private static final String SYMBOL = "P6RESTART";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;

    private BigDecimal originalCash;

    @AfterEach
    void cleanup() {
        deleteApprovals(SYMBOL);
        jdbc.sql("DELETE FROM risk_decision_rule WHERE risk_decision_id IN (SELECT d.id FROM risk_decision d JOIN paper_order o ON o.id = d.order_id WHERE o.symbol = :symbol)")
            .param("symbol", SYMBOL).update();
        jdbc.sql("DELETE FROM risk_decision WHERE order_id IN (SELECT id FROM paper_order WHERE symbol = :symbol)")
            .param("symbol", SYMBOL).update();
        jdbc.sql("DELETE FROM audit_event WHERE entity_type = 'PAPER_ORDER' AND entity_id IN (SELECT id::text FROM paper_order WHERE symbol = :symbol)")
            .param("symbol", SYMBOL).update();
        jdbc.sql("DELETE FROM paper_fill WHERE order_id IN (SELECT id FROM paper_order WHERE symbol = :symbol)")
            .param("symbol", SYMBOL).update();
        jdbc.sql("DELETE FROM paper_cash_entry WHERE order_id IN (SELECT id FROM paper_order WHERE symbol = :symbol)")
            .param("symbol", SYMBOL).update();
        jdbc.sql("DELETE FROM paper_position WHERE symbol = :symbol").param("symbol", SYMBOL).update();
        jdbc.sql("DELETE FROM paper_order WHERE symbol = :symbol").param("symbol", SYMBOL).update();
        if (originalCash != null) {
            jdbc.sql("UPDATE paper_account SET cash_balance = :cash, updated_at = now() WHERE id = 1")
                .param("cash", originalCash).update();
        }
    }

    @Test
    void freshServiceInstanceReconstructsPersistedSettlementAfterExecution() throws Exception {
        market(SYMBOL, new BigDecimal("100"));
        originalCash = jdbc.sql("SELECT cash_balance FROM paper_account WHERE id = 1")
            .query(BigDecimal.class).single();
        String idempotencyKey = "p6-restart-" + UUID.randomUUID();
        String body = """
            {"symbol":"%s","side":"BUY","quantity":1,"referencePrice":100.00,
             "stopLoss":95.00,"target":110.00,"signalGeneratedAt":"%s",
             "productType":"DELIVERY","orderType":"MARKET"}
            """.formatted(SYMBOL, NOW);

        String created = mvc.perform(post("/api/paper-orders")
                .header(KEY_HEADER, API_KEY)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        UUID orderId = UUID.fromString(json.readTree(created).path("order").path("id").asText());

        // Simulate a process/service restart: instantiate a new stateless reader using only the durable datasource.
        PaperLedgerQueryService restartedReader = new PaperLedgerQueryService(jdbc);

        assertThat(restartedReader.recentFills(100))
            .anySatisfy(fill -> {
                assertThat(fill.orderId()).isEqualTo(orderId);
                assertThat(fill.symbol()).isEqualTo(SYMBOL);
                assertThat(fill.quantity()).isEqualTo(1);
            });
        assertThat(restartedReader.recentCashEntries(100))
            .anySatisfy(entry -> {
                assertThat(entry.orderId()).isEqualTo(orderId);
                assertThat(entry.symbol()).isEqualTo(SYMBOL);
                assertThat(entry.entryType()).isEqualTo("BUY_SETTLEMENT");
            });
        assertThat(jdbc.sql("SELECT COUNT(*) FROM paper_position WHERE symbol = :symbol AND product_type = 'DELIVERY'")
            .param("symbol", SYMBOL).query(Integer.class).single()).isEqualTo(1);
    }
}
