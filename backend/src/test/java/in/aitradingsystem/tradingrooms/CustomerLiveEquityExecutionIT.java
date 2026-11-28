package in.aitradingsystem.tradingrooms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.aitradingsystem.instrument.Instrument;
import in.aitradingsystem.instrument.InstrumentMasterService;
import in.aitradingsystem.marketdata.IndianCashMarketCalendar;
import in.aitradingsystem.provider.angelone.AngelOneQuoteDetailParser;
import in.aitradingsystem.provider.angelone.CustomerAngelOneSessionService;
import in.aitradingsystem.provider.upstox.CustomerUpstoxOAuthService;
import in.aitradingsystem.support.AbstractPostgresIT;
import in.aitradingsystem.user.UserAccountStore;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;

/**
 * End-to-end LIVE intraday cash-equity order path against the real PostgreSQL
 * schema. Broker sessions and the market calendar are Mockito mocks; no broker
 * request leaves this JVM and no real order is ever placed.
 */
@TestPropertySource(properties = {
    "minttick.live-equity.dispatch-enabled=true"
})
class CustomerLiveEquityExecutionIT extends AbstractPostgresIT {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CONSENT = LiveEquityOrderCoordinator.CONSENT;
    private static final BigDecimal PRICE = new BigDecimal("100.00");

    @Autowired JdbcClient jdbc;
    @Autowired UserAccountStore users;

    @MockitoBean CustomerAngelOneSessionService angel;
    @MockitoBean CustomerUpstoxOAuthService upstox;
    @MockitoBean InstrumentMasterService instruments;
    @MockitoBean IndianCashMarketCalendar calendar;
    @Autowired CustomerLiveEquityOrderService orders;
    @Autowired LiveEquityReconciliationCoordinator reconciliation;
    @Autowired LiveEquityOrderCoordinator coordinator;

    /** Operator attestation for this test's Angel One hosting evidence. */
    private UUID hostingAttestation;

    @BeforeEach
    void recordHostingAttestation() {
        hostingAttestation = UUID.randomUUID();
        jdbc.sql("""
            INSERT INTO live_equity_hosting_attestation
              (id, broker, egress_ip, operator_reference, attested_at, expires_at)
            VALUES (:id, 'ANGEL_ONE', '203.0.113.10', 'IT-OPERATOR-ATTESTATION', NOW(), NOW() + INTERVAL '1 day')
            """).param("id", hostingAttestation).update();
    }

    @AfterEach
    void revokeHostingAttestation() {
        jdbc.sql("UPDATE live_equity_hosting_attestation SET revoked_at = NOW(), revoked_by_reference = 'IT-CLEANUP' WHERE id = :id")
            .param("id", hostingAttestation).update();
    }

    /** Broker and market stubs; reset per test by Spring's MockitoBean lifecycle. */
    @BeforeEach
    void openBrokerStubs() throws Exception {
        String bid = freshBrokerOrderId();
        when(calendar.isInsideCashSession(any())).thenReturn(true);
        when(instruments.requireActiveInstrument(42L)).thenReturn(new Instrument(42L,
            "NSE_CM", "NSE", "3045", "SBIN-EQ", "State Bank", "EQ", null, null, 1,
            new BigDecimal("0.05"), UUID.randomUUID()));
        when(angel.status(any())).thenReturn(new CustomerAngelOneSessionService.ConnectionStatus(
            true, "ANGEL_ONE", "***xyz", Instant.now().plusSeconds(3600), true));
        when(angel.funds(any())).thenReturn(new CustomerAngelOneSessionService.BrokerRead(
            "ANGEL_ONE", Instant.now(), json("{\"data\":{\"availablecash\":\"100000.00\"}}")));
        when(angel.positions(any())).thenReturn(new CustomerAngelOneSessionService.BrokerRead(
            "ANGEL_ONE", Instant.now(), json("{\"data\":[]}")));
        when(angel.orders(any())).thenReturn(new CustomerAngelOneSessionService.BrokerRead(
            "ANGEL_ONE", Instant.now(), json("{\"data\":[]}")));
        when(angel.quoteDetail(any(), any())).thenReturn(freshQuote());
    }


    @Test
    void acknowledgedBuyIsDispatchedOnceAndRecordedWithBrokerOrderId() throws Exception {
        String bid = freshBrokerOrderId();
        UUID owner = customer();
        UUID room = liveAngelRoom(owner);
        riskPolicy(owner, 100_000, 50_000, 100, 5_000);
        when(angel.submitVerifiedIntradayLimitOnce(any(), any()))
            .thenReturn(new CustomerAngelOneSessionService.OrderSubmission(
                "ACKNOWLEDGED", bid, "ANGEL_ONE_ORDER_ACK"));

        var first = coordinator.submit(owner, room, "idem-ack-0001", 42L, "BUY", 10, PRICE, CONSENT);

        assertThat(first.created()).isTrue();
        assertThat(first.order().state()).isEqualTo("ACKNOWLEDGED");
        assertThat(first.order().brokerOrderId()).isEqualTo(bid);
        assertThat(first.order().filledQuantity()).isZero();
        verify(angel, times(1)).submitVerifiedIntradayLimitOnce(any(), any());
    }

    @Test
    void revokedHostingAttestationBlocksBeforeAnyRowOrRequest() throws Exception {
        jdbc.sql("UPDATE live_equity_hosting_attestation SET revoked_at = NOW(), revoked_by_reference = 'IT-REVOKE' WHERE id = :id")
            .param("id", hostingAttestation).update();
        UUID owner = customer();
        UUID room = liveAngelRoom(owner);
        riskPolicy(owner, 100_000, 50_000, 100, 5_000);

        assertRefused(owner, room, "idem-hosting-0001", "BUY", 10);
        verify(angel, never()).submitVerifiedIntradayLimitOnce(any(), any());
    }

    @Test
    void idempotentReplayReturnsTheStoredOrderAndNeverDispatchesAgain() throws Exception {
        String bid = freshBrokerOrderId();
        UUID owner = customer();
        UUID room = liveAngelRoom(owner);
        riskPolicy(owner, 100_000, 50_000, 100, 5_000);
        when(angel.submitVerifiedIntradayLimitOnce(any(), any()))
            .thenReturn(new CustomerAngelOneSessionService.OrderSubmission(
                "ACKNOWLEDGED", bid, "ANGEL_ONE_ORDER_ACK"));

        var first = coordinator.submit(owner, room, "idem-replay-01", 42L, "BUY", 10, PRICE, CONSENT);
        var replay = coordinator.submit(owner, room, "idem-replay-01", 42L, "BUY", 10, PRICE, CONSENT);

        assertThat(replay.created()).isFalse();
        assertThat(replay.order().id()).isEqualTo(first.order().id());
        verify(angel, times(1)).submitVerifiedIntradayLimitOnce(any(), any());
    }

    @Test
    void reusedKeyForADifferentOrderIsAConflictAndNeverDispatches() throws Exception {
        String bid = freshBrokerOrderId();
        UUID owner = customer();
        UUID room = liveAngelRoom(owner);
        riskPolicy(owner, 100_000, 50_000, 100, 5_000);
        when(angel.submitVerifiedIntradayLimitOnce(any(), any()))
            .thenReturn(new CustomerAngelOneSessionService.OrderSubmission(
                "ACKNOWLEDGED", bid, "ANGEL_ONE_ORDER_ACK"));
        coordinator.submit(owner, room, "idem-reuse-01", 42L, "BUY", 10, PRICE, CONSENT);

        assertThatThrownBy(() -> coordinator.submit(owner, room, "idem-reuse-01",
                42L, "BUY", 11, PRICE, CONSENT))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT));
        verify(angel, times(1)).submitVerifiedIntradayLimitOnce(any(), any());
    }

    @Test
    void concurrentClaimsForOneCustomerAcrossKeysDispatchExactlyOnce() throws Exception {
        UUID owner = customer();
        UUID room = liveAngelRoom(owner);
        riskPolicy(owner, 100_000, 50_000, 100, 5_000);
        when(angel.submitVerifiedIntradayLimitOnce(any(), any()))
            .thenAnswer(inv -> {
                Thread.sleep(150); // widen the race window
                return new CustomerAngelOneSessionService.OrderSubmission(
                    "ACKNOWLEDGED", "AO-" + UUID.randomUUID().toString().substring(0, 8),
                    "ANGEL_ONE_ORDER_ACK");
            });

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<Boolean> attempt1 = () -> {
                start.await();
                return coordinator.submit(owner, room, "race-key-0001", 42L, "BUY", 10,
                    PRICE, CONSENT).created();
            };
            Callable<Boolean> attempt2 = () -> {
                start.await();
                return coordinator.submit(owner, room, "race-key-0002", 42L, "BUY", 10,
                    PRICE, CONSENT).created();
            };
            Future<Boolean> f1 = pool.submit(attempt1);
            Future<Boolean> f2 = pool.submit(attempt2);
            start.countDown();
            int created = 0;
            int refused = 0;
            for (Future<Boolean> f : List.of(f1, f2)) {
                try {
                    if (f.get(20, TimeUnit.SECONDS)) created++;
                } catch (java.util.concurrent.ExecutionException refusedByGate) {
                    if (refusedByGate.getCause() instanceof ResponseStatusException rse
                            && rse.getStatusCode() == HttpStatus.CONFLICT) {
                        refused++;
                    } else {
                        throw refusedByGate;
                    }
                }
            }
            assertThat(created).isEqualTo(1);
            assertThat(refused).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        verify(angel, times(1)).submitVerifiedIntradayLimitOnce(any(), any());
    }

    @Test
    void timeoutIsUnknownStaysReservedAndReconcilesToFilledWithoutRetry() throws Exception {
        String bid = freshBrokerOrderId();
        UUID owner = customer();
        UUID room = liveAngelRoom(owner);
        riskPolicy(owner, 100_000, 50_000, 100, 5_000);
        when(angel.submitVerifiedIntradayLimitOnce(any(), any()))
            .thenReturn(new CustomerAngelOneSessionService.OrderSubmission(
                "UNKNOWN", null, "ANGEL_ONE_ORDER_STATUS_UNKNOWN"));

        var order = coordinator.submit(owner, room, "idem-unk-0001", 42L, "BUY", 10, PRICE, CONSENT).order();
        assertThat(order.state()).isEqualTo("UNKNOWN");
        assertThat(order.outcomeCode()).isEqualTo("ANGEL_ONE_ORDER_STATUS_UNKNOWN");
        String tag = brokerTag(order.id());

        // Empty history is not a rejection: the exposure stays reserved.
        var stillReserved = reconciliation.reconcile(owner, room, order.id());
        assertThat(stillReserved.state()).isEqualTo("UNKNOWN");
        assertThat(stillReserved.outcomeCode()).startsWith("RECONCILE_RESERVED:");

        when(angel.orders(any())).thenReturn(new CustomerAngelOneSessionService.BrokerRead(
            "ANGEL_ONE", Instant.now(), json(angelRecord(bid, tag, "complete", 10, "100.10"))));
        var filled = reconciliation.reconcile(owner, room, order.id());
        assertThat(filled.state()).isEqualTo("FILLED");
        assertThat(filled.filledQuantity()).isEqualTo(10);
        assertThat(filled.brokerOrderId()).isEqualTo(bid);

        // A reconciled order is no longer retried or redispatched.
        verify(angel, times(1)).submitVerifiedIntradayLimitOnce(any(), any());
    }

    @Test
    void cancelAfterPartialFillStaysReservedAsPartiallyFilled() throws Exception {
        String bid = freshBrokerOrderId();
        UUID owner = customer();
        UUID room = liveAngelRoom(owner);
        riskPolicy(owner, 100_000, 50_000, 100, 5_000);
        when(angel.submitVerifiedIntradayLimitOnce(any(), any()))
            .thenReturn(new CustomerAngelOneSessionService.OrderSubmission(
                "ACKNOWLEDGED", bid, "ANGEL_ONE_ORDER_ACK"));
        var order = coordinator.submit(owner, room, "idem-part-0001", 42L, "BUY", 10, PRICE, CONSENT).order();
        String tag = brokerTag(order.id());

        when(angel.orders(any())).thenReturn(new CustomerAngelOneSessionService.BrokerRead(
            "ANGEL_ONE", Instant.now(), json(angelRecord(bid, tag, "cancelled", 4, "100.00"))));
        var result = reconciliation.reconcile(owner, room, order.id());

        assertThat(result.state()).isEqualTo("PARTIALLY_FILLED");
        assertThat(result.filledQuantity()).isEqualTo(4);
        assertThat(result.outcomeCode()).isEqualTo("CANCELLED_AFTER_PARTIAL_FILL_RESERVED");
        // Exposure is still reserved: a new order is refused until reconciled.
        assertThatThrownBy(() -> coordinator.submit(owner, room, "idem-part-0002", 42L,
                "BUY", 5, PRICE, CONSENT))
            .isInstanceOf(ResponseStatusException.class);
        verify(angel, times(1)).submitVerifiedIntradayLimitOnce(any(), any());
    }

    @Test
    void brokerHistoryFailureLeavesTheOrderReserved() throws Exception {
        UUID owner = customer();
        UUID room = liveAngelRoom(owner);
        riskPolicy(owner, 100_000, 50_000, 100, 5_000);
        when(angel.submitVerifiedIntradayLimitOnce(any(), any()))
            .thenReturn(new CustomerAngelOneSessionService.OrderSubmission(
                "UNKNOWN", null, "ANGEL_ONE_ORDER_STATUS_UNKNOWN"));
        var order = coordinator.submit(owner, room, "idem-hist-0001", 42L, "BUY", 10, PRICE, CONSENT).order();
        when(angel.orders(any())).thenThrow(new RuntimeException("provider timeout"));

        var result = reconciliation.reconcile(owner, room, order.id());

        assertThat(result.state()).isEqualTo("UNKNOWN");
        assertThat(result.outcomeCode()).isEqualTo("RECONCILE_RESERVED:BROKER_HISTORY_UNAVAILABLE");
    }

    @Test
    void acknowledgementWithoutBrokerIdIsDowngradedToUnknown() throws Exception {
        UUID owner = customer();
        UUID room = liveAngelRoom(owner);
        riskPolicy(owner, 100_000, 50_000, 100, 5_000);
        when(angel.submitVerifiedIntradayLimitOnce(any(), any()))
            .thenReturn(new CustomerAngelOneSessionService.OrderSubmission(
                "ACKNOWLEDGED", null, "ANGEL_ONE_ORDER_ACK"));

        var order = coordinator.submit(owner, room, "idem-noid-0001", 42L, "BUY", 10, PRICE, CONSENT).order();

        assertThat(order.state()).isEqualTo("UNKNOWN");
        assertThat(order.outcomeCode()).isEqualTo("BROKER_ACK_WITHOUT_ORDER_ID");
        assertThat(order.brokerOrderId()).isNull();
    }

    @Test
    void missingPerOrderConsentIsRefusedBeforeAnyRowOrRequest() throws Exception {
        UUID owner = customer();
        UUID room = liveAngelRoom(owner);
        riskPolicy(owner, 100_000, 50_000, 100, 5_000);

        assertThatThrownBy(() -> coordinator.submit(owner, room, "idem-consent-01", 42L,
                "BUY", 10, PRICE, null))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.PRECONDITION_REQUIRED));
        assertThat(orderCount(owner)).isZero();
        verify(angel, never()).submitVerifiedIntradayLimitOnce(any(), any());
    }

    @Test
    void accountWideOrderValueCapBlocksBeforeAnyRowOrRequest() throws Exception {
        UUID owner = customer();
        UUID room = liveAngelRoom(owner);
        riskPolicy(owner, 100_000, 500, 100, 5_000); // notional 1000 > 500

        assertRefused(owner, room, "idem-cap-000001", "BUY", 10);
        verify(angel, never()).submitVerifiedIntradayLimitOnce(any(), any());
    }

    @Test
    void reachedDailyLossLimitBlocksBeforeAnyRowOrRequest() throws Exception {
        UUID owner = customer();
        UUID room = liveAngelRoom(owner);
        riskPolicy(owner, 100_000, 50_000, 100, 5_000);
        when(angel.positions(any())).thenReturn(new CustomerAngelOneSessionService.BrokerRead(
            "ANGEL_ONE", Instant.now(), json(
                "{\"data\":[{\"exchange\":\"NSE\",\"symboltoken\":\"3045\","
                + "\"producttype\":\"INTRADAY\",\"netqty\":\"0\",\"realised\":\"-6000\"}]}")));

        assertRefused(owner, room, "idem-loss-00001", "BUY", 10);
        verify(angel, never()).submitVerifiedIntradayLimitOnce(any(), any());
    }

    @Test
    void sellWithoutVerifiedIntradayLongIsRefused() throws Exception {
        UUID owner = customer();
        UUID room = liveAngelRoom(owner);
        riskPolicy(owner, 100_000, 50_000, 100, 5_000);

        assertRefused(owner, room, "idem-sell-00001", "SELL", 10);
        verify(angel, never()).submitVerifiedIntradayLimitOnce(any(), any());
    }

    @Test
    void sellCoveredByVerifiedIntradayLongIsDispatched() throws Exception {
        String bid = freshBrokerOrderId();
        UUID owner = customer();
        UUID room = liveAngelRoom(owner);
        riskPolicy(owner, 100_000, 50_000, 100, 5_000);
        when(angel.positions(any())).thenReturn(new CustomerAngelOneSessionService.BrokerRead(
            "ANGEL_ONE", Instant.now(), json(
                "{\"data\":[{\"exchange\":\"NSE\",\"symboltoken\":\"3045\","
                + "\"producttype\":\"INTRADAY\",\"netqty\":\"10\",\"realised\":\"0\"}]}")));
        when(angel.submitVerifiedIntradayLimitOnce(any(), any()))
            .thenReturn(new CustomerAngelOneSessionService.OrderSubmission(
                "ACKNOWLEDGED", bid, "ANGEL_ONE_ORDER_ACK"));

        var result = coordinator.submit(owner, room, "idem-sell-00002", 42L, "SELL", 10, PRICE, CONSENT);

        assertThat(result.order().side()).isEqualTo("SELL");
        assertThat(result.order().state()).isEqualTo("ACKNOWLEDGED");
        verify(angel, times(1)).submitVerifiedIntradayLimitOnce(any(), any());
    }

    @Test
    void closedMarketBlocksBeforeAnyRowOrRequest() throws Exception {
        when(calendar.isInsideCashSession(any())).thenReturn(false);
        UUID owner = customer();
        UUID room = liveAngelRoom(owner);
        riskPolicy(owner, 100_000, 50_000, 100, 5_000);

        assertRefused(owner, room, "idem-mkt-000001", "BUY", 10);
        verify(angel, never()).submitVerifiedIntradayLimitOnce(any(), any());
    }

    @Test
    void staleOrUnverifiedQuoteBlocksBeforeAnyRowOrRequest() throws Exception {
        when(angel.quoteDetail(any(), any())).thenThrow(new RuntimeException("quote unavailable"));
        UUID owner = customer();
        UUID room = liveAngelRoom(owner);
        riskPolicy(owner, 100_000, 50_000, 100, 5_000);

        assertRefused(owner, room, "idem-qte-000001", "BUY", 10);
        verify(angel, never()).submitVerifiedIntradayLimitOnce(any(), any());
    }

    @Test
    void missingRiskPolicyFailsClosedWithNoRowOrRequest() throws Exception {
        UUID owner = customer();
        UUID room = liveAngelRoom(owner); // no risk policy row inserted

        assertRefused(owner, room, "idem-norisk-0001", "BUY", 10);
        verify(angel, never()).submitVerifiedIntradayLimitOnce(any(), any());
    }

    @Test
    void anotherCustomersRoomAndOrderAreNotVisibleOrReconcilable() throws Exception {
        UUID owner = customer();
        UUID room = liveAngelRoom(owner);
        UUID intruder = customer();
        riskPolicy(owner, 100_000, 50_000, 100, 5_000);
        riskPolicy(intruder, 100_000, 50_000, 100, 5_000);
        when(angel.submitVerifiedIntradayLimitOnce(any(), any()))
            .thenReturn(new CustomerAngelOneSessionService.OrderSubmission(
                "UNKNOWN", null, "ANGEL_ONE_ORDER_STATUS_UNKNOWN"));
        var order = coordinator.submit(owner, room, "idem-own-00001", 42L, "BUY", 10, PRICE, CONSENT).order();

        assertThatThrownBy(() -> coordinator.submit(intruder, room, "idem-own-00002", 42L,
                "BUY", 10, PRICE, CONSENT))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> reconciliation.reconcile(intruder, room, order.id()))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND));
    }

    // ----- helpers -----

    private void assertRefused(UUID owner, UUID room, String key, String side, int qty) {
        assertThatThrownBy(() -> coordinator.submit(owner, room, key, 42L, side, qty, PRICE, CONSENT))
            .isInstanceOf(ResponseStatusException.class);
        assertThat(orderCount(owner)).isZero();
    }

    private long orderCount(UUID owner) {
        return jdbc.sql("SELECT COUNT(*) FROM customer_live_equity_order WHERE user_id=:o")
            .param("o", owner).query(Long.class).single();
    }

    private String brokerTag(UUID orderId) {
        return jdbc.sql("SELECT broker_tag FROM customer_live_equity_order WHERE id=:id")
            .param("id", orderId).query(String.class).single();
    }

    /** Unique per run: the shared local database keeps rows between test runs. */
    private static String freshBrokerOrderId() {
        return "AO-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private static JsonNode json(String raw) {
        try {
            return JSON.readTree(raw);
        } catch (Exception malformed) {
            throw new IllegalArgumentException(malformed);
        }
    }

    private static String angelRecord(String orderId, String tag, String status,
            int filled, String avg) {
        return "{\"data\":[{\"orderid\":\"" + orderId + "\",\"ordertag\":\"" + tag
            + "\",\"exchange\":\"NSE\",\"tradingsymbol\":\"SBIN-EQ\",\"transactiontype\":\"BUY\","
            + "\"quantity\":\"10\",\"price\":\"100.00\",\"producttype\":\"INTRADAY\","
            + "\"ordertype\":\"LIMIT\",\"duration\":\"DAY\",\"orderstatus\":\"" + status
            + "\",\"filledshares\":\"" + filled + "\",\"averageprice\":\"" + avg + "\"}]}";
    }

    private static AngelOneQuoteDetailParser.Detail freshQuote() {
        return new AngelOneQuoteDetailParser.Detail(new BigDecimal("100.00"),
            new BigDecimal("99.00"), new BigDecimal("101.00"), new BigDecimal("98.00"),
            new BigDecimal("99.50"), 1000L, Instant.now(), new BigDecimal("99.80"),
            new BigDecimal("150.00"), new BigDecimal("80.00"), 500L, 500L,
            List.of(new AngelOneQuoteDetailParser.DepthLevel(new BigDecimal("99.95"), 100L, 2L)),
            List.of(new AngelOneQuoteDetailParser.DepthLevel(new BigDecimal("100.05"), 100L, 2L)));
    }

    private UUID customer() {
        UUID suffix = UUID.randomUUID();
        var user = users.createUser("live-exec-" + suffix, "Live execution",
            "live-exec-" + suffix + "@example.test", "test");
        jdbc.sql("UPDATE app_user SET role='CUSTOMER' WHERE id=:id")
            .param("id", user.id()).update();
        return user.id();
    }

    private UUID liveAngelRoom(UUID owner) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
            INSERT INTO customer_trading_room
              (id,user_id,name,focus,planned_budget_inr,risk_preference,mode,market_scope)
            VALUES (:id,:owner,:name,'INTRADAY',20000,'BALANCED','LIVE','EQUITY')
            """)
            .param("id", id).param("owner", owner).param("name", "Live " + id).update();
        jdbc.sql("""
            INSERT INTO customer_live_room_broker (room_id,user_id,mode,broker)
            VALUES (:room,:owner,'LIVE','ANGEL_ONE')
            """)
            .param("room", id).param("owner", owner).update();
        return id;
    }

    private void riskPolicy(UUID owner, long accountBuy, long maxValue, int maxQty, long lossLimit) {
        jdbc.sql("""
            INSERT INTO customer_live_equity_risk_policy
              (user_id,max_account_intraday_buy_inr,max_order_value_inr,
               max_order_quantity,daily_realized_loss_limit_inr)
            VALUES (:owner,:buy,:value,:qty,:loss)
            """)
            .param("owner", owner).param("buy", accountBuy).param("value", maxValue)
            .param("qty", maxQty).param("loss", lossLimit).update();
    }
}
