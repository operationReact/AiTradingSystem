package in.aitradingsystem.paper;

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
     * Entry fees/slippage are allocated across every close of a lot before the response limit is
     * applied, with the final allocation receiving the rounding residual so monetary totals reconcile.
     */
    @Transactional(readOnly = true)
    public PaperLearningResearchExport export(int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }
        List<PaperLearningResearchExport.ClosedTrade> rows = jdbc.sql("""
                WITH base AS (
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
                           entry_fill.side AS entry_side,
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
                           c.closed_at,
                           row_number() OVER (
                               PARTITION BY l.id ORDER BY c.closed_at, c.id
                           ) AS allocation_number,
                           count(*) OVER (PARTITION BY l.id) AS allocation_count,
                           round(
                               l.entry_fee_amount * c.allocated_quantity::numeric / l.entry_quantity,
                               2
                           ) AS rounded_entry_fee,
                           round(
                               l.entry_slippage_amount * c.allocated_quantity::numeric / l.entry_quantity,
                               2
                           ) AS rounded_entry_slippage
                      FROM paper_trade_lot_close c
                      JOIN paper_trade_lot l ON l.id = c.lot_id
                      JOIN paper_order entry_order ON entry_order.id = l.entry_order_id
                      JOIN paper_fill entry_fill ON entry_fill.id = l.entry_fill_id
                     WHERE l.lineage_status = 'EXHAUSTED'
                       AND l.remaining_quantity = 0
                ), allocated AS (
                    SELECT base.*,
                           CASE
                               WHEN allocation_number < allocation_count THEN rounded_entry_fee
                               ELSE entry_fee_amount - COALESCE(
                                   sum(rounded_entry_fee) OVER (
                                       PARTITION BY lot_id ORDER BY allocation_number
                                       ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING
                                   ), 0)
                           END AS allocated_entry_fee_amount,
                           CASE
                               WHEN allocation_number < allocation_count THEN rounded_entry_slippage
                               ELSE entry_slippage_amount - COALESCE(
                                   sum(rounded_entry_slippage) OVER (
                                       PARTITION BY lot_id ORDER BY allocation_number
                                       ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING
                                   ), 0)
                           END AS allocated_entry_slippage_amount
                      FROM base
                )
                SELECT allocation_id,
                       lot_id,
                       entry_order_id,
                       entry_fill_id,
                       exit_order_id,
                       exit_fill_id,
                       instrument_id,
                       exchange,
                       symbol,
                       product_type,
                       execution_origin,
                       entry_side,
                       allocated_quantity,
                       expected_entry_price,
                       entry_price,
                       exit_price,
                       allocated_entry_fee_amount,
                       exit_fee_amount,
                       allocated_entry_slippage_amount,
                       exit_slippage_amount,
                       allocated_realized_pnl,
                       exit_reason,
                       opened_at,
                       closed_at
                  FROM allocated
                 ORDER BY closed_at, allocation_id
                 LIMIT :limit
                """)
            .param("limit", limit)
            .query((rs, rowNum) -> {
                Object instrumentValue = rs.getObject("instrument_id");
                Long instrumentId = instrumentValue == null ? null : ((Number) instrumentValue).longValue();
                return new PaperLearningResearchExport.ClosedTrade(
                    rs.getObject("allocation_id", java.util.UUID.class),
                    rs.getObject("lot_id", java.util.UUID.class),
                    rs.getObject("entry_order_id", java.util.UUID.class),
                    rs.getObject("entry_fill_id", java.util.UUID.class),
                    rs.getObject("exit_order_id", java.util.UUID.class),
                    rs.getObject("exit_fill_id", java.util.UUID.class),
                    instrumentId,
                    rs.getString("exchange"),
                    rs.getString("symbol"),
                    rs.getString("product_type"),
                    rs.getString("execution_origin"),
                    rs.getString("entry_side"),
                    rs.getInt("allocated_quantity"),
                    rs.getBigDecimal("expected_entry_price"),
                    rs.getBigDecimal("entry_price"),
                    rs.getBigDecimal("exit_price"),
                    rs.getBigDecimal("allocated_entry_fee_amount"),
                    rs.getBigDecimal("exit_fee_amount"),
                    rs.getBigDecimal("allocated_entry_slippage_amount"),
                    rs.getBigDecimal("exit_slippage_amount"),
                    rs.getBigDecimal("allocated_realized_pnl"),
                    rs.getString("exit_reason"),
                    instant(rs.getObject("opened_at", OffsetDateTime.class)),
                    instant(rs.getObject("closed_at", OffsetDateTime.class)));
            })
            .list();
        return new PaperLearningResearchExport(CONTRACT_VERSION, Instant.now(), List.copyOf(rows));
    }

    private static Instant instant(OffsetDateTime value) {
        if (value == null) throw new IllegalStateException("closed PAPER lineage timestamp is missing");
        return value.toInstant();
    }
}
