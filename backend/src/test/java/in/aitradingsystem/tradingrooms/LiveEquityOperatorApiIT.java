package in.aitradingsystem.tradingrooms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import in.aitradingsystem.support.AbstractPostgresIT;
import in.aitradingsystem.user.UserAccountStore;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

/**
 * Operator controls for LIVE dispatch: attestation, risk-policy provisioning,
 * and attested release of an unknown order. Uses PostgreSQL; no broker calls.
 */
class LiveEquityOperatorApiIT extends AbstractPostgresIT {
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired UserAccountStore users;
    @Autowired LiveEquityOperatorService operator;

    private static final String ATTEST = """
            {"broker":"ANGEL_ONE","egressIp":"203.0.113.77",
             "operatorReference":"ops-ticket-101","validForSeconds":3600}
            """;

    @Test
    void operatorRoutesRequireTheOperatorCredential() throws Exception {
        long before = attestationCount();
        mvc.perform(post("/api/operator/live-equity/hosting-attestations")
                .contentType("application/json").content(ATTEST))
            .andExpect(result -> assertThat(result.getResponse().getStatus())
                .isNotIn(200, 201));
        assertThat(attestationCount()).isEqualTo(before);
    }

    @Test
    void operatorCanAttestHostingAndRevokeIt() throws Exception {
        mvc.perform(post("/api/operator/live-equity/hosting-attestations")
                .header("X-API-Key", API_KEY).contentType("application/json").content(ATTEST))
            .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(201));
        UUID id = jdbc.sql("""
                SELECT id FROM live_equity_hosting_attestation
                 WHERE egress_ip = '203.0.113.77' AND revoked_at IS NULL
                 ORDER BY attested_at DESC LIMIT 1
                """).query(UUID.class).single();
        assertThat(angelOneAttested()).isTrue();

        mvc.perform(delete("/api/operator/live-equity/hosting-attestations/" + id)
                .header("X-API-Key", API_KEY).contentType("application/json")
                .content("{\"operatorReference\":\"ops-ticket-102\"}"))
            .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(200));
        assertThat(jdbc.sql("SELECT revoked_by_reference FROM live_equity_hosting_attestation WHERE id=:id")
            .param("id", id).query(String.class).single()).isEqualTo("ops-ticket-102");
    }

    @Test
    void attestationRejectsOutOfRangeValidityAndBadAddresses() {
        assertThatThrownBy(() -> operator.attestHosting("ANGEL_ONE", "203.0.113.9", "ref",
                Duration.ofDays(91)))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> operator.attestHosting("ANGEL_ONE", "not an ip", "ref",
                Duration.ofDays(1)))
            .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void riskPolicyIsProvisionedAttributedAndBounded() throws Exception {
        UUID customer = customer();
        mvc.perform(put("/api/operator/live-equity/customers/" + customer + "/risk-policy")
                .header("X-API-Key", API_KEY).contentType("application/json").content("""
                    {"maxAccountIntradayBuyInr":100000,"maxOrderValueInr":50000,
                     "maxOrderQuantity":100,"dailyRealizedLossLimitInr":5000,
                     "operatorReference":"ops-ticket-201"}
                    """))
            .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(200));
        assertThat(jdbc.sql("""
                SELECT operator_reference FROM customer_live_equity_risk_policy WHERE user_id=:u
                """).param("u", customer).query(String.class).single()).isEqualTo("ops-ticket-201");

        mvc.perform(put("/api/operator/live-equity/customers/" + customer + "/risk-policy")
                .header("X-API-Key", API_KEY).contentType("application/json").content("""
                    {"maxAccountIntradayBuyInr":100000,"maxOrderValueInr":50000,
                     "maxOrderQuantity":10001,"dailyRealizedLossLimitInr":5000,
                     "operatorReference":"ops-ticket-202"}
                    """))
            .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(400));
    }

    @Test
    void riskPolicyCannotBeSetForANonCustomerAccount() {
        assertThatThrownBy(() -> operator.setRiskPolicy(UUID.randomUUID(),
                new LiveEquityOperatorService.RiskPolicyInput(100_000L, 50_000L, 100, 5_000L),
                "ops-ticket-301"))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void unknownOrderWithNoBrokerRecordIsReleasedOnlyWithAnEvidenceReference() {
        UUID owner = customer();
        UUID room = liveAngelRoom(owner);
        UUID order = seedOrder(owner, room, "UNKNOWN", null);

        operator.resolveNotPlaced(order, "ops-ticket-401", "broker-export-2026-11-28-0912");

        assertThat(jdbc.sql("SELECT state FROM customer_live_equity_order WHERE id=:id")
            .param("id", order).query(String.class).single()).isEqualTo("REJECTED");
        assertThat(jdbc.sql("""
                SELECT evidence_reference FROM live_equity_order_operator_resolution WHERE order_id=:id
                """).param("id", order).query(String.class).single())
            .isEqualTo("broker-export-2026-11-28-0912");
    }

    @Test
    void acknowledgedOrderWithABrokerIdCannotBeReleasedByOperator() {
        UUID owner = customer();
        UUID room = liveAngelRoom(owner);
        UUID order = seedOrder(owner, room, "ACKNOWLEDGED", "AO-LIVE-1");

        assertThatThrownBy(() -> operator.resolveNotPlaced(order, "ops-ticket-402", "evidence"))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT));
        assertThat(jdbc.sql("SELECT state FROM customer_live_equity_order WHERE id=:id")
            .param("id", order).query(String.class).single()).isEqualTo("ACKNOWLEDGED");
    }

    @Test
    void submittingOrderWithoutEvidenceIsNotReleased() {
        UUID owner = customer();
        UUID room = liveAngelRoom(owner);
        UUID order = seedOrder(owner, room, "SUBMITTING", null);

        assertThatThrownBy(() -> operator.resolveNotPlaced(order, "ops-ticket-403", "  "))
            .isInstanceOf(ResponseStatusException.class);
        assertThat(jdbc.sql("SELECT state FROM customer_live_equity_order WHERE id=:id")
            .param("id", order).query(String.class).single()).isEqualTo("SUBMITTING");
    }

    // ----- helpers -----

    private boolean angelOneAttested() {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM live_equity_hosting_attestation
                 WHERE broker='ANGEL_ONE' AND revoked_at IS NULL
                   AND attested_at <= now() AND expires_at > now())
                """).query(Boolean.class).single();
    }

    private long attestationCount() {
        return jdbc.sql("SELECT COUNT(*) FROM live_equity_hosting_attestation WHERE egress_ip='203.0.113.77'")
            .query(Long.class).single();
    }

    private UUID seedOrder(UUID owner, UUID room, String state, String brokerOrderId) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
            INSERT INTO customer_live_equity_order
              (id,room_id,user_id,mode,broker,instrument_id,trading_symbol,exchange,side,quantity,
               limit_price,idempotency_key,payload_hash,broker_tag,state,broker_order_id,
               filled_quantity,outcome_code)
            VALUES (:id,:room,:owner,'LIVE','ANGEL_ONE',42,'SBIN-EQ','NSE','BUY',10,100.00,
                    :idem,:hash,:tag,:state,:bid,0,'SEEDED')
            """)
            .param("id", id).param("room", room).param("owner", owner)
            .param("idem", "op-" + id.toString().substring(0, 8))
            .param("hash", "0".repeat(64))
            .param("tag", "MTop" + id.toString().substring(0, 8))
            .param("state", state).param("bid", brokerOrderId).update();
        return id;
    }

    private UUID customer() {
        UUID suffix = UUID.randomUUID();
        var user = users.createUser("live-op-" + suffix, "Live operator",
            "live-op-" + suffix + "@example.test", "test");
        jdbc.sql("UPDATE app_user SET role='CUSTOMER' WHERE id=:id").param("id", user.id()).update();
        return user.id();
    }

    private UUID liveAngelRoom(UUID owner) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
            INSERT INTO customer_trading_room
              (id,user_id,name,focus,planned_budget_inr,risk_preference,mode,market_scope)
            VALUES (:id,:owner,:name,'INTRADAY',20000,'BALANCED','LIVE','EQUITY')
            """)
            .param("id", id).param("owner", owner).param("name", "Live op " + id).update();
        jdbc.sql("INSERT INTO customer_live_room_broker (room_id,user_id,mode,broker) VALUES (:r,:o,'LIVE','ANGEL_ONE')")
            .param("r", id).param("o", owner).update();
        return id;
    }
}
