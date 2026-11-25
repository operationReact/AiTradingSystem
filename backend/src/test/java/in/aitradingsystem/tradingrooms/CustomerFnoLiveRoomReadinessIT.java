package in.aitradingsystem.tradingrooms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

import in.aitradingsystem.instrument.InstrumentMasterService;
import in.aitradingsystem.provider.angelone.CustomerAngelOneSessionService;
import in.aitradingsystem.provider.upstox.CustomerUpstoxOAuthService;
import in.aitradingsystem.support.AbstractPostgresIT;
import in.aitradingsystem.user.UserAccountStore;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.server.ResponseStatusException;

class CustomerFnoLiveRoomReadinessIT extends AbstractPostgresIT {
    @Autowired JdbcClient jdbc;
    @Autowired UserAccountStore users;
    @Autowired CustomerPaperWalletService wallets;

    private record Fixture(CustomerExecutionReadinessService service,
                           InstrumentMasterService instruments,
                           CustomerUpstoxOAuthService upstox) {}

    private Fixture fixture() {
        var instruments = mock(InstrumentMasterService.class);
        var upstox = mock(CustomerUpstoxOAuthService.class);
        return new Fixture(new CustomerExecutionReadinessService(jdbc,
            mock(CustomerAngelOneSessionService.class),
            upstox, instruments), instruments, upstox);
    }

    private UUID customer() {
        UUID suffix = UUID.randomUUID();
        return users.createUser("fno-ready-" + suffix, "FNO readiness",
            "fno-ready-" + suffix + "@example.test", "test").id();
    }

    private UUID room(UUID owner, String mode, String scope) {
        UUID room = UUID.randomUUID();
        jdbc.sql("""
            INSERT INTO customer_trading_room
              (id,user_id,name,focus,planned_budget_inr,risk_preference,
               mode,market_scope,status)
            VALUES (:room,:owner,:name,'SWING',20000,'BALANCED',
                    :mode,:scope,'DRAFT')
            """)
            .param("room", room).param("owner", owner).param("name", "Scope " + room)
            .param("mode", mode).param("scope", scope).update();
        return room;
    }

    @Test
    void resumedPaperWalletRemainsFundedAndPausedStateIsReportedAccurately() {
        var setup = fixture();
        UUID owner = customer();
        UUID id = room(owner, "PAPER", "FNO");
        var inactive = setup.service.readiness(owner, id);
        assertThat(inactive.virtualCashReady()).isFalse();
        assertThat(inactive.blockers()).contains("PAPER_WALLET_NOT_FUNDED");

        wallets.activate(owner, id);
        var paused = setup.service.readiness(owner, id);
        assertThat(paused.virtualCashReady()).isTrue();
        assertThat(paused.blockers()).contains("PAPER_ROOM_PAUSED");
        assertThat(paused.blockers()).doesNotContain("PAPER_WALLET_NOT_FUNDED");

        wallets.setPaused(owner, id, false);
        var resumed = setup.service.readiness(owner, id);
        assertThat(resumed.virtualCashReady()).isTrue();
        assertThat(resumed.blockers()).doesNotContain(
            "PAPER_ROOM_PAUSED", "PAPER_WALLET_NOT_FUNDED");
        assertThat(resumed.executionEnabled()).isFalse();
        // F&O PAPER execution is separately gated by live-feed/rights checks.
        assertThat(resumed.blockers()).contains("EXECUTION_DISABLED");
    }

    @Test
    void fnoAndAllLiveRoomsNeverInheritEquityReadiness() {
        var setup = fixture();
        UUID owner = customer();
        UUID equity = room(owner, "LIVE", "EQUITY");
        UUID fno = room(owner, "LIVE", "FNO");
        UUID all = room(owner, "LIVE", "ALL");

        var equityStatus = setup.service.readiness(owner, equity);
        assertThat(equityStatus.executionEnabled()).isFalse();
        assertThat(equityStatus.blockers()).doesNotContain(
            "FNO_LIVE_ORDER_ADAPTER_NOT_ENABLED");

        for (UUID id : new UUID[] { fno, all }) {
            var status = setup.service.readiness(owner, id);
            assertThat(status.mode()).isEqualTo("LIVE");
            assertThat(status.executionEnabled()).isFalse();
            assertThat(status.brokerExecutionApproved()).isFalse();
            assertThat(status.blockers()).contains(
                "FNO_BROKER_SEGMENT_PERMISSION_NOT_VERIFIED",
                "FNO_CONTRACT_MARGIN_AND_RISK_NOT_VERIFIED",
                "FNO_LIVE_ORDER_ADAPTER_NOT_ENABLED",
                "EXPLICIT_LIVE_EXECUTION_CONSENT_NOT_CONFIGURED",
                "EXECUTION_DISABLED");
        }
    }

    @Test
    void ownerVerifiedNfoProfileClearsOnlySegmentBlockerNotLiveExecution() {
        var setup = fixture();
        UUID owner = customer();
        UUID id = room(owner, "LIVE", "FNO");
        when(setup.upstox.status(owner)).thenReturn(
            new CustomerUpstoxOAuthService.Status(true, true, "UPSTOX",
                "***456", Instant.parse("2026-11-17T22:00:00Z"), false));
        when(setup.upstox.fnoEntitlement(owner)).thenReturn(
            new CustomerUpstoxOAuthService.FnoEntitlement(
                true, true, true, true, true, "UPSTOX_NFO_PROFILE_VERIFIED"));

        var readiness = setup.service.selectLiveBroker(owner, id, "UPSTOX");
        assertThat(readiness.brokerConnected()).isTrue();
        assertThat(readiness.blockers())
            .doesNotContain("FNO_BROKER_SEGMENT_PERMISSION_NOT_VERIFIED");
        assertThat(readiness.blockers()).contains(
            "FNO_LIVE_ORDER_ADAPTER_NOT_ENABLED",
            "EXPLICIT_LIVE_EXECUTION_CONSENT_NOT_CONFIGURED",
            "FNO_CONTRACT_MARGIN_AND_RISK_NOT_VERIFIED",
            "EXECUTION_DISABLED");
        assertThat(readiness.executionEnabled()).isFalse();
        assertThat(readiness.brokerExecutionApproved()).isFalse();

        when(setup.upstox.fnoEntitlement(owner)).thenReturn(
            new CustomerUpstoxOAuthService.FnoEntitlement(
                true, false, true, true, true, "UPSTOX_NFO_NOT_ENABLED"));
        var rejected = setup.service.readiness(owner, id);
        assertThat(rejected.blockers()).contains(
            "FNO_BROKER_SEGMENT_PERMISSION_NOT_VERIFIED",
            "UPSTOX_NFO_NOT_ENABLED");
    }

    @Test
    void fnoOnlyRoomRejectsCashOrderPreflightBeforeInstrumentLookup() {
        var setup = fixture();
        UUID owner = customer();
        UUID id = room(owner, "LIVE", "FNO");

        assertThatThrownBy(() -> setup.service.preflight(owner, id, 123,
                "BUY", 1, new BigDecimal("100")))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT))
            .hasMessageContaining("F&O-only room");
        verifyNoInteractions(setup.instruments());
    }

    @Test
    void foreignRoomCannotBeInspectedOrPreflighted() {
        var setup = fixture();
        UUID owner = customer();
        UUID outsider = customer();
        UUID id = room(owner, "LIVE", "FNO");

        assertThatThrownBy(() -> setup.service.readiness(outsider, id))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> setup.service.preflight(outsider, id, 123,
                "BUY", 1, new BigDecimal("100")))
            .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(setup.instruments());
    }
}
