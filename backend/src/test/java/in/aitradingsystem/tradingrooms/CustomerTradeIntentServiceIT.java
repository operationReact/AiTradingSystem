package in.aitradingsystem.tradingrooms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.aitradingsystem.support.AbstractPostgresIT;
import in.aitradingsystem.user.UserAccountStore;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.server.ResponseStatusException;

class CustomerTradeIntentServiceIT extends AbstractPostgresIT {
    @Autowired JdbcClient jdbc;
    @Autowired UserAccountStore users;

    @Test
    void sameCustomerAndRoomAndKeyAreIdempotentButDifferentOrderConflicts() {
        UUID owner = customer();
        UUID room = room(owner, "PAPER");
        var service = service(room, "PAPER");

        var first = service.create(owner, room, "idem_test_001", 42L,
            "BUY", 5, "DELIVERY", new BigDecimal("194.50"));
        var retry = service.create(owner, room, "idem_test_001", 42L,
            "BUY", 5, "DELIVERY", new BigDecimal("194.50"));
        assertThat(first.id()).isEqualTo(retry.id());
        assertThat(first.state()).isEqualTo("PRECHECK_BLOCKED");
        assertThat(first.blockers()).contains("EXECUTION_DISABLED");
        assertThat(first.mode()).isEqualTo("PAPER");

        assertThatThrownBy(() -> service.create(owner, room, "idem_test_001",
            42L, "BUY", 5, "INTRADAY", new BigDecimal("194.50")))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(err -> assertThat(((ResponseStatusException) err).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT));
        assertThat(service.list(owner, room)).hasSize(1);
    }

    @Test
    void liveAndPaperIntentsHaveSeparateCustomerAndModeCustody() {
        UUID owner = customer();
        UUID paperRoom = room(owner, "PAPER");
        UUID liveRoom = room(owner, "LIVE");
        var paper = service(paperRoom, "PAPER");
        var live = service(liveRoom, "LIVE");

        var paperEntry = paper.create(owner, paperRoom, "same_key_001",
            42L, "BUY", 1, "DELIVERY", new BigDecimal("194.50"));
        var liveEntry = live.create(owner, liveRoom, "same_key_001",
            42L, "BUY", 1, "DELIVERY", new BigDecimal("194.50"));
        assertThat(paperEntry.mode()).isEqualTo("PAPER");
        assertThat(liveEntry.mode()).isEqualTo("LIVE");
        assertThat(paperEntry.id()).isNotEqualTo(liveEntry.id());
        assertThat(paper.list(owner, paperRoom)).hasSize(1);
        assertThat(live.list(owner, liveRoom)).hasSize(1);

        Long count = jdbc.sql("""
                SELECT COUNT(*) FROM customer_trade_intent
                WHERE user_id = :owner
                """).param("owner", owner).query(Long.class).single();
        assertThat(count).isEqualTo(2L);
    }

    private CustomerTradeIntentService service(UUID room, String mode) {
        var readiness = mock(CustomerExecutionReadinessService.class);
        when(readiness.preflight(any(), any(), anyLong(), any(), anyInt(), any()))
            .thenAnswer(call -> new CustomerExecutionReadinessService.Preflight(
                room, mode, 42L, "SBIN-EQ", "NSE",
                call.getArgument(3), call.getArgument(4),
                "LIMIT", "DAY", call.getArgument(5), false,
                List.of("EXECUTION_DISABLED", "AUTHORITATIVE_QUOTE_NOT_VERIFIED")));
        when(readiness.readiness(any(), any()))
            .thenReturn(new CustomerExecutionReadinessService.Readiness(
                room, mode, "DRAFT", null, false, false, false, false,
                false, false, List.of("EXECUTION_DISABLED")));
        return new CustomerTradeIntentService(jdbc, new ObjectMapper(), readiness);
    }

    private UUID customer() {
        UUID suffix = UUID.randomUUID();
        var created = users.createUser("intent-" + suffix,
            "Intent customer", "intent-" + suffix + "@example.test", "test");
        jdbc.sql("UPDATE app_user SET role='CUSTOMER' WHERE id = :id")
            .param("id", created.id()).update();
        return created.id();
    }

    private UUID room(UUID owner, String mode) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO customer_trading_room
                    (id, user_id, name, focus, planned_budget_inr, risk_preference, mode)
                VALUES (:id, :owner, :name, 'INTRADAY', 20000, 'BALANCED', :mode)
                """)
            .param("id", id).param("owner", owner)
            .param("name", "Intent room " + id).param("mode", mode).update();
        return id;
    }
}
