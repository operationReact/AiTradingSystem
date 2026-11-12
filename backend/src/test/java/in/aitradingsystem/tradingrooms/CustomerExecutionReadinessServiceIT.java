package in.aitradingsystem.tradingrooms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.aitradingsystem.support.AbstractPostgresIT;
import in.aitradingsystem.user.UserAccountStore;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.server.ResponseStatusException;

class CustomerExecutionReadinessServiceIT extends AbstractPostgresIT {
    @Autowired CustomerExecutionReadinessService service;
    @Autowired CustomerPaperWalletService paperWallets;
    @Autowired UserAccountStore users;
    @Autowired JdbcClient jdbc;

    @Test
    void paperAndLiveHaveIndependentBlockersAndNeitherCanTrade() {
        UUID owner = customer();
        UUID paper = room(owner, "PAPER", 20000);
        UUID live = room(owner, "LIVE", 20000);
        var before = service.readiness(owner, paper);
        assertThat(before.mode()).isEqualTo("PAPER");
        assertThat(before.blockers()).contains("PAPER_WALLET_NOT_FUNDED", "EXECUTION_DISABLED");
        assertThat(before.executionEnabled()).isFalse();

        paperWallets.activate(owner, paper);
        var after = service.readiness(owner, paper);
        assertThat(after.virtualCashReady()).isTrue();
        assertThat(after.blockers()).doesNotContain("PAPER_WALLET_NOT_FUNDED");
        assertThat(after.blockers()).contains("CUSTOMER_PAPER_ORDER_ENGINE_NOT_ENABLED");
        assertThat(after.executionEnabled()).isFalse();

        var liveReadiness = service.readiness(owner, live);
        assertThat(liveReadiness.mode()).isEqualTo("LIVE");
        assertThat(liveReadiness.virtualCashReady()).isFalse();
        assertThat(liveReadiness.brokerConnected()).isFalse();
        assertThat(liveReadiness.blockers()).contains(
            "REGISTERED_STATIC_IP_NOT_VERIFIED",
            "ACCOUNT_WIDE_RISK_AND_RECONCILIATION_NOT_ENABLED",
            "EXPLICIT_LIVE_EXECUTION_CONSENT_NOT_CONFIGURED",
            "EXECUTION_DISABLED");
        assertThat(liveReadiness.executionEnabled()).isFalse();
    }

    @Test
    void liveRoomSelectsExactlyOneOwnerBrokerButCannotSubmitRealOrders() {
        UUID owner = customer();
        UUID live = room(owner, "LIVE", 25000);
        var before = service.readiness(owner, live);
        assertThat(before.selectedBroker()).isNull();
        assertThat(before.blockers()).contains("ROOM_BROKER_ASSIGNMENT_NOT_CONFIGURED");
        var upstox = service.selectLiveBroker(owner, live, "UPSTOX");
        assertThat(upstox.selectedBroker()).isEqualTo("UPSTOX");
        assertThat(upstox.brokerConnected()).isFalse();
        assertThat(upstox.blockers()).doesNotContain("ROOM_BROKER_ASSIGNMENT_NOT_CONFIGURED");
        assertThat(upstox.blockers()).contains("BROKER_NOT_CONNECTED",
            "REGISTERED_STATIC_IP_NOT_VERIFIED",
            "ACCOUNT_WIDE_RISK_AND_RECONCILIATION_NOT_ENABLED",
            "EXPLICIT_LIVE_EXECUTION_CONSENT_NOT_CONFIGURED", "EXECUTION_DISABLED");
        assertThat(upstox.executionEnabled()).isFalse();
        var angel = service.selectLiveBroker(owner, live, "ANGEL_ONE");
        assertThat(angel.selectedBroker()).isEqualTo("ANGEL_ONE");
        Long selections = jdbc.sql("""
                SELECT COUNT(*) FROM customer_live_room_broker WHERE room_id=:room
                """).param("room", live).query(Long.class).single();
        assertThat(selections).isEqualTo(1L);
        assertThat(service.readiness(owner, live).selectedBroker())
            .isEqualTo("ANGEL_ONE");
    }

    @Test
    void neverAssignsLiveBrokerToPaperOrDifferentCustomer() {
        UUID alice = customer();
        UUID bob = customer();
        UUID paper = room(alice, "PAPER", 25000);
        UUID live = room(alice, "LIVE", 30000);
        assertThatThrownBy(() -> service.selectLiveBroker(alice, paper, "UPSTOX"))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT));
        assertThatThrownBy(() -> service.selectLiveBroker(bob, live, "UPSTOX"))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.selectLiveBroker(alice, live, "OTHER"))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST));
        Long count = jdbc.sql("SELECT COUNT(*) FROM customer_live_room_broker")
            .query(Long.class).single();
        assertThat(count).isZero();
    }

    @Test
    void cannotReadAnotherCustomersReadinessOrInventExecutionPermission() {
        UUID alice = customer();
        UUID bob = customer();
        UUID room = room(alice, "LIVE", 50000);
        assertThatThrownBy(() -> service.readiness(bob, room))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND));
    }

    private UUID customer() {
        UUID suffix = UUID.randomUUID();
        var user = users.createUser(
            "readiness-" + suffix,
            "Customer",
            "readiness-" + suffix + "@example.test",
            "test");
        jdbc.sql("UPDATE app_user SET role='CUSTOMER' WHERE id=:id")
            .param("id", user.id()).update();
        return user.id();
    }

    private UUID room(UUID owner, String mode, long budget) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO customer_trading_room
                    (id, user_id, name, focus, planned_budget_inr, risk_preference, mode)
                VALUES (:id, :owner, :name, 'INTRADAY', :budget, 'BALANCED', :mode)
                """)
            .param("id", id).param("owner", owner).param("name", "Room " + id)
            .param("budget", budget).param("mode", mode).update();
        return id;
    }
}
