package in.aitradingsystem.paper;

import in.aitradingsystem.domain.ProductType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Deterministic long-only PAPER position accounting, separated by product type. */
@Service
public class PositionAccountingService {
    private final JdbcClient jdbc;

    public PositionAccountingService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public AccountingResult applyBuy(Long instrumentId, String exchange, String symbol, ProductType productType,
                                     int quantity, BigDecimal fillCostBasis, Instant now) {
        requireCommon(symbol, productType, quantity, now);
        if (fillCostBasis == null || fillCostBasis.signum() <= 0) {
            throw new IllegalArgumentException("fillCostBasis must be positive");
        }
        PositionRow existing = lockPosition(instrumentId, symbol, productType);
        if (existing == null) {
            BigDecimal average = average(fillCostBasis, quantity);
            jdbc.sql("""
                    INSERT INTO paper_position (
                        instrument_id, exchange, symbol, product_type, quantity, cost_basis, average_price,
                        realized_pnl, opened_at, updated_at)
                    VALUES (:instrumentId, :exchange, :symbol, :productType, :quantity, :costBasis, :averagePrice,
                        0, :now, :now)
                    """)
                .param("instrumentId", instrumentId, Types.BIGINT)
                .param("exchange", exchange, Types.VARCHAR)
                .param("symbol", symbol)
                .param("productType", productType.name())
                .param("quantity", quantity)
                .param("costBasis", money(fillCostBasis))
                .param("averagePrice", average)
                .param("now", ts(now))
                .update();
            return new AccountingResult(quantity, money(fillCostBasis), average, BigDecimal.ZERO.setScale(2));
        }

        int newQuantity = Math.addExact(existing.quantity(), quantity);
        BigDecimal newCostBasis = money(existing.costBasis().add(fillCostBasis));
        BigDecimal newAverage = average(newCostBasis, newQuantity);
        int updated = jdbc.sql("""
                UPDATE paper_position
                   SET quantity = :quantity, cost_basis = :costBasis, average_price = :averagePrice,
                       exchange = COALESCE(:exchange, exchange), instrument_id = COALESCE(:instrumentId, instrument_id),
                       updated_at = :now
                 WHERE symbol = :symbol AND product_type = :productType AND quantity = :expectedQuantity
                """)
            .param("quantity", newQuantity)
            .param("costBasis", newCostBasis)
            .param("averagePrice", newAverage)
            .param("exchange", exchange, Types.VARCHAR)
            .param("instrumentId", instrumentId, Types.BIGINT)
            .param("now", ts(now))
            .param("symbol", existing.symbol())
            .param("productType", productType.name())
            .param("expectedQuantity", existing.quantity())
            .update();
        if (updated != 1) throw new IllegalStateException("Concurrent PAPER position update detected");
        return new AccountingResult(newQuantity, newCostBasis, newAverage, existing.realizedPnl());
    }

    @Transactional
    public AccountingResult applySell(Long instrumentId, String symbol, ProductType productType,
                                      int quantity, BigDecimal netProceeds, Instant now) {
        requireCommon(symbol, productType, quantity, now);
        if (netProceeds == null || netProceeds.signum() < 0) {
            throw new IllegalArgumentException("netProceeds must be non-negative");
        }
        PositionRow existing = lockPosition(instrumentId, symbol, productType);
        if (existing == null || quantity > existing.quantity()) {
            throw new IllegalStateException("Short PAPER positions are not allowed");
        }

        BigDecimal soldCostBasis = existing.quantity() == quantity
            ? existing.costBasis()
            : money(existing.costBasis().multiply(BigDecimal.valueOf(quantity))
                .divide(BigDecimal.valueOf(existing.quantity()), 8, RoundingMode.HALF_UP));
        BigDecimal realized = money(netProceeds.subtract(soldCostBasis));
        BigDecimal cumulativeRealized = money(existing.realizedPnl().add(realized));
        int remaining = existing.quantity() - quantity;

        if (remaining == 0) {
            int deleted = jdbc.sql("DELETE FROM paper_position WHERE symbol = :symbol AND product_type = :productType")
                .param("symbol", existing.symbol())
                .param("productType", productType.name())
                .update();
            if (deleted != 1) throw new IllegalStateException("Concurrent PAPER position update detected");
            return new AccountingResult(0, BigDecimal.ZERO.setScale(2), null, cumulativeRealized);
        }

        BigDecimal remainingCost = money(existing.costBasis().subtract(soldCostBasis));
        BigDecimal remainingAverage = average(remainingCost, remaining);
        int updated = jdbc.sql("""
                UPDATE paper_position
                   SET quantity = :quantity, cost_basis = :costBasis, average_price = :averagePrice,
                       realized_pnl = :realizedPnl, updated_at = :now
                 WHERE symbol = :symbol AND product_type = :productType AND quantity = :expectedQuantity
                """)
            .param("quantity", remaining)
            .param("costBasis", remainingCost)
            .param("averagePrice", remainingAverage)
            .param("realizedPnl", cumulativeRealized)
            .param("now", ts(now))
            .param("symbol", existing.symbol())
            .param("productType", productType.name())
            .param("expectedQuantity", existing.quantity())
            .update();
        if (updated != 1) throw new IllegalStateException("Concurrent PAPER position update detected");
        return new AccountingResult(remaining, remainingCost, remainingAverage, cumulativeRealized);
    }

    private PositionRow lockPosition(Long instrumentId, String symbol, ProductType productType) {
        return jdbc.sql("""
                SELECT symbol, quantity, cost_basis, realized_pnl
                  FROM paper_position
                 WHERE product_type = :productType
                   AND ((:instrumentId IS NOT NULL AND instrument_id = :instrumentId)
                        OR (:instrumentId IS NULL AND instrument_id IS NULL AND symbol = :symbol))
                 FOR UPDATE
                """)
            .param("productType", productType.name())
            .param("instrumentId", instrumentId, Types.BIGINT)
            .param("symbol", symbol)
            .query((rs, row) -> new PositionRow(
                rs.getString("symbol"), rs.getInt("quantity"), rs.getBigDecimal("cost_basis"),
                rs.getBigDecimal("realized_pnl")))
            .optional().orElse(null);
    }

    private static void requireCommon(String symbol, ProductType productType, int quantity, Instant now) {
        if (symbol == null || symbol.isBlank()) throw new IllegalArgumentException("symbol is required");
        if (productType == null) throw new IllegalArgumentException("productType is required");
        if (quantity <= 0) throw new IllegalArgumentException("quantity must be positive");
        if (now == null) throw new IllegalArgumentException("now is required");
    }

    private static BigDecimal average(BigDecimal costBasis, int quantity) {
        return costBasis.divide(BigDecimal.valueOf(quantity), 6, RoundingMode.HALF_UP);
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static OffsetDateTime ts(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private record PositionRow(String symbol, int quantity, BigDecimal costBasis, BigDecimal realizedPnl) {}

    public record AccountingResult(int quantity, BigDecimal costBasis, BigDecimal averagePrice, BigDecimal realizedPnl) {}
}
