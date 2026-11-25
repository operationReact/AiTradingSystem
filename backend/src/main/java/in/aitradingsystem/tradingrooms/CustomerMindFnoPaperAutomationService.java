package in.aitradingsystem.tradingrooms;

import in.aitradingsystem.marketdata.MarketSessionCalendar;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Opt-in, persistent and owner-scoped virtual F&O scheduler. NEVER a broker order. */
@Service
@EnableScheduling
@ConditionalOnProperty(
    name={"minttick.upstox-fno.paper-enabled","minttick.upstox-fno.v3-feed-enabled"},
    havingValue="true")
public class CustomerMindFnoPaperAutomationService {
    private final JdbcClient jdbc;
    private final CustomerPaperFnoCapabilityController capability;
    private final CustomerMindFnoPaperCycleService cycles;
    private final Clock clock;
    private final MarketSessionCalendar sessionCalendar;

    public record State(UUID roomId,boolean enabled,Instant nextRunAt,
        Instant lastRunAt,String lastStatus,String lastReason,boolean liveMoney) {}
    public record History(UUID id,Instant evaluatedAt,String status,String reasonCode,
        String action,String strategyId,String instrumentKey,UUID fillId,
        Long realizedPnlDeltaPaise,Long cashAfterPaise,
        String orderReconciliation,String walletReconciliation,boolean liveMoney) {}
    // Exact database lease token fences stale workers after reassignment.
    private record Due(UUID room,UUID owner,Instant leaseUntil) {}

    public CustomerMindFnoPaperAutomationService(JdbcClient jdbc,
            CustomerPaperFnoCapabilityController capability,
            CustomerMindFnoPaperCycleService cycles, Clock clock,
            MarketSessionCalendar sessionCalendar) {
        this.jdbc=jdbc; this.capability=capability; this.cycles=cycles; this.clock=clock;
        this.sessionCalendar=sessionCalendar;
    }

    public State state(UUID owner, UUID room) {
        verifyRoom(owner,room);
        return jdbc.sql("""
            SELECT enabled,next_run_at,last_run_at,last_status,last_reason
            FROM mind_fno_paper_automation WHERE room_id=:room AND user_id=:owner
            """).param("owner",owner).param("room",room)
            .query((rs,i)->new State(room,rs.getBoolean("enabled"),
                rs.getTimestamp("next_run_at").toInstant(),
                rs.getTimestamp("last_run_at")==null?null:
                    rs.getTimestamp("last_run_at").toInstant(),
                rs.getString("last_status"),rs.getString("last_reason"),false))
            .optional().orElse(new State(room,false,null,null,null,null,false));
    }

    public State setEnabled(UUID owner,UUID room,boolean enabled) {
        verifyRoom(owner,room);
        if (enabled && !capability.internalCapability(owner,room).paperConfigured())
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Verified F&O display and PAPER usage rights required");
        jdbc.sql("""
            INSERT INTO mind_fno_paper_automation(room_id,user_id,enabled,next_run_at)
            VALUES(:room,:owner,:enabled,NOW())
            ON CONFLICT(room_id) DO UPDATE SET
                enabled=EXCLUDED.enabled,next_run_at=NOW(),
                lease_until=NULL,updated_at=NOW()
            WHERE mind_fno_paper_automation.user_id=EXCLUDED.user_id
            """).param("room",room).param("owner",owner)
            .param("enabled",enabled).update();
        return state(owner,room);
    }

    public List<History> history(UUID owner,UUID room) {
        verifyRoom(owner,room);
        return jdbc.sql("""
            SELECT id,evaluated_at,status,reason_code,action,strategy_id,
                instrument_key,fill_id,realized_pnl_delta_paise,cash_after_paise,
                order_reconciliation,wallet_reconciliation
            FROM mind_fno_paper_cycle_event WHERE user_id=:owner AND room_id=:room
            ORDER BY created_at DESC,id DESC LIMIT 40
            """).param("owner",owner).param("room",room)
            .query((rs,i)->new History(rs.getObject("id",UUID.class),
                rs.getTimestamp("evaluated_at").toInstant(),
                rs.getString("status"),rs.getString("reason_code"),
                rs.getString("action"),rs.getString("strategy_id"),
                rs.getString("instrument_key"),rs.getObject("fill_id",UUID.class),
                (Long)rs.getObject("realized_pnl_delta_paise"),
                (Long)rs.getObject("cash_after_paise"),
                rs.getString("order_reconciliation"),
                rs.getString("wallet_reconciliation"),false)).list();
    }

    void verifyRoom(UUID owner,UUID room) {
        if(owner==null||room==null)throw new ResponseStatusException(
            HttpStatus.UNAUTHORIZED,"Customer required");
        boolean valid=jdbc.sql("""
            SELECT EXISTS(SELECT 1 FROM customer_trading_room
              WHERE id=:room AND user_id=:owner AND mode='PAPER'
                AND status='PAPER_FUNDED' AND market_scope='FNO'
                AND focus IN ('INTRADAY','SWING'))
            """).param("room",room).param("owner",owner)
            .query(Boolean.class).single();
        if(!valid)throw new ResponseStatusException(HttpStatus.NOT_FOUND,
            "Funded F&O PAPER room unavailable");
    }

    @Scheduled(fixedDelay=15000)
    public void poll() {
        // Never poll outside official NSE F&O sessions, including holidays.
        if (!sessionCalendar.isInsideFnoSession(clock.instant())) return;
        // DB claim serializes workers across application replicas.
        List<Due> claims=jdbc.sql("""
            WITH due AS (
              SELECT room_id FROM mind_fno_paper_automation
              WHERE enabled=TRUE AND next_run_at<=NOW()
                AND (lease_until IS NULL OR lease_until<NOW())
              ORDER BY next_run_at,room_id FOR UPDATE SKIP LOCKED LIMIT 10
            )
            UPDATE mind_fno_paper_automation w
            SET lease_until=NOW()+INTERVAL '90 seconds',
                next_run_at=NOW()+INTERVAL '20 seconds',updated_at=NOW()
            FROM due WHERE w.room_id=due.room_id
            RETURNING w.room_id,w.user_id,w.lease_until
            """).query((rs,i)->new Due(
                rs.getObject("room_id",UUID.class),
                rs.getObject("user_id",UUID.class),
                rs.getTimestamp("lease_until").toInstant())).list();
        for (Due due:claims) {
            try { runClaimed(due); }
            catch(RuntimeException failure) {
                // On database failure the lease expires; do not attempt a new
                // order before the prior outcome can be reconciled.
                jdbc.sql("""
                    UPDATE mind_fno_paper_automation SET enabled=FALSE,
                        last_status='UNKNOWN',last_reason='CYCLE_JOURNAL_FAILED',
                        lease_until=NULL,updated_at=NOW()
                    WHERE room_id=:room AND user_id=:owner
                      AND lease_until=:lease AND enabled=TRUE
                    """).param("room",due.room()).param("owner",due.owner())
                        .param("lease",java.sql.Timestamp.from(due.leaseUntil())).update();
            }
        }
    }

    private void runClaimed(Due due) {
        CustomerMindFnoPaperCycleService.CycleResult result=runOrAbstain(due);
        boolean uncertain="UNKNOWN".equals(result.status())
            || result.status().startsWith("FILLED_AUDIT_")
            || "PAPER_RECONCILIATION_MISMATCH".equals(result.reasonCode());
        // This is ONE atomic PostgreSQL statement. An older process must not
        // journal or clear an expired/reassigned lease, nor overwrite a newer
        // worker's status. INSERT happens only if the UPDATE returns the
        // exact currently-owned lease; failed inserts roll back the UPDATE.
        jdbc.sql("""
            WITH accepted AS (
              UPDATE mind_fno_paper_automation SET
                lease_until=NULL,last_status=:status,last_reason=:reason,
                last_run_at=:at,
                enabled=CASE WHEN :uncertain THEN FALSE ELSE enabled END,
                updated_at=NOW()
              WHERE room_id=:room AND user_id=:owner AND enabled=TRUE
                AND lease_until=:lease AND lease_until>NOW()
              RETURNING room_id,user_id
            )
            INSERT INTO mind_fno_paper_cycle_event(
                id,room_id,user_id,evaluated_at,status,reason_code,action,
                strategy_id,instrument_key,fill_id,realized_pnl_delta_paise,
                cash_after_paise,order_reconciliation,wallet_reconciliation)
            SELECT :id,accepted.room_id,accepted.user_id,:evaluated,:status,
                :reason,:action,:strategy,:instrument,:fill,:pnl,:cash,
                :orders,:wallet
            FROM accepted
            """).param("id",UUID.randomUUID()).param("room",due.room())
            .param("owner",due.owner()).param("lease",java.sql.Timestamp.from(due.leaseUntil()))
            .param("at",java.sql.Timestamp.from(clock.instant()))
            .param("evaluated",java.sql.Timestamp.from(result.evaluatedAt()))
            .param("uncertain",uncertain)
            .param("status",result.status()).param("reason",result.reasonCode())
            .param("action",result.action()).param("strategy",result.strategyId())
            .param("instrument",result.instrumentKey()).param("fill",result.fillId())
            .param("pnl",result.realizedPnlDeltaPaise())
            .param("cash",result.walletCashAfterPaise())
            .param("orders",result.orderReconciliation())
            .param("wallet",result.walletReconciliation()).update();
    }

    private CustomerMindFnoPaperCycleService.CycleResult runOrAbstain(Due due) {
        Instant now=clock.instant();
        try {
            boolean ready=jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM mind_fno_paper_automation w
                  JOIN customer_trading_room r
                    ON r.id=w.room_id AND r.user_id=w.user_id
                  WHERE w.room_id=:room AND w.user_id=:owner AND w.enabled=TRUE
                    AND r.mode='PAPER' AND r.status='PAPER_FUNDED'
                    AND r.market_scope='FNO' AND r.focus IN ('INTRADAY','SWING')
                    AND w.lease_until=:lease AND w.lease_until>NOW())
                """).param("owner",due.owner()).param("room",due.room())
                .param("lease",java.sql.Timestamp.from(due.leaseUntil())).query(Boolean.class).single();
            if(ready)return cycles.runOptedIn(due.owner(),due.room());
            return abstain(now,due.room(),"BLOCKED","ROOM_DISABLED_OR_NOT_ELIGIBLE");
        } catch(RuntimeException failure) {
            return abstain(now,due.room(),"UNKNOWN","CYCLE_OUTCOME_UNKNOWN");
        }
    }

    private static CustomerMindFnoPaperCycleService.CycleResult abstain(
            Instant at,UUID room,String status,String reason) {
        return new CustomerMindFnoPaperCycleService.CycleResult(
            "mind.fno-paper-cycle.v1",at,room,status,reason,"NONE",
            null,null,null,null,null,null,null,false);
    }
}
