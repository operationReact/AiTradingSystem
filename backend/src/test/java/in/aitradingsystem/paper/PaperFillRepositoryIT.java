package in.aitradingsystem.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.aitradingsystem.domain.Side;
import in.aitradingsystem.paper.PaperFillRepository.FillWrite;
import in.aitradingsystem.support.AbstractPostgresIT;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

class PaperFillRepositoryIT extends AbstractPostgresIT {
    @Autowired PaperFillRepository fills;
    @Autowired JdbcClient jdbc;

    private UUID orderId;

    @AfterEach
    void cleanup() {
        if (orderId != null) {
            jdbc.sql("DELETE FROM paper_fill WHERE order_id = :id").param("id", orderId).update();
            jdbc.sql("DELETE FROM paper_order WHERE id = :id").param("id", orderId).update();
        }
    }

    @Test
    void appendsMultipleOrderedFillsForOneOrderWithProvenance() {
        seedOrder(10);

        UUID first = fills.appendFill(orderId, 1, fill(4, "PAPER_EXECUTION_V1", "FULL_LIQUIDITY"));
        UUID second = fills.appendFill(orderId, 2, fill(6, "PAPER_EXECUTION_V1", "FULL_LIQUIDITY"));

        List<StoredFill> stored = jdbc.sql("""
                SELECT id, fill_sequence, quantity, execution_model_version, liquidity_assumption
                FROM paper_fill WHERE order_id = :orderId ORDER BY fill_sequence
                """)
            .param("orderId", orderId)
            .query((rs, n) -> new StoredFill(
                rs.getObject("id", UUID.class),
                rs.getInt("fill_sequence"),
                rs.getInt("quantity"),
                rs.getString("execution_model_version"),
                rs.getString("liquidity_assumption")))
            .list();

        assertThat(stored).containsExactly(
            new StoredFill(first, 1, 4, "PAPER_EXECUTION_V1", "FULL_LIQUIDITY"),
            new StoredFill(second, 2, 6, "PAPER_EXECUTION_V1", "FULL_LIQUIDITY"));
    }

    @Test
    void duplicateFillSequenceForSameOrderIsRejected() {
        seedOrder(10);
        fills.appendFill(orderId, 1, fill(4, "PAPER_EXECUTION_V1", "FULL_LIQUIDITY"));

        assertThatThrownBy(() -> fills.appendFill(orderId, 1, fill(6, "PAPER_EXECUTION_V1", "FULL_LIQUIDITY")))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void fillSequenceMustBePositive() {
        seedOrder(10);
        assertThatThrownBy(() -> fills.appendFill(orderId, 0, fill(10, null, null)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("fillSequence");
    }

    private void seedOrder(int quantity) {
        orderId = UUID.randomUUID();
        String key = "fill-it-" + orderId;
        jdbc.sql("""
                INSERT INTO paper_order (
                    id, idempotency_key, request_hash, symbol, side, quantity, submitted_quantity,
                    filled_quantity, reference_price, stop_loss, target_price, status, created_at, updated_at)
                VALUES (:id, :key, :hash, 'TESTFILL', 'BUY', :qty, :qty, 0, 100, 95, 110, 'READY', :now, :now)
                """)
            .param("id", orderId)
            .param("key", key)
            .param("hash", "a".repeat(64))
            .param("qty", quantity)
            .param("now", OffsetDateTime.now(ZoneOffset.UTC))
            .update();
    }

    private static FillWrite fill(int quantity, String modelVersion, String liquidity) {
        BigDecimal fillPrice = new BigDecimal("100.10");
        BigDecimal gross = fillPrice.multiply(BigDecimal.valueOf(quantity));
        return new FillWrite(
            "TESTFILL",
            Side.BUY,
            quantity,
            new BigDecimal("100.00"),
            fillPrice,
            gross,
            new BigDecimal("1.00"),
            new BigDecimal("0.10"),
            gross.add(new BigDecimal("1.00")).negate(),
            gross.add(new BigDecimal("1.00")),
            BigDecimal.ZERO,
            modelVersion,
            liquidity,
            Instant.now());
    }

    private record StoredFill(UUID id, int sequence, int quantity, String modelVersion, String liquidityAssumption) {}
}
