package in.aitradingsystem.paper;

import in.aitradingsystem.domain.ProductType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Persists exact entry-fill to exit-fill lineage for PAPER outcome learning.
 *
 * <p>The existing position/account ledger remains the accounting authority. This repository adds a
 * deterministic FIFO attribution layer so a later research outcome can identify the exact entry
 * orders/fills that an exit consumed. Close-allocation rows are append-only; only the lot's derived
 * remaining quantity is updated while the surrounding PAPER order transaction is locked.
 */
@Repository
public class PaperTradeLotRepository {
    private final JdbcClient jdbc;

    public PaperTradeLotRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public UUID openLot(UUID entryOrderId, UUID entryFillId, Long instrumentId, String exchange,
                        String symbol, ProductType productType, int quantity, BigDecimal entryPrice,
                        BigDecimal entryCostBasis, BigDecimal entryFeeAmount,
                        BigDecimal entrySlippageAmount, Instant openedAt) {
        requireIdentity(entryOrderId, entryFillId, symbol, productType, quantity, openedAt);
        requirePositive(entryPrice, "entryPrice");
        requirePositive(entryCostBasis, "entryCostBasis");
        requireNonNegative(entryFeeAmount, "entryFeeAmount");
        requireNonNegative(entrySlippageAmount, "entrySlippageAmount");

        UUID lotId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO paper_trade_lot (
                    id, entry_order_id, entry_fill_id, instrument_id, exchange, symbol, product_type,
                    entry_quantity, remaining_quantity, entry_price, entry_cost_basis,
                    entry_fee_amount, entry_slippage_amount, opened_at)
                VALUES (
                    :id, :entryOrderId, :entryFillId, :instrumentId, :exchange, :symbol, :productType,
                    :quantity, :quantity, :entryPrice, :entryCostBasis,
                    :entryFeeAmount, :entrySlippageAmount, :openedAt)
                """)
            .param("id", lotId)
            .param("entryOrderId", entryOrderId)
            .param("entryFillId", entryFillId)
            .param("instrumentId", instrumentId, Types.BIGINT)
            .param("exchange", exchange, Types.VARCHAR)
            .param("symbol", symbol)
            .param("productType", productType.name())
            .param("quantity", quantity)
            .param("entryPrice", price(entryPrice))
            .param("entryCostBasis", money(entryCostBasis))
            .param("entryFeeAmount", money(entryFeeAmount))
            .param("entrySlippageAmount", money(entrySlippageAmount))
            .param("openedAt", ts(openedAt))
            .update();
        return lotId;
    }

    /**
     * Allocates one authoritative SELL fill across open entry lots in FIFO order.
     * Monetary fill totals are apportioned by quantity and the final allocation receives the exact
     * residual, ensuring sums reconcile to the authoritative fill after currency rounding.
     */
    public List<CloseAllocation> allocateClose(
            UUID exitOrderId,
            UUID exitFillId,
            Long instrumentId,
            String symbol,
            ProductType productType,
            int quantity,
            BigDecimal exitPrice,
            BigDecimal totalFeeAmount,
            BigDecimal totalSlippageAmount,
            BigDecimal totalRealizedPnl,
            String exitReason,
            Instant closedAt) {
        requireIdentity(exitOrderId, exitFillId, symbol, productType, quantity, closedAt);
        requirePositive(exitPrice, "exitPrice");
        requireNonNegative(totalFeeAmount, "totalFeeAmount");
        requireNonNegative(totalSlippageAmount, "totalSlippageAmount");
        if (totalRealizedPnl == null) throw new IllegalArgumentException("totalRealizedPnl is required");

        List<OpenLot> openLots = lockOpenLots(instrumentId, symbol, productType);
        int available = openLots.stream().mapToInt(OpenLot::remainingQuantity).sum();
        if (available < quantity) {
            throw new IllegalStateException(
                "PAPER lot lineage has insufficient open quantity for exit: requested=" + quantity
                    + ", available=" + available);
        }

        List<PlannedAllocation> plan = new ArrayList<>();
        int left = quantity;
        for (OpenLot lot : openLots) {
            if (left == 0) break;
            int allocated = Math.min(left, lot.remainingQuantity());
            plan.add(new PlannedAllocation(lot, allocated));
            left -= allocated;
        }

        BigDecimal feeTotal = money(totalFeeAmount);
        BigDecimal slippageTotal = money(totalSlippageAmount);
        BigDecimal pnlTotal = money(totalRealizedPnl);
        BigDecimal feeAllocated = BigDecimal.ZERO.setScale(2);
        BigDecimal slippageAllocated = BigDecimal.ZERO.setScale(2);
        BigDecimal pnlAllocated = BigDecimal.ZERO.setScale(2);
        List<CloseAllocation> result = new ArrayList<>();

        for (int index = 0; index < plan.size(); index++) {
            PlannedAllocation item = plan.get(index);
            boolean last = index == plan.size() - 1;
            BigDecimal allocatedFee = allocateMoney(feeTotal, item.quantity(), quantity, feeAllocated, last);
            BigDecimal allocatedSlippage = allocateMoney(
                slippageTotal, item.quantity(), quantity, slippageAllocated, last);
            BigDecimal allocatedPnl = allocateMoney(pnlTotal, item.quantity(), quantity, pnlAllocated, last);
            feeAllocated = feeAllocated.add(allocatedFee);
            slippageAllocated = slippageAllocated.add(allocatedSlippage);
            pnlAllocated = pnlAllocated.add(allocatedPnl);

            UUID allocationId = UUID.randomUUID();
            int sequence = index + 1;
            jdbc.sql("""
                    INSERT INTO paper_trade_lot_close (
                        id, lot_id, exit_order_id, exit_fill_id, allocation_sequence,
                        allocated_quantity, exit_price, allocated_fee_amount,
                        allocated_slippage_amount, allocated_realized_pnl, exit_reason, closed_at)
                    VALUES (
                        :id, :lotId, :exitOrderId, :exitFillId, :sequence,
                        :quantity, :exitPrice, :fee, :slippage, :pnl, :reason, :closedAt)
                    """)
                .param("id", allocationId)
                .param("lotId", item.lot().id())
                .param("exitOrderId", exitOrderId)
                .param("exitFillId", exitFillId)
                .param("sequence", sequence)
                .param("quantity", item.quantity())
                .param("exitPrice", price(exitPrice))
                .param("fee", allocatedFee)
                .param("slippage", allocatedSlippage)
                .param("pnl", allocatedPnl)
                .param("reason", exitReason, Types.VARCHAR)
                .param("closedAt", ts(closedAt))
                .update();

            int remaining = item.lot().remainingQuantity() - item.quantity();
            int updated = jdbc.sql("""
                    UPDATE paper_trade_lot
                       SET remaining_quantity = :remaining
                     WHERE id = :id AND remaining_quantity = :expected
                    """)
                .param("remaining", remaining)
                .param("id", item.lot().id())
                .param("expected", item.lot().remainingQuantity())
                .update();
            if (updated != 1) {
                throw new IllegalStateException("Concurrent PAPER lot allocation detected");
            }
            result.add(new CloseAllocation(
                allocationId, item.lot().id(), exitOrderId, exitFillId, sequence, item.quantity(),
                allocatedFee, allocatedSlippage, allocatedPnl, remaining));
        }

        return List.copyOf(result);
    }

    private List<OpenLot> lockOpenLots(Long instrumentId, String symbol, ProductType productType) {
        String identity = instrumentId == null
            ? "instrument_id IS NULL AND upper(symbol) = upper(:symbol)"
            : "instrument_id = :instrumentId";
        var query = jdbc.sql("""
                SELECT id, entry_order_id, entry_fill_id, remaining_quantity, opened_at
                  FROM paper_trade_lot
                 WHERE remaining_quantity > 0
                   AND product_type = :productType
                   AND %s
                 ORDER BY opened_at, id
                 FOR UPDATE
                """.formatted(identity))
            .param("productType", productType.name());
        if (instrumentId == null) {
            query = query.param("symbol", symbol);
        } else {
            query = query.param("instrumentId", instrumentId);
        }
        return query.query((rs, row) -> new OpenLot(
                rs.getObject("id", UUID.class),
                rs.getObject("entry_order_id", UUID.class),
                rs.getObject("entry_fill_id", UUID.class),
                rs.getInt("remaining_quantity"),
                rs.getObject("opened_at", OffsetDateTime.class).toInstant()))
            .list();
    }

    private static BigDecimal allocateMoney(BigDecimal total, int quantity, int totalQuantity,
                                            BigDecimal alreadyAllocated, boolean last) {
        if (last) return money(total.subtract(alreadyAllocated));
        return money(total.multiply(BigDecimal.valueOf(quantity))
            .divide(BigDecimal.valueOf(totalQuantity), 12, RoundingMode.HALF_UP));
    }

    private static void requireIdentity(UUID orderId, UUID fillId, String symbol,
                                        ProductType productType, int quantity, Instant at) {
        if (orderId == null) throw new IllegalArgumentException("orderId is required");
        if (fillId == null) throw new IllegalArgumentException("fillId is required");
        if (symbol == null || symbol.isBlank()) throw new IllegalArgumentException("symbol is required");
        if (productType == null) throw new IllegalArgumentException("productType is required");
        if (quantity <= 0) throw new IllegalArgumentException("quantity must be positive");
        if (at == null) throw new IllegalArgumentException("timestamp is required");
    }

    private static void requirePositive(BigDecimal value, String name) {
        if (value == null || value.signum() <= 0) throw new IllegalArgumentException(name + " must be positive");
    }

    private static void requireNonNegative(BigDecimal value, String name) {
        if (value == null || value.signum() < 0) throw new IllegalArgumentException(name + " must be non-negative");
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal price(BigDecimal value) {
        return value.setScale(6, RoundingMode.HALF_UP);
    }

    private static OffsetDateTime ts(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private record OpenLot(UUID id, UUID entryOrderId, UUID entryFillId,
                           int remainingQuantity, Instant openedAt) {}

    private record PlannedAllocation(OpenLot lot, int quantity) {}

    public record CloseAllocation(
        UUID id,
        UUID lotId,
        UUID exitOrderId,
        UUID exitFillId,
        int allocationSequence,
        int allocatedQuantity,
        BigDecimal allocatedFeeAmount,
        BigDecimal allocatedSlippageAmount,
        BigDecimal allocatedRealizedPnl,
        int remainingLotQuantity
    ) {}
}
