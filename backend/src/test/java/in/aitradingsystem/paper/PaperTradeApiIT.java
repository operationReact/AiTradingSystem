package in.aitradingsystem.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.aitradingsystem.support.AbstractPostgresIT;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

class PaperTradeApiIT extends AbstractPostgresIT {
    private static final String KEY_HEADER = "X-API-Key";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;

    @AfterEach
    void restoreState() throws Exception {
        mvc.perform(put("/api/risk/emergency-stop").header(KEY_HEADER, API_KEY)
            .contentType(MediaType.APPLICATION_JSON).content("{\"halted\":false}"));
        for (JsonNode position : portfolio().get("positions")) {
            String symbol = position.get("symbol").asText();
            int qty = position.get("quantity").asInt();
            String mark = position.get("markPrice").asText();
            simulate(newKey(), body(symbol, "SELL", qty, mark, null, null, Instant.now()))
                .andExpect(status().isCreated());
        }
    }

    @Test
    void acceptsSmallPaperTrade() throws Exception {
        simulate(newKey(), buy("RELIANCE", 10))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.order.status").value("FILLED"))
            .andExpect(jsonPath("$.decision.decision").value("APPROVED"))
            .andExpect(jsonPath("$.fill.feeAmount").value(2.5));
    }

    @Test
    void rejectsRiskAboveBudgetWithReasonCodes() throws Exception {
        simulate(newKey(), buy("RELIANCE", 100))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.reasonCodes[0]").value("RISK_BUDGET_EXCEEDED"));
    }

    @Test
    void duplicateIdempotencyKeyReplaysWithoutSecondFill() throws Exception {
        String key = newKey();
        String payload = buy("TCS", 10);
        simulate(key, payload).andExpect(status().isCreated());
        simulate(key, payload).andExpect(status().isOk())
            .andExpect(jsonPath("$.replayed").value(true));
        assertThat(positionQuantity("TCS")).isEqualTo(10);
    }

    @Test
    void idempotencyKeyReusedWithDifferentPayloadIsConflict() throws Exception {
        String key = newKey();
        simulate(key, buy("TCS", 10)).andExpect(status().isCreated());
        simulate(key, buy("TCS", 5)).andExpect(status().isConflict());
        assertThat(positionQuantity("TCS")).isEqualTo(10);
    }

    @Test
    void duplicateReplayPreservesFillAndRiskDecisionCounts() throws Exception {
        String key = newKey();
        String payload = buy("TCS", 10);
        BigDecimal cashBefore = cash();
        simulate(key, payload).andExpect(status().isCreated());
        BigDecimal cashAfter = cash();
        simulate(key, payload).andExpect(status().isOk())
            .andExpect(jsonPath("$.replayed").value(true));

        assertThat(cash()).isEqualByComparingTo(cashAfter);
        assertThat(cashAfter).isLessThan(cashBefore);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM paper_order WHERE idempotency_key = :key")
            .param("key", key).query(Long.class).single()).isEqualTo(1L);
        assertThat(jdbc.sql("""
            SELECT COUNT(*) FROM paper_fill f
            JOIN paper_order o ON o.id = f.order_id WHERE o.idempotency_key = :key
            """).param("key", key).query(Long.class).single()).isEqualTo(1L);
        assertThat(jdbc.sql("""
            SELECT COUNT(*) FROM risk_decision d
            JOIN paper_order o ON o.id = d.order_id WHERE o.idempotency_key = :key
            """).param("key", key).query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void rejectedOrderReplayDoesNotCreateFillOrExtraRiskDecision() throws Exception {
        String key = newKey();
        String payload = buy("RELIANCE", 100);
        simulate(key, payload).andExpect(status().isUnprocessableEntity());
        simulate(key, payload).andExpect(status().isUnprocessableEntity());
        assertThat(jdbc.sql("""
            SELECT COUNT(*) FROM paper_fill f
            JOIN paper_order o ON o.id = f.order_id WHERE o.idempotency_key = :key
            """).param("key", key).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("""
            SELECT COUNT(*) FROM risk_decision d
            JOIN paper_order o ON o.id = d.order_id WHERE o.idempotency_key = :key
            """).param("key", key).query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void filledOrderCreatesExactlyOneAuditEventAndReplayDoesNotDuplicateIt() throws Exception {
        String key = newKey();
        String payload = buy("TCS", 10);
        simulate(key, payload).andExpect(status().isCreated());
        simulate(key, payload).andExpect(status().isOk());

        assertThat(auditCountForKey(key, "ORDER_FILLED")).isEqualTo(1L);
        assertThat(auditCountForKey(key, "ORDER_REJECTED")).isZero();
    }

    @Test
    void rejectedOrderCreatesExactlyOneAuditEventAndReplayDoesNotDuplicateIt() throws Exception {
        String key = newKey();
        String payload = buy("RELIANCE", 100);
        simulate(key, payload).andExpect(status().isUnprocessableEntity());
        simulate(key, payload).andExpect(status().isUnprocessableEntity());

        assertThat(auditCountForKey(key, "ORDER_REJECTED")).isEqualTo(1L);
        assertThat(auditCountForKey(key, "ORDER_FILLED")).isZero();
    }

    private long auditCountForKey(String key, String eventType) {
        return jdbc.sql("""
            SELECT COUNT(*) FROM audit_event a
            JOIN paper_order o ON a.entity_id = CAST(o.id AS text)
            WHERE o.idempotency_key = :key AND a.event_type = :eventType
              AND a.entity_type = 'PAPER_ORDER'
            """).param("key", key).param("eventType", eventType)
            .query(Long.class).single();
    }

    @Test
    void concurrentDuplicateRequestsCreateExactlyOneFill() throws Exception {
        String key = newKey();
        String payload = buy("INFY", 10);
        List<Integer> codes = runConcurrently(2,
            () -> simulate(key, payload).andReturn().getResponse().getStatus());
        assertThat(codes).containsExactlyInAnyOrder(201, 200);
        assertThat(positionQuantity("INFY")).isEqualTo(10);
    }

    @Test
    void concurrentDifferentOrdersForSameSymbolOnlyOneIsFilled() throws Exception {
        List<Integer> codes = runConcurrently(2,
            () -> simulate(newKey(), buy("WIPRO", 10)).andReturn().getResponse().getStatus());
        assertThat(codes).containsExactlyInAnyOrder(201, 422);
        assertThat(positionQuantity("WIPRO")).isEqualTo(10);
    }

    @Test
    void staleSignalIsRejected() throws Exception {
        String stale = body("HDFCBANK", "BUY", 10, "500", "490", "530", Instant.now().minus(2, ChronoUnit.DAYS));
        simulate(newKey(), stale).andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.reasonCodes[0]").value("STALE_SIGNAL"));
    }

    @Test
    void invalidBuyLevelsAreBadRequest() throws Exception {
        simulate(newKey(), body("TCS", "BUY", 10, "500", "505", "530", Instant.now()))
            .andExpect(status().isBadRequest());
    }

    @Test
    void emergencyStopBlocksNewOrders() throws Exception {
        mvc.perform(put("/api/risk/emergency-stop").header(KEY_HEADER, API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"halted\":true,\"reason\":\"Integration test emergency stop\"}"))
            .andExpect(status().isOk());
        simulate(newKey(), buy("TCS", 10)).andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.reasonCodes[0]").value("EMERGENCY_STOP_ACTIVE"));
    }

    @Test
    void riskRejectedBuyDoesNotChangeCashOrCreateFillOrCashEntry() throws Exception {
        String key = newKey();
        BigDecimal cashBefore = cash();
        simulate(key, buy("RELIANCE", 100))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.reasonCodes[0]").value("RISK_BUDGET_EXCEEDED"));

        assertThat(cash()).isEqualByComparingTo(cashBefore);
        assertThat(jdbc.sql("""
            SELECT COUNT(*) FROM paper_cash_entry e
            JOIN paper_order o ON o.id = e.order_id
            WHERE o.idempotency_key = :key
            """).param("key", key).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("""
            SELECT COUNT(*) FROM paper_fill f
            JOIN paper_order o ON o.id = f.order_id
            WHERE o.idempotency_key = :key
            """).param("key", key).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("""
            SELECT COUNT(*) FROM risk_decision d
            JOIN paper_order o ON o.id = d.order_id
            WHERE o.idempotency_key = :key AND d.decision = 'REJECTED'
            """).param("key", key).query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void buySellAccountingReconcilesToLedger() throws Exception {
        BigDecimal cashBefore = cash();
        BigDecimal realizedBefore = realizedPnl();

        simulate(newKey(), buy("TCS", 10)).andExpect(status().isCreated());
        assertThat(cash()).isEqualByComparingTo(cashBefore.subtract(new BigDecimal("5007.50")));

        simulate(newKey(), body("TCS", "SELL", 10, "520", null, null, Instant.now()))
            .andExpect(status().isCreated());
        assertThat(realizedPnl().subtract(realizedBefore)).isEqualByComparingTo("184.70");
        assertThat(cash().subtract(cashBefore)).isEqualByComparingTo("184.70");
        assertThat(portfolio().get("cashReconciled").asBoolean()).isTrue();
    }

    @Test
    void sellWithoutHoldingsIsRejected() throws Exception {
        simulate(newKey(), body("ZOMATO", "SELL", 10, "200", null, null, Instant.now()))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.reasonCodes[0]").value("INSUFFICIENT_HOLDINGS"));
    }

    @Test
    void flywayMigrationTwoIsApplied() {
        Long applied = jdbc.sql("SELECT COUNT(*) FROM flyway_schema_history WHERE version = '2' AND success")
            .query(Long.class).single();
        assertThat(applied).isEqualTo(1L);
    }

    @Test
    void protectedEndpointsRequireApiKey() throws Exception {
        mvc.perform(get("/api/paper-trades/portfolio")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/paper-trades/portfolio").header(KEY_HEADER, "wrong-key-value-0000"))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/health")).andExpect(status().isOk());
    }

    @Test
    void missingIdempotencyKeyIsBadRequest() throws Exception {
        mvc.perform(post("/api/paper-trades/simulate").header(KEY_HEADER, API_KEY)
                .contentType(MediaType.APPLICATION_JSON).content(buy("TCS", 10)))
            .andExpect(status().isBadRequest());
    }

    private ResultActions simulate(String key, String body) throws Exception {
        return mvc.perform(post("/api/paper-trades/simulate").header(KEY_HEADER, API_KEY)
            .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private JsonNode portfolio() throws Exception {
        String body = mvc.perform(get("/api/paper-trades/portfolio").header(KEY_HEADER, API_KEY))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private int positionQuantity(String symbol) throws Exception {
        for (JsonNode p : portfolio().get("positions")) {
            if (symbol.equals(p.get("symbol").asText())) {
                return p.get("quantity").asInt();
            }
        }
        return 0;
    }

    private BigDecimal cash() throws Exception {
        return new BigDecimal(portfolio().get("cash").asText());
    }

    private BigDecimal realizedPnl() throws Exception {
        return new BigDecimal(portfolio().get("realizedPnl").asText());
    }

    private static String newKey() {
        return "it-" + UUID.randomUUID();
    }

    private static String buy(String symbol, int qty) {
        return body(symbol, "BUY", qty, "500", "490", "530", Instant.now());
    }

    private static String body(String symbol, String side, int qty, String entry, String stop, String target,
                               Instant signal) {
        StringBuilder sb = new StringBuilder("{\"symbol\":\"").append(symbol).append("\",\"side\":\"").append(side)
            .append("\",\"quantity\":").append(qty).append(",\"entryPrice\":").append(entry);
        if (stop != null) sb.append(",\"stopLoss\":").append(stop);
        if (target != null) sb.append(",\"target\":").append(target);
        return sb.append(",\"signalGeneratedAt\":\"").append(signal).append("\"}").toString();
    }

    private static List<Integer> runConcurrently(int threads, Callable<Integer> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(task));
            }
            List<Integer> codes = new ArrayList<>();
            for (Future<Integer> f : futures) {
                codes.add(f.get());
            }
            return codes;
        } finally {
            pool.shutdownNow();
        }
    }
}
