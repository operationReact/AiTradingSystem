package in.aitradingsystem.tradingrooms;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Separate customer PAPER wallet. Balances are integer paise and represent
 * virtual currency only; LIVE capital and the legacy shared PAPER account
 * are never touched. No orders/fills are supported in this stage.
 */
@Service
public class CustomerPaperWalletService {
    private final JdbcClient jdbc;

    public CustomerPaperWalletService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record PaperWallet(
        UUID roomId, String mode, String roomStatus,
        long initialCashPaise, long availableCashPaise,
        long reservedCashPaise, long realizedPnlPaise,
        boolean halted, long ledgerVersion, Instant updatedAt,
        boolean executionEnabled) {}

    public record CashEvent(UUID id, UUID roomId, String eventKind,
                            long cashDeltaPaise, long cashAfterPaise, Instant createdAt) {}

    private record RoomState(UUID id, String mode, String status, long plannedBudgetInr) {}

    /**
     * Idempotent activation under a row-level room lock. Concurrent calls
     * cannot mint duplicate starting credits or wallets.
     */
    @Transactional
    public PaperWallet activate(UUID owner, UUID roomId) {
        RoomState room = jdbc.sql("""
                SELECT id, mode, status, planned_budget_inr
                FROM customer_trading_room
                WHERE id = :roomId AND user_id = :owner
                FOR UPDATE
                """)
            .param("roomId", roomId).param("owner", owner)
            .query((rs, row) -> new RoomState(
                rs.getObject("id", UUID.class), rs.getString("mode"),
                rs.getString("status"), rs.getLong("planned_budget_inr")))
            .optional()
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Trading Room not found"));
        if (!"PAPER".equals(room.mode())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "LIVE Trading Rooms cannot receive virtual PAPER cash");
        }
        if ("DRAFT".equals(room.status())) {
            long paise = Math.multiplyExact(room.plannedBudgetInr(), 100L);
            int inserted = jdbc.sql("""
                    INSERT INTO customer_paper_account
                       (room_id, user_id, initial_cash_paise, available_cash_paise)
                    VALUES (:roomId, :owner, :paise, :paise)
                    ON CONFLICT (room_id) DO NOTHING
                    """)
                .param("roomId", roomId).param("owner", owner).param("paise", paise)
                .update();
            // The row lock and uniqueness enforce exactly-once allocation.
            if (inserted != 1) {
                throw new IllegalStateException(
                    "PAPER account exists while its room is still a draft");
            }
            jdbc.sql("""
                    INSERT INTO customer_paper_cash_event
                       (id, room_id, user_id, event_kind, cash_delta_paise, cash_after_paise)
                    VALUES (:eventId, :roomId, :owner, 'INITIAL_PAPER_ALLOCATION', :paise, :paise)
                    """)
                .param("eventId", UUID.randomUUID())
                .param("roomId", roomId).param("owner", owner)
                .param("paise", paise).update();
            jdbc.sql("""
                    UPDATE customer_trading_room
                    SET status = 'PAPER_FUNDED', updated_at = NOW()
                    WHERE id = :roomId AND user_id = :owner AND status = 'DRAFT'
                    """)
                .param("roomId", roomId).param("owner", owner).update();
        } else if (!"PAPER_FUNDED".equals(room.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Room is not eligible for PAPER activation");
        }
        return get(owner, roomId);
    }

    /** Explicit manual Paper order control. Never changes a LIVE Room. */
    @Transactional
    public PaperWallet setPaused(UUID owner, UUID roomId, boolean paused) {
        int updated = jdbc.sql("""
                UPDATE customer_paper_account a
                SET halted=:halted, ledger_version=ledger_version+1,updated_at=NOW()
                WHERE a.room_id=:room AND a.user_id=:owner
                  AND EXISTS(SELECT 1 FROM customer_trading_room r
                        WHERE r.id=a.room_id AND r.user_id=a.user_id
                          AND r.mode='PAPER' AND r.status='PAPER_FUNDED')
                """)
            .param("halted", paused).param("room", roomId)
            .param("owner", owner).update();
        if (updated != 1) throw new ResponseStatusException(
            HttpStatus.NOT_FOUND,"Funded Paper Room not found");
        return get(owner,roomId);
    }

    @Transactional(readOnly = true)
    public PaperWallet get(UUID owner, UUID roomId) {
        return jdbc.sql("""
                SELECT a.room_id, r.mode, r.status,
                       a.initial_cash_paise, a.available_cash_paise,
                       a.reserved_cash_paise, a.realized_pnl_paise,
                       a.halted, a.ledger_version, a.updated_at
                FROM customer_paper_account a
                JOIN customer_trading_room r ON r.id = a.room_id AND r.user_id = a.user_id
                WHERE a.room_id = :roomId AND a.user_id = :owner
                  AND r.mode = 'PAPER' AND r.status = 'PAPER_FUNDED'
                """)
            .param("roomId", roomId).param("owner", owner)
            .query(CustomerPaperWalletService::map)
            .optional()
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "PAPER wallet not found"));
    }

    @Transactional(readOnly = true)
    public List<CashEvent> ledger(UUID owner, UUID roomId) {
        // Ensure room ownership and activation before returning any journal row.
        get(owner, roomId);
        return jdbc.sql("""
                SELECT id, room_id, event_kind, cash_delta_paise,
                       cash_after_paise, created_at
                FROM customer_paper_cash_event
                WHERE room_id = :roomId AND user_id = :owner
                ORDER BY created_at ASC, id ASC LIMIT 100
                """)
            .param("roomId", roomId).param("owner", owner)
            .query((rs, row) -> new CashEvent(
                rs.getObject("id", UUID.class), rs.getObject("room_id", UUID.class),
                rs.getString("event_kind"), rs.getLong("cash_delta_paise"),
                rs.getLong("cash_after_paise"),
                rs.getTimestamp("created_at").toInstant()))
            .list();
    }

    private static PaperWallet map(ResultSet rs, int row) throws SQLException {
        return new PaperWallet(
            rs.getObject("room_id", UUID.class),
            rs.getString("mode"), rs.getString("status"),
            rs.getLong("initial_cash_paise"),
            rs.getLong("available_cash_paise"), rs.getLong("reserved_cash_paise"),
            rs.getLong("realized_pnl_paise"), rs.getBoolean("halted"),
            rs.getLong("ledger_version"),
            rs.getTimestamp("updated_at").toInstant(),
            !rs.getBoolean("halted") // Manual Paper fills only; not automated trading.
        );
    }
}
