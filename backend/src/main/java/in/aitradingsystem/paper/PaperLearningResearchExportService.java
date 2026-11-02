package in.aitradingsystem.paper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaperLearningResearchExportService {
    public static final String CONTRACT_VERSION = "paper-closed-trade.v1";
    static final int MAX_LIMIT = 5_000;

    private final JdbcClient jdbc;

    public PaperLearningResearchExportService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Exports only allocations belonging to fully exhausted, non-invalidated entry lots.
     * Active or invalidated lineage is intentionally invisible to offline learning.
     */
    @Transactional(readOnly = true)
    public PaperLearningResearchExport export(int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }
        List<PaperLearningResearchExport.ClosedTrade> rows = jdbc.sql("""
                SELECT c.id AS allocation_id,
                       l.id AS lot_id,
                       l.entry_order_id,
                       l.entry_fill_id,
                       c.exit_order_id,
                       c.exit_fill_id,
                       l.instrument_id,
                       l.exchange,
                       l.symbol,
                       l.product_type,
                       entry_order.execution_origin,
                       c.allocated_quantity,
                       entry_fill.reference_price AS expected_entry_price,
                       l.entry_price,
                       c.exit_price,
                       l.entry_fee_amount,
                       l.entry_slippage_amount,
                       l.entry_quantity,
                       c.allocated_fee_amount AS exit_fee_amount,
                       c.allocated_slippage_amount AS exit_slippage_amount,
                       c.allocated_realized_pnl,
                       c.exit_reason,
                       l.opened_at,
                       c.closed_at
                  FROM paper_trade_lot_close c
                  JOIN paper_trade_lot l ON l.id = c.lot_id
                  JOIN paper_order entry_order ON entry_order.id = l.entry_order_id
                  JOIN paper_fill entry_fill ON entry_fill.id = l.entry_fill_id
                 WHERE l.lineage_status = 'EXHAUSTED'
                   AND l.remaining_quantity = 0
                 ORDER BY c.closed_at, c.id
                 LIMIT :limit
                """)
            .param("limit", limit)
            .query((rs, rowNum) -> {
                int quantity = rs.getInt("allocated_quantity");
                int entryQuantity = rs.getInt("entry_quantity");
                BigDecimal entryFee = allocate(rs.getBigDecimal("entry_fee_amount"), quantity, entryQuantity);
                BigDecimal entrySlippage = allocate(
                    rs.getBigDecimal("entry_slippage_amount"), quantity, entryQuantity);
                return new PaperLearningResearchExport.ClosedTrade(
                    rs.getObject("allocation_id", java.util.UUID.class),
                    rs.getObject("lot_id", java.util.UUID.class),
                    rs.getObject("entry_order_id", java.util.UUID.class),
                    rs.getObject("entry_fill_id", java.util.UUID.class),
                    rs.getObject("exit_order_id", java.util.UUID.class),
                    rs.getObject("exit_fill_id", java.util.UUID.class),
                    rs.getObject("instrument_id", Long.class),
                    rs.getString("exchange"),
                    rs.getString("symbol"),
                    rs.getString("product_type"),
                    rs.getString("execution_origin"),
                    quantity,
                    rs.getBigDecimal("expected_entry_price"),
                    rs.getBigDecimal("entry_price"),
                    rs.getBigDecimal("exit_price"),
                    entryFee,
                    rs.getBigDecimal("exit_fee_amount"),
                    entrySlippage,
                    rs.getBigDecimal("exit_slippage_amount"),
                    rs.getBigDecimal("allocated_realized_pnl"),
                    rs.getString("exit_reason"),
                    instant(rs.getObject("opened_at", OffsetDateTime.class)),
                    instant(rs.getObject("closed_at", OffsetDateTime.class)));
            })
            .list();
        return new PaperLearningResearchExport(CONTRACT_VERSION, Instant.now(), List.copyOf(rows));
    }

    private static BigDecimal allocate(BigDecimal total, int quantity, int totalQuantity) {
        if (total == null || totalQuantity <= 0) {
            throw new IllegalStateException("closed PAPER lineage contains invalid entry allocation data");
        }
        return total.multiply(BigDecimal.valueOf(quantity))
            .divide(BigDecimal.valueOf(totalQuantity), 2, RoundingMode.HALF_UP);
    }

    private static Instant instant(OffsetDateTime value) {
        if (value == null) throw new IllegalStateException("closed PAPER lineage timestamp is missing");
        return value.toInstant();
    }
}
