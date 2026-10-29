package in.aitradingsystem.paper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class PaperLedgerQueryService {
    private final JdbcClient jdbc;

    public PaperLedgerQueryService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<FillLedgerView> recentFills(int limit) {
        return jdbc.sql("""
                SELECT f.id, f.order_id, f.fill_sequence, f.symbol, f.side, f.quantity,
                       f.fill_price, f.gross_amount, f.fee_amount, f.slippage_amount,
                       f.net_amount, f.cost_basis, f.realized_pnl, f.filled_at,
                       o.product_type
                  FROM paper_fill f
                  JOIN paper_order o ON o.id = f.order_id
                 ORDER BY f.filled_at DESC, f.fill_sequence DESC, f.id DESC
                 LIMIT :limit
                """)
            .param("limit", limit)
            .query((rs, row) -> new FillLedgerView(
                rs.getObject("id", UUID.class),
                rs.getObject("order_id", UUID.class),
                rs.getInt("fill_sequence"),
                rs.getString("symbol"),
                rs.getString("side"),
                rs.getString("product_type"),
                rs.getInt("quantity"),
                rs.getBigDecimal("fill_price"),
                rs.getBigDecimal("gross_amount"),
                rs.getBigDecimal("fee_amount"),
                rs.getBigDecimal("slippage_amount"),
                rs.getBigDecimal("net_amount"),
                rs.getBigDecimal("cost_basis"),
                rs.getBigDecimal("realized_pnl"),
                PaperTradingService.instant(rs, "filled_at")))
            .list();
    }

    public List<CashLedgerView> recentCashEntries(int limit) {
        return jdbc.sql("""
                SELECT e.id, e.order_id, e.entry_type, e.amount, e.balance_after, e.created_at,
                       o.symbol, o.product_type
                  FROM paper_cash_entry e
                  LEFT JOIN paper_order o ON o.id = e.order_id
                 ORDER BY e.created_at DESC, e.id DESC
                 LIMIT :limit
                """)
            .param("limit", limit)
            .query((rs, row) -> new CashLedgerView(
                rs.getLong("id"),
                rs.getObject("order_id", UUID.class),
                rs.getString("entry_type"),
                rs.getString("symbol"),
                rs.getString("product_type"),
                rs.getBigDecimal("amount"),
                rs.getBigDecimal("balance_after"),
                PaperTradingService.instant(rs, "created_at")))
            .list();
    }

    public record FillLedgerView(UUID id, UUID orderId, int fillSequence, String symbol, String side,
                                 String productType, int quantity, BigDecimal fillPrice,
                                 BigDecimal grossAmount, BigDecimal feeAmount, BigDecimal slippageAmount,
                                 BigDecimal netAmount, BigDecimal costBasis, BigDecimal realizedPnl,
                                 Instant filledAt) {}

    public record CashLedgerView(long id, UUID orderId, String entryType, String symbol, String productType,
                                 BigDecimal amount, BigDecimal balanceAfter, Instant createdAt) {}
}
