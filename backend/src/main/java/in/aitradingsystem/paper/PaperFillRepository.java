package in.aitradingsystem.paper;

import in.aitradingsystem.domain.Side;
import java.math.BigDecimal;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Append-only persistence boundary for ordered PAPER fills. */
@Repository
public class PaperFillRepository {
    private final JdbcClient jdbc;

    public PaperFillRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Appends one immutable fill for an order. The database uniqueness constraint on
     * {@code (order_id, fill_sequence)} prevents accidental duplicate application.
     */
    public UUID appendFill(UUID orderId, int fillSequence, FillWrite fill) {
        if (fillSequence <= 0) {
            throw new IllegalArgumentException("fillSequence must be positive");
        }
        UUID fillId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO paper_fill (
                    id, order_id, fill_sequence, symbol, side, quantity, reference_price, fill_price,
                    gross_amount, fee_amount, slippage_amount, net_amount, cost_basis, realized_pnl,
                    execution_model_version, liquidity_assumption, filled_at)
                VALUES (
                    :id, :orderId, :sequence, :symbol, :side, :quantity, :referencePrice, :fillPrice,
                    :grossAmount, :feeAmount, :slippageAmount, :netAmount, :costBasis, :realizedPnl,
                    :executionModelVersion, :liquidityAssumption, :filledAt)
                """)
            .param("id", fillId)
            .param("orderId", orderId)
            .param("sequence", fillSequence)
            .param("symbol", fill.symbol())
            .param("side", fill.side().name())
            .param("quantity", fill.quantity())
            .param("referencePrice", fill.referencePrice())
            .param("fillPrice", fill.fillPrice())
            .param("grossAmount", fill.grossAmount())
            .param("feeAmount", fill.feeAmount())
            .param("slippageAmount", fill.slippageAmount())
            .param("netAmount", fill.netAmount())
            .param("costBasis", fill.costBasis())
            .param("realizedPnl", fill.realizedPnl())
            .param("executionModelVersion", fill.executionModelVersion(), Types.VARCHAR)
            .param("liquidityAssumption", fill.liquidityAssumption(), Types.VARCHAR)
            .param("filledAt", OffsetDateTime.ofInstant(fill.filledAt(), ZoneOffset.UTC))
            .update();
        return fillId;
    }

    public record FillWrite(
        String symbol,
        Side side,
        int quantity,
        BigDecimal referencePrice,
        BigDecimal fillPrice,
        BigDecimal grossAmount,
        BigDecimal feeAmount,
        BigDecimal slippageAmount,
        BigDecimal netAmount,
        BigDecimal costBasis,
        BigDecimal realizedPnl,
        String executionModelVersion,
        String liquidityAssumption,
        Instant filledAt
    ) {
        public FillWrite {
            if (symbol == null || symbol.isBlank()) throw new IllegalArgumentException("symbol is required");
            if (side == null) throw new IllegalArgumentException("side is required");
            if (quantity <= 0) throw new IllegalArgumentException("quantity must be positive");
            if (filledAt == null) throw new IllegalArgumentException("filledAt is required");
        }
    }
}
