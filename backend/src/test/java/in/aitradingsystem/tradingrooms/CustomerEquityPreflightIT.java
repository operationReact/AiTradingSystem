package in.aitradingsystem.tradingrooms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.aitradingsystem.instrument.Instrument;
import in.aitradingsystem.instrument.InstrumentMasterService;
import in.aitradingsystem.provider.angelone.CustomerAngelOneSessionService;
import in.aitradingsystem.provider.upstox.CustomerUpstoxOAuthService;
import in.aitradingsystem.support.AbstractPostgresIT;
import in.aitradingsystem.user.UserAccountStore;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.server.ResponseStatusException;

class CustomerEquityPreflightIT extends AbstractPostgresIT {
    @Autowired JdbcClient jdbc;
    @Autowired UserAccountStore users;

    @Test
    void limitPriceMustBeAnExactMultipleOfTheActiveInstrumentTick() {
        UUID owner = customer();
        UUID room = room(owner);
        var instruments = mock(InstrumentMasterService.class);
        var active = new Instrument(42L, "NSE_CM", "NSE", "3045",
            "SBIN-EQ", "State Bank", "EQ", null, null, 1,
            new BigDecimal("0.05"), UUID.randomUUID());
        when(instruments.requireActiveInstrument(42L)).thenReturn(active);
        var service = new CustomerExecutionReadinessService(jdbc,
            mock(CustomerAngelOneSessionService.class),
            mock(CustomerUpstoxOAuthService.class), instruments);

        assertThatThrownBy(() -> service.preflight(owner, room, 42L,
                "BUY", 1, new BigDecimal("194.53")))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY))
            .hasMessageContaining("tick size");

        var acceptedForReview = service.preflight(owner, room, 42L,
            "BUY", 1, new BigDecimal("194.55"));
        assertThat(acceptedForReview.executable()).isFalse();
        // Room exposure is read from the ledger (zero here). It was previously
        // reported unverified because of an Instant binding defect; that defect is fixed.
        assertThat(acceptedForReview.blockers()).contains(
            "BROKER_AVAILABLE_FUNDS_NOT_VERIFIED", "EXECUTION_DISABLED");
        assertThat(acceptedForReview.blockers()).doesNotContain("ROOM_DAILY_EXPOSURE_NOT_VERIFIED");
        assertThat(acceptedForReview.roomExposureVerified()).isTrue();
        assertThat(acceptedForReview.blockers().contains("CASH_MARKET_SESSION_CLOSED")
                || acceptedForReview.blockers().contains("AUTHORITATIVE_QUOTE_NOT_VERIFIED"))
            .isTrue();
    }

    private UUID customer() {
        UUID suffix = UUID.randomUUID();
        var user = users.createUser("equity-preflight-" + suffix,
            "Equity preflight", "equity-preflight-" + suffix + "@example.test", "test");
        jdbc.sql("UPDATE app_user SET role='CUSTOMER' WHERE id=:id")
            .param("id", user.id()).update();
        return user.id();
    }

    private UUID room(UUID owner) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
            INSERT INTO customer_trading_room
              (id,user_id,name,focus,planned_budget_inr,risk_preference,mode,market_scope)
            VALUES (:id,:owner,:name,'INTRADAY',20000,'BALANCED','LIVE','EQUITY')
            """)
            .param("id", id).param("owner", owner).param("name", "Equity " + id).update();
        return id;
    }
}
