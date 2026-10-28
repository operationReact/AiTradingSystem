package in.aitradingsystem.paper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only reconciliation evidence derived from the persisted PAPER ledger. */
@Service
@Transactional(readOnly = true)
public class PaperReconciliationService {
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    private final JdbcClient jdbc;
    private final Clock clock;

    public PaperReconciliationService(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public ReconciliationStatus status() {
        BigDecimal accountCash = money(jdbc.sql("SELECT cash_balance FROM paper_account WHERE id = 1")
            .query(BigDecimal.class).single());
        BigDecimal ledgerCash = money(jdbc.sql("SELECT COALESCE(SUM(amount), 0) FROM paper_cash_entry")
            .query(BigDecimal.class).single());
        long orderCount = jdbc.sql("SELECT COUNT(*) FROM paper_order").query(Long.class).single();
        long filledOrderCount = jdbc.sql("SELECT COUNT(*) FROM paper_order WHERE status = 'FILLED'")
            .query(Long.class).single();
        long fillCount = jdbc.sql("SELECT COUNT(*) FROM paper_fill").query(Long.class).single();
        long orphanFillCount = jdbc.sql("""
                SELECT COUNT(*) FROM paper_fill f
                LEFT JOIN paper_order o ON o.id = f.order_id
                WHERE o.id IS NULL
                """).query(Long.class).single();
        long filledWithoutFillCount = jdbc.sql("""
                SELECT COUNT(*) FROM paper_order o
                LEFT JOIN paper_fill f ON f.order_id = o.id
                WHERE o.status = 'FILLED' AND f.id IS NULL
                """).query(Long.class).single();
        long positionCount = jdbc.sql("SELECT COUNT(*) FROM paper_position").query(Long.class).single();
        long invalidPositionCount = jdbc.sql("SELECT COUNT(*) FROM paper_position WHERE quantity <= 0 OR cost_basis < 0")
            .query(Long.class).single();

        BigDecimal cashDelta = money(accountCash.subtract(ledgerCash));
        boolean reconciled = cashDelta.compareTo(ZERO) == 0
            && orphanFillCount == 0
            && filledWithoutFillCount == 0
            && invalidPositionCount == 0;

        return new ReconciliationStatus(reconciled, accountCash, ledgerCash, cashDelta,
            orderCount, filledOrderCount, fillCount, orphanFillCount, filledWithoutFillCount,
            positionCount, invalidPositionCount, Instant.now(clock));
    }

    private static BigDecimal money(BigDecimal value) {
        return PaperExecutionModel.money(value == null ? ZERO : value);
    }

    public record ReconciliationStatus(
        boolean reconciled,
        BigDecimal accountCash,
        BigDecimal ledgerCash,
        BigDecimal cashDelta,
        long orderCount,
        long filledOrderCount,
        long fillCount,
        long orphanFillCount,
        long filledWithoutFillCount,
        long positionCount,
        long invalidPositionCount,
        Instant asOf) {}
}
