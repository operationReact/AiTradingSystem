package in.aitradingsystem.tradingrooms;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.List;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.aitradingsystem.instrument.InstrumentMasterService;
import in.aitradingsystem.provider.angelone.CustomerAngelOneSessionService;
import in.aitradingsystem.provider.upstox.CustomerUpstoxOAuthService;
import in.aitradingsystem.support.AbstractPostgresIT;
import in.aitradingsystem.user.UserAccountStore;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Database-backed checks for the V100 custody migration and the broker-history
 * reconciliation SQL. Orders are seeded directly (the gated claim path cannot
 * produce a reconcilable order in this release); broker reads are mocked and no
 * real broker order is ever placed.
 */
class CustomerLiveEquityReconciliationIT extends AbstractPostgresIT {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired JdbcClient jdbc;
    @Autowired UserAccountStore users;
    @Autowired CustomerLiveEquityOrderService orders;
    @Autowired LiveEquityReconciliationCoordinator reconciliation;
    @MockitoBean CustomerUpstoxOAuthService upstox;
    @MockitoBean CustomerAngelOneSessionService angel;
    @MockitoBean InstrumentMasterService instruments;

    /** Readiness reads the owner's broker status; the stub is connected, never a fallback. */
    @BeforeEach
    void connectedUpstoxSession() {
        when(upstox.status(any())).thenReturn(new CustomerUpstoxOAuthService.Status(
            true, true, "UPSTOX", "***456", Instant.now().plusSeconds(3600), false));
    }

    @Test
    void fullFillReconciliationPersistsFilledStateAndQuantity() throws Exception {
        UUID owner = customer();
        UUID room = liveUpstoxRoom(owner);
        String tag = LiveEquityCorrelationTag.generate();
        String brokerId = "UP" + UUID.randomUUID().toString().substring(0, 8);
        UUID orderId = seedReconcilableOrder(owner, room, tag);

        JsonNode history = JSON.readTree("""
            {"data":[{"order_id":"%2$s","tag":"%1$s","exchange":"NSE",
              "trading_symbol":"RELIANCE","transaction_type":"BUY","quantity":10,
              "price":100.00,"product":"I","order_type":"LIMIT","validity":"DAY",
              "status":"complete","filled_quantity":10,"average_price":100.10}]}
            """.formatted(tag, brokerId));
        when(upstox.orderHistoryByTag(eq(owner), eq(tag))).thenReturn(
            new CustomerUpstoxOAuthService.AccountData("UPSTOX", Instant.now(),
                history.get("data")));

        var result = reconciliation.reconcile(owner, room, orderId);
        assertThat(result.state()).isEqualTo("FILLED");
        assertThat(result.filledQuantity()).isEqualTo(10);
        assertThat(result.brokerOrderId()).isEqualTo(brokerId);
    }

    @Test
    void emptyBrokerHistoryLeavesOrderReservedAndRecordsOutcome() throws Exception {
        UUID owner = customer();
        UUID room = liveUpstoxRoom(owner);
        String tag = LiveEquityCorrelationTag.generate();
        UUID orderId = seedReconcilableOrder(owner, room, tag);

        when(upstox.orderHistoryByTag(eq(owner), eq(tag))).thenReturn(
            new CustomerUpstoxOAuthService.AccountData("UPSTOX", Instant.now(),
                JSON.createArrayNode()));

        var result = reconciliation.reconcile(owner, room, orderId);
        assertThat(result.state()).isEqualTo("UNKNOWN");
        assertThat(result.outcomeCode()).startsWith("RECONCILE_RESERVED:");
        assertThat(result.filledQuantity()).isZero();
    }

    @Test
    void applyRefusesToOverwriteAnOrderThatChangedAfterTheSnapshot() throws Exception {
        UUID owner = customer();
        UUID room = liveUpstoxRoom(owner);
        String tag = LiveEquityCorrelationTag.generate();
        UUID orderId = seedReconcilableOrder(owner, room, tag);
        var seen = orders.loadForReconcile(owner, room, orderId);
        // Another writer records a newer state after the snapshot but before apply.
        jdbc.sql("UPDATE customer_live_equity_order SET state='ACKNOWLEDGED', broker_order_id='UPCHG1' WHERE id=:id")
            .param("id", orderId).update();
        var resolution = LiveEquityReconciler.reconcile(new LiveEquityReconciler.Expected(
            "UPSTOX", tag, "NSE", "RELIANCE", "BUY", 10, new BigDecimal("100.00"), null),
            List.of());

        assertThatThrownBy(() -> orders.applyReconciliation(owner, room, orderId, seen, resolution))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT));
        assertThat(jdbc.sql("SELECT state FROM customer_live_equity_order WHERE id=:id")
            .param("id", orderId).query(String.class).single()).isEqualTo("ACKNOWLEDGED");
    }

    @Test
    void migrationEnforcesUniqueBrokerTagPerBroker() {
        UUID owner = customer();
        UUID room = liveUpstoxRoom(owner);
        String tag = LiveEquityCorrelationTag.generate();
        seedReconcilableOrder(owner, room, tag);
        // A second order reusing the same broker+tag must violate the unique index.
        try {
            seedReconcilableOrder(owner, room, tag);
            assertThat(false).as("expected a unique-tag violation").isTrue();
        } catch (RuntimeException expected) {
            assertThat(expected).isNotNull();
        }
    }

    private UUID seedReconcilableOrder(UUID owner, UUID room, String tag) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
            INSERT INTO customer_live_equity_order
              (id,room_id,user_id,mode,broker,instrument_id,trading_symbol,exchange,
               side,quantity,limit_price,idempotency_key,payload_hash,broker_tag,
               state,filled_quantity,outcome_code)
            VALUES (:id,:room,:owner,'LIVE','UPSTOX',42,'RELIANCE','NSE',
                    'BUY',10,100.00,:idem,:hash,:tag,'UNKNOWN',0,'LIVE_EQUITY_ORDER_UNKNOWN')
            """)
            .param("id", id).param("room", room).param("owner", owner)
            .param("idem", "idem-" + id.toString().substring(0, 8))
            .param("hash", "0".repeat(64)).param("tag", tag).update();
        return id;
    }

    private UUID customer() {
        UUID suffix = UUID.randomUUID();
        var user = users.createUser("equity-reconcile-" + suffix, "Equity reconcile",
            "equity-reconcile-" + suffix + "@example.test", "test");
        jdbc.sql("UPDATE app_user SET role='CUSTOMER' WHERE id=:id")
            .param("id", user.id()).update();
        return user.id();
    }

    private UUID liveUpstoxRoom(UUID owner) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
            INSERT INTO customer_trading_room
              (id,user_id,name,focus,planned_budget_inr,risk_preference,mode,market_scope)
            VALUES (:id,:owner,:name,'INTRADAY',20000,'BALANCED','LIVE','EQUITY')
            """)
            .param("id", id).param("owner", owner).param("name", "Live " + id).update();
        jdbc.sql("""
            INSERT INTO customer_live_room_broker (room_id,user_id,mode,broker)
            VALUES (:room,:owner,'LIVE','UPSTOX')
            """)
            .param("room", id).param("owner", owner).update();
        return id;
    }
}
