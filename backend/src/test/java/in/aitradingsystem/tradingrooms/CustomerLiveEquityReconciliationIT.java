package in.aitradingsystem.tradingrooms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

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

    private CustomerUpstoxOAuthService upstox;
    private CustomerLiveEquityOrderService orders;

    private CustomerLiveEquityOrderService service() {
        var angelOne = mock(CustomerAngelOneSessionService.class);
        upstox = mock(CustomerUpstoxOAuthService.class);
        var instruments = mock(InstrumentMasterService.class);
        // readiness() only needs a non-throwing, disconnected broker status here.
        when(upstox.status(any())).thenReturn(new CustomerUpstoxOAuthService.Status(
            true, false, "UPSTOX", null, null, false));
        var readiness = new CustomerExecutionReadinessService(jdbc, angelOne, upstox, instruments);
        return new CustomerLiveEquityOrderService(jdbc, readiness, angelOne, upstox);
    }

    @Test
    void fullFillReconciliationPersistsFilledStateAndQuantity() throws Exception {
        orders = service();
        UUID owner = customer();
        UUID room = liveUpstoxRoom(owner);
        String tag = "MTreconc001a";
        UUID orderId = seedReconcilableOrder(owner, room, tag);

        JsonNode history = JSON.readTree("""
            {"data":[{"order_id":"UP777","tag":"%s","exchange":"NSE",
              "trading_symbol":"RELIANCE","transaction_type":"BUY","quantity":10,
              "price":100.00,"product":"I","order_type":"LIMIT","validity":"DAY",
              "status":"complete","filled_quantity":10,"average_price":100.10}]}
            """.formatted(tag));
        when(upstox.orderHistoryByTag(eq(owner), eq(tag))).thenReturn(
            new CustomerUpstoxOAuthService.AccountData("UPSTOX", Instant.now(),
                history.get("data")));

        var result = orders.reconcileFromBroker(owner, room, orderId);
        assertThat(result.state()).isEqualTo("FILLED");
        assertThat(result.filledQuantity()).isEqualTo(10);
        assertThat(result.brokerOrderId()).isEqualTo("UP777");
    }

    @Test
    void emptyBrokerHistoryLeavesOrderReservedAndRecordsOutcome() throws Exception {
        orders = service();
        UUID owner = customer();
        UUID room = liveUpstoxRoom(owner);
        String tag = "MTreconc002b";
        UUID orderId = seedReconcilableOrder(owner, room, tag);

        when(upstox.orderHistoryByTag(eq(owner), eq(tag))).thenReturn(
            new CustomerUpstoxOAuthService.AccountData("UPSTOX", Instant.now(),
                JSON.createArrayNode()));

        var result = orders.reconcileFromBroker(owner, room, orderId);
        assertThat(result.state()).isEqualTo("UNKNOWN");
        assertThat(result.outcomeCode()).startsWith("RECONCILE_RESERVED:");
        assertThat(result.filledQuantity()).isZero();
    }

    @Test
    void migrationEnforcesUniqueBrokerTagPerBroker() {
        orders = service();
        UUID owner = customer();
        UUID room = liveUpstoxRoom(owner);
        String tag = "MTreconc003c";
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
