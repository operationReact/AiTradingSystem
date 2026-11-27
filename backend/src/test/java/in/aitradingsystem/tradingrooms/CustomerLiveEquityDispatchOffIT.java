package in.aitradingsystem.tradingrooms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;

/**
 * With the deployment gate at its default (off), a fully valid LIVE order must
 * be refused before any claim row is written and before any broker request.
 * No property is set here, so this test exercises the shipped default.
 */
class CustomerLiveEquityDispatchOffIT extends AbstractPostgresIT {
    @Autowired JdbcClient jdbc;
    @Autowired UserAccountStore users;
    @Autowired LiveEquityOrderCoordinator coordinator;
    @Autowired CustomerLiveEquityOrderService orders;
    @MockitoBean CustomerAngelOneSessionService angel;
    @MockitoBean CustomerUpstoxOAuthService upstox;
    @MockitoBean InstrumentMasterService instruments;
    @MockitoBean IndianCashMarketCalendar calendar;

    @BeforeEach
    void fullyValidBrokerState() throws Exception {
        when(calendar.isInsideCashSession(any())).thenReturn(true);
        when(instruments.requireActiveInstrument(42L)).thenReturn(new Instrument(42L,
            "NSE_CM", "NSE", "3045", "SBIN-EQ", "State Bank", "EQ", null, null, 1,
            new BigDecimal("0.05"), UUID.randomUUID()));
        when(angel.status(any())).thenReturn(new CustomerAngelOneSessionService.ConnectionStatus(
            true, "ANGEL_ONE", "***xyz", Instant.now().plusSeconds(3600), true));
        when(angel.funds(any())).thenReturn(new CustomerAngelOneSessionService.BrokerRead(
            "ANGEL_ONE", Instant.now(), new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree("{\"data\":{\"availablecash\":\"100000.00\"}}")));
        when(angel.positions(any())).thenReturn(new CustomerAngelOneSessionService.BrokerRead(
            "ANGEL_ONE", Instant.now(), new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree("{\"data\":[]}")));
        when(angel.quoteDetail(any(), any())).thenReturn(new AngelOneQuoteDetailParser.Detail(
            new BigDecimal("100.00"), new BigDecimal("99.00"), new BigDecimal("101.00"),
            new BigDecimal("98.00"), new BigDecimal("99.50"), 1000L, Instant.now(),
            new BigDecimal("99.80"), new BigDecimal("150.00"), new BigDecimal("80.00"), 500L, 500L,
            List.of(new AngelOneQuoteDetailParser.DepthLevel(new BigDecimal("99.95"), 100L, 2L)),
            List.of(new AngelOneQuoteDetailParser.DepthLevel(new BigDecimal("100.05"), 100L, 2L))));
    }

    @Test
    void defaultOffGateCreatesNoRowAndSendsNothing() {
        UUID owner = customer();
        UUID room = liveAngelRoom(owner);
        riskPolicy(owner);

        assertThatThrownBy(() -> coordinator.submit(owner, room, "idem-off-00001", 42L,
                "BUY", 10, new BigDecimal("100.00"), LiveEquityOrderCoordinator.CONSENT))
            .isInstanceOf(ResponseStatusException.class);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM customer_live_equity_order WHERE user_id=:o")
            .param("o", owner).query(Long.class).single()).isZero();
        verify(angel, never()).submitVerifiedIntradayLimitOnce(any(), any());
        verify(upstox, never()).submitVerifiedEquityIntradayOnce(any(), any());
    }

    @Test
    void claimItselfRefusesWhileTheGateIsOffEvenWithConsent() {
        // Defense in depth: even bypassing the coordinator, the durable claim
        // is refused by the readiness gate (EXECUTION_DISABLED) with no row.
        UUID owner = customer();
        UUID room = liveAngelRoom(owner);
        riskPolicy(owner);

        assertThatThrownBy(() -> orders.claimForSubmission(owner, room, "idem-off-00002", 42L,
                "BUY", 10, new BigDecimal("100.00"), true))
            .isInstanceOf(ResponseStatusException.class);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM customer_live_equity_order WHERE user_id=:o")
            .param("o", owner).query(Long.class).single()).isZero();
        verify(angel, never()).submitVerifiedIntradayLimitOnce(any(), any());
    }

    private UUID customer() {
        UUID suffix = UUID.randomUUID();
        var user = users.createUser("live-off-" + suffix, "Live off",
            "live-off-" + suffix + "@example.test", "test");
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
            .param("id", id).param("owner", owner).param("name", "Live off " + id).update();
        jdbc.sql("""
            INSERT INTO customer_live_room_broker (room_id,user_id,mode,broker)
            VALUES (:room,:owner,'LIVE','ANGEL_ONE')
            """)
            .param("room", id).param("owner", owner).update();
        return id;
    }

    private void riskPolicy(UUID owner) {
        jdbc.sql("""
            INSERT INTO customer_live_equity_risk_policy
              (user_id,max_account_intraday_buy_inr,max_order_value_inr,
               max_order_quantity,daily_realized_loss_limit_inr)
            VALUES (:owner,100000,50000,100,5000)
            """)
            .param("owner", owner).update();
    }
}
