package in.aitradingsystem.paper;

import java.math.BigDecimal;
import java.sql.Types;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PaperAccountRepository {
    private final JdbcClient jdbc;

    public PaperAccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public AccountSnapshot lock() {
        return jdbc.sql("SELECT starting_capital, cash_balance FROM paper_account WHERE id = 1 FOR UPDATE")
            .query((rs, row) -> new AccountSnapshot(rs.getBigDecimal("starting_capital"), rs.getBigDecimal("cash_balance")))
            .optional().orElseThrow(() -> new IllegalStateException("Paper account is not initialized"));
    }

    public void updateCash(BigDecimal cash, Instant now) {
        jdbc.sql("UPDATE paper_account SET cash_balance = :cash, updated_at = :now WHERE id = 1")
            .param("cash", cash).param("now", PaperOrderRepository.ts(now)).update();
    }

    public void appendCashEntry(UUID orderId, String type, BigDecimal amount, BigDecimal balanceAfter, Instant now) {
        jdbc.sql("""
                INSERT INTO paper_cash_entry (order_id, entry_type, amount, balance_after, created_at)
                VALUES (:orderId, :type, :amount, :balanceAfter, :now)
                """)
            .param("orderId", orderId).param("type", type).param("amount", amount)
            .param("balanceAfter", balanceAfter).param("now", PaperOrderRepository.ts(now)).update();
    }

    public void setNewOrdersHalted(boolean halted, String reason, Instant now) {
        jdbc.sql("UPDATE trading_control SET new_orders_halted = :halted, reason = :reason, updated_at = :now WHERE id = 1")
            .param("halted", halted).param("reason", reason, Types.VARCHAR)
            .param("now", PaperOrderRepository.ts(now)).update();
    }

    public boolean isHalted() {
        return jdbc.sql("SELECT new_orders_halted FROM trading_control WHERE id = 1").query(Boolean.class).single();
    }

    public record AccountSnapshot(BigDecimal startingCapital, BigDecimal cash) {}
}
