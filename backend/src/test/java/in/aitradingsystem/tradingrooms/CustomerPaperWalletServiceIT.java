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

class CustomerPaperWalletServiceIT extends AbstractPostgresIT {
    @Autowired JdbcClient jdbc;
    @Autowired UserAccountStore users;
    @Autowired CustomerPaperWalletService service;

    @Test
    void activationCreditsExactlyOnceAndRemainsHaltedUntilRealExecutionExists() {
        UUID owner = customer();
        UUID room = room(owner, "PAPER", "DRAFT", 20000);

        var first = service.activate(owner, room);
        var second = service.activate(owner, room);

        assertThat(first.roomId()).isEqualTo(room);
        assertThat(first.mode()).isEqualTo("PAPER");
        assertThat(first.roomStatus()).isEqualTo("PAPER_FUNDED");
        assertThat(first.initialCashPaise()).isEqualTo(2_000_000L);
        assertThat(first.availableCashPaise()).isEqualTo(2_000_000L);
        assertThat(first.reservedCashPaise()).isZero();
        assertThat(first.halted()).isTrue();
        assertThat(first.executionEnabled()).isFalse();
        assertThat(second).isEqualTo(first);

        var events = service.ledger(owner, room);
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().eventKind()).isEqualTo("INITIAL_PAPER_ALLOCATION");
        assertThat(events.getFirst().cashDeltaPaise()).isEqualTo(2_000_000L);
        assertThat(events.getFirst().cashAfterPaise()).isEqualTo(2_000_000L);

        Long count = jdbc.sql("""
                SELECT count(*) FROM customer_paper_cash_event
                WHERE room_id = :room
                """).param("room", room).query(Long.class).single();
        assertThat(count).isEqualTo(1L);
        assertThat(service.get(owner, room).ledgerVersion()).isEqualTo(1);
    }

    @Test
    void customerCannotReadOrActivateAnotherPersonsPaperAccount() {
        UUID owner = customer();
        UUID another = customer();
        UUID room = room(owner, "PAPER", "DRAFT", 5000);
        service.activate(owner, room);

        assertThatThrownBy(() -> service.get(another, room))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.ledger(another, room))
            .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.activate(another, room))
            .isInstanceOf(ResponseStatusException.class);
        assertThat(service.get(owner, room).availableCashPaise()).isEqualTo(500_000);
    }

    @Test
    void liveRoomCanNeverBeMintedVirtualFunds() {
        UUID owner = customer();
        UUID live = room(owner, "LIVE", "DRAFT", 50000);
        assertThatThrownBy(() -> service.activate(owner, live))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT));
        Long count = jdbc.sql("""
                SELECT count(*) FROM customer_paper_account
                WHERE room_id = :room
                """).param("room", live).query(Long.class).single();
        assertThat(count).isZero();
    }

    @Test
    void absentPaperAccountDoesNotReportInventedCash() {
        UUID owner = customer();
        UUID room = room(owner, "PAPER", "DRAFT", 3500);
        assertThatThrownBy(() -> service.get(owner, room))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.ledger(owner, room))
            .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void manualPaperResumeAndPauseAreOwnerScopedAndNeverTouchLiveRooms() {
        UUID owner = customer();
        UUID another = customer();
        UUID room = room(owner, "PAPER", "DRAFT", 20000);
        service.activate(owner,room);
        assertThat(service.get(owner,room).halted()).isTrue();

        var started = service.setPaused(owner,room,false);
        assertThat(started.halted()).isFalse();
        assertThat(started.executionEnabled()).isTrue();
        var stopped = service.setPaused(owner,room,true);
        assertThat(stopped.halted()).isTrue();
        assertThat(stopped.executionEnabled()).isFalse();
        assertThat(stopped.availableCashPaise()).isEqualTo(2_000_000L);

        assertThatThrownBy(() -> service.setPaused(another,room,false))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> assertThat(((ResponseStatusException)ex).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND));

        UUID live = room(owner,"LIVE","DRAFT",20000);
        assertThatThrownBy(() -> service.setPaused(owner,live,false))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> assertThat(((ResponseStatusException)ex).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND));
    }

    private UUID customer() {
        String subject = UUID.randomUUID().toString();
        var user = users.createUser("paper-" + subject,
            "Paper test customer", "paper-" + subject + "@example.test", "test:customer");
        jdbc.sql("UPDATE app_user SET role = 'CUSTOMER' WHERE id = :id")
            .param("id", user.id()).update();
        return user.id();
    }

    private UUID room(UUID owner, String mode, String status, long budgetInr) {
        UUID roomId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO customer_trading_room
                    (id, user_id, name, focus, planned_budget_inr, risk_preference, mode, status)
                VALUES (:id, :owner, :name, 'INTRADAY', :budget, 'BALANCED', :mode, :status)
                """)
            .param("id", roomId)
            .param("owner", owner)
            .param("name", "Portfolio " + roomId)
            .param("budget", budgetInr)
            .param("mode", mode)
            .param("status", status).update();
        return roomId;
    }
}
