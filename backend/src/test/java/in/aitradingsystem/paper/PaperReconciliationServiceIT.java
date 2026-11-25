package in.aitradingsystem.paper;

import static org.assertj.core.api.Assertions.assertThat;

import in.aitradingsystem.support.AbstractPostgresIT;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class PaperReconciliationServiceIT extends AbstractPostgresIT {
    @Autowired PaperReconciliationService reconciliation;
    @Autowired JdbcClient jdbc;

    private BigDecimal originalCash;
    private UUID seededOrder;

    @AfterEach
    void cleanup() {
        if (seededOrder != null) {
            jdbc.sql("DELETE FROM paper_reconciliation_difference").update();
            jdbc.sql("DELETE FROM paper_reconciliation_run").update();
            jdbc.sql("DELETE FROM paper_fill WHERE order_id = :id").param("id", seededOrder).update();
            jdbc.sql("DELETE FROM paper_cash_entry WHERE order_id = :id").param("id", seededOrder).update();
            jdbc.sql("DELETE FROM paper_order WHERE id = :id").param("id", seededOrder).update();
        } else {
            jdbc.sql("DELETE FROM paper_reconciliation_difference").update();
            jdbc.sql("DELETE FROM paper_reconciliation_run").update();
        }
        if (originalCash != null) {
            jdbc.sql("UPDATE paper_account SET cash_balance = :cash WHERE id = 1")
                .param("cash", originalCash).update();
        }
    }

    @Test
    void cleanBootstrapBookProducesCleanRun() {
        var result = reconciliation.run();

        assertThat(result.status()).isEqualTo("CLEAN");
        assertThat(result.differenceCount()).isZero();
        assertThat(result.checksPerformed()).isPositive();
        assertThat(jdbc.sql("SELECT status FROM paper_reconciliation_run WHERE id = :id")
            .param("id", result.runId()).query(String.class).single()).isEqualTo("CLEAN");
    }

    @Test
    void cashMismatchIsPersistedWithoutRepairingSourceLedger() {
        originalCash = jdbc.sql("SELECT cash_balance FROM paper_account WHERE id = 1")
            .query(BigDecimal.class).single();
        BigDecimal corrupted = originalCash.subtract(new BigDecimal("123.45"));
        jdbc.sql("UPDATE paper_account SET cash_balance = :cash WHERE id = 1")
            .param("cash", corrupted).update();

        var result = reconciliation.run();

        assertThat(result.status()).isEqualTo("DIFFERENCES");
        assertThat(result.differences()).extracting(PaperReconciliationService.Difference::code)
            .contains("CASH_LEDGER_MISMATCH");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM paper_reconciliation_difference WHERE run_id = :runId AND difference_code = 'CASH_LEDGER_MISMATCH'")
            .param("runId", result.runId()).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT cash_balance FROM paper_account WHERE id = 1")
            .query(BigDecimal.class).single()).isEqualByComparingTo(corrupted);
    }

    @Test
    void rejectedOrderSettlementIsDetectedAndPreservedAsHistoricalEvidence() {
        seededOrder = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                INSERT INTO paper_order (
                    id, idempotency_key, request_hash, symbol, side, quantity, submitted_quantity,
                    filled_quantity, order_type, product_type, reference_price, stop_loss, target_price,
                    status, created_at, updated_at)
                VALUES (:id, :key, :hash, 'P6RECON', 'BUY', 1, 1, 1, 'MARKET', 'DELIVERY',
                    100, 95, 110, 'REJECTED', :now, :now)
                """)
            .param("id", seededOrder)
            .param("key", "recon-" + seededOrder)
            .param("hash", "b".repeat(64))
            .param("now", now)
            .update();
        jdbc.sql("""
                INSERT INTO paper_fill (
                    id, order_id, fill_sequence, symbol, side, quantity, reference_price, fill_price,
                    gross_amount, fee_amount, slippage_amount, net_amount, cost_basis, realized_pnl, filled_at)
                VALUES (:fillId, :orderId, 1, 'P6RECON', 'BUY', 1, 100, 100,
                    100, 0, 0, -100, 100, 0, :now)
                """)
            .param("fillId", UUID.randomUUID())
            .param("orderId", seededOrder)
            .param("now", now)
            .update();

        var result = reconciliation.run();

        assertThat(result.differences()).extracting(PaperReconciliationService.Difference::code)
            .contains("REJECTED_ORDER_HAS_SETTLEMENT");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM paper_fill WHERE order_id = :id")
            .param("id", seededOrder).query(Integer.class).single()).isEqualTo(1);
    }
}
