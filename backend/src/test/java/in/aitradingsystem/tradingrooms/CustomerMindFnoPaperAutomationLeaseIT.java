package in.aitradingsystem.tradingrooms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import in.aitradingsystem.marketdata.MarketSessionCalendar;
import in.aitradingsystem.support.AbstractPostgresIT;
import in.aitradingsystem.user.UserAccountStore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Actual PostgreSQL lease-fencing acceptance; research and orders are mocked.
 * No Upstox/provider calls, no order submission, no customer secrets.
 */
class CustomerMindFnoPaperAutomationLeaseIT extends AbstractPostgresIT {
    private static final Instant SESSION=Instant.parse("2026-11-16T04:00:00Z");

    @Autowired JdbcClient jdbc;
    @Autowired UserAccountStore users;
    @Autowired CustomerPaperWalletService wallets;

    private final CustomerPaperFnoCapabilityController capability=
        mock(CustomerPaperFnoCapabilityController.class);
    private final CustomerMindFnoPaperCycleService cycles=
        mock(CustomerMindFnoPaperCycleService.class);
    private final MarketSessionCalendar calendar=mock(MarketSessionCalendar.class);
    private final Clock clock=Clock.fixed(SESSION,ZoneOffset.UTC);

    private record Fixture(UUID owner,UUID room,CustomerMindFnoPaperAutomationService worker) {}

    private Fixture setup() {
        var suffix=UUID.randomUUID();
        UUID owner=users.createUser("lease-fno-"+suffix,"Lease verification",
            "lease-fno-"+suffix+"@example.test","test:customer").id();
        jdbc.sql("UPDATE app_user SET role='CUSTOMER' WHERE id=:owner")
            .param("owner",owner).update();
        UUID room=UUID.randomUUID();
        jdbc.sql("""
            INSERT INTO customer_trading_room
                (id,user_id,name,focus,planned_budget_inr,risk_preference,
                 mode,market_scope,status)
            VALUES (:room,:owner,:name,'INTRADAY',100000,'BALANCED',
                    'PAPER','FNO','DRAFT')
            """).param("room",room).param("owner",owner)
            .param("name","Lease test "+suffix).update();
        wallets.activate(owner,room);
        when(capability.internalCapability(owner,room)).thenReturn(
            new CustomerPaperFnoCapabilityController.Capability(
                "SOURCE_CHECK_REQUIRED",true,true,false,List.of()));
        when(calendar.isInsideFnoSession(SESSION)).thenReturn(true);
        var worker=new CustomerMindFnoPaperAutomationService(
            jdbc,capability,cycles,clock,calendar);
        assertThat(worker.setEnabled(owner,room,true).enabled()).isTrue();
        return new Fixture(owner,room,worker);
    }

    private CustomerMindFnoPaperCycleService.CycleResult result(UUID room) {
        return new CustomerMindFnoPaperCycleService.CycleResult(
            "mind.fno-paper-cycle.v1",SESSION,room,
            "NO_TRADE","NO_NEW_CROSSOVER","NONE",
            null,null,null,null,null,null,null,false);
    }

    private int journalCount(UUID room) {
        return jdbc.sql("""
            SELECT COUNT(*) FROM mind_fno_paper_cycle_event WHERE room_id=:room
            """).param("room",room).query(Integer.class).single();
    }

    @Test
    void validLeaseAtomicallyJournalsExactlyOneCompletedNoTradeCycle() {
        var f=setup();
        when(cycles.runOptedIn(f.owner(),f.room())).thenReturn(result(f.room()));
        f.worker().poll();
        assertThat(journalCount(f.room())).isEqualTo(1);
        var status=f.worker().state(f.owner(),f.room());
        assertThat(status.enabled()).isTrue();
        assertThat(status.lastStatus()).isEqualTo("NO_TRADE");
        assertThat(status.lastReason()).isEqualTo("NO_NEW_CROSSOVER");
        assertThat(jdbc.sql("""
            SELECT lease_until IS NULL FROM mind_fno_paper_automation
            WHERE room_id=:room
            """).param("room",f.room()).query(Boolean.class).single()).isTrue();
        verify(cycles,times(1)).runOptedIn(f.owner(),f.room());
    }

    @Test
    void workerThatLostLeaseToNewClaimCannotOverwriteStatusOrAppendReceipt() {
        var f=setup();
        doAnswer(call->{
            // Model a second app replica reclaiming the room while cycle runs.
            jdbc.sql("""
                UPDATE mind_fno_paper_automation
                SET lease_until=NOW()+INTERVAL '180 seconds',
                    last_status='NEWER_CLAIM',last_reason='RECLAIMED'
                WHERE room_id=:room AND user_id=:owner
                """).param("room",f.room()).param("owner",f.owner()).update();
            return result(f.room());
        }).when(cycles).runOptedIn(f.owner(),f.room());
        f.worker().poll();
        assertThat(journalCount(f.room())).isZero();
        var state=f.worker().state(f.owner(),f.room());
        assertThat(state.enabled()).isTrue();
        assertThat(state.lastStatus()).isEqualTo("NEWER_CLAIM");
        assertThat(state.lastReason()).isEqualTo("RECLAIMED");
        assertThat(jdbc.sql("""
            SELECT lease_until > NOW() FROM mind_fno_paper_automation
            WHERE room_id=:room
            """).param("room",f.room()).query(Boolean.class).single()).isTrue();
    }

    @Test
    void operatorDisablesDuringCycleOldWorkerCannotReEnableOrJournal() {
        var f=setup();
        doAnswer(call->{
            assertThat(f.worker().setEnabled(f.owner(),f.room(),false).enabled())
                .isFalse();
            return result(f.room());
        }).when(cycles).runOptedIn(f.owner(),f.room());
        f.worker().poll();
        assertThat(journalCount(f.room())).isZero();
        assertThat(f.worker().state(f.owner(),f.room()).enabled()).isFalse();
    }

    @Test
    void unknownCycleOutcomeStopsWorkerAndJournalsUnknownOnce() {
        var f=setup();
        when(cycles.runOptedIn(f.owner(),f.room()))
            .thenThrow(new IllegalStateException("simulated unknown outcome"));
        f.worker().poll();
        assertThat(journalCount(f.room())).isEqualTo(1);
        var state=f.worker().state(f.owner(),f.room());
        assertThat(state.enabled()).isFalse();
        assertThat(state.lastStatus()).isEqualTo("UNKNOWN");
        assertThat(state.lastReason()).isEqualTo("CYCLE_OUTCOME_UNKNOWN");
    }
}
