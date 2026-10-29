package in.aitradingsystem.paper;

import in.aitradingsystem.domain.ProductType;
import in.aitradingsystem.paper.PaperPositionRepository.PositionRow;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Deterministic long-only PAPER position accounting, separated by product type. */
@Service
public class PositionAccountingService {
    private final PaperPositionRepository positions;

    public PositionAccountingService(PaperPositionRepository positions) {
        this.positions = positions;
    }

    @Transactional
    public AccountingResult applyBuy(Long instrumentId, String exchange, String symbol, ProductType productType,
                                     int quantity, BigDecimal fillCostBasis, Instant now) {
        requireCommon(symbol, productType, quantity, now);
        if (fillCostBasis == null || fillCostBasis.signum() <= 0) {
            throw new IllegalArgumentException("fillCostBasis must be positive");
        }
        BigDecimal appliedCostBasis = money(fillCostBasis);
        PositionRow existing = positions.lock(instrumentId, symbol, productType);
        if (existing == null) {
            BigDecimal average = average(appliedCostBasis, quantity);
            positions.insert(instrumentId, exchange, symbol, productType, quantity, appliedCostBasis, average, now);
            return new AccountingResult(quantity, appliedCostBasis, average, BigDecimal.ZERO.setScale(2),
                appliedCostBasis, BigDecimal.ZERO.setScale(2));
        }

        int newQuantity = Math.addExact(existing.quantity(), quantity);
        BigDecimal newCostBasis = money(existing.costBasis().add(appliedCostBasis));
        BigDecimal newAverage = average(newCostBasis, newQuantity);
        if (!positions.update(existing, instrumentId, exchange, newQuantity, newCostBasis, newAverage,
                existing.realizedPnl(), now)) {
            throw new IllegalStateException("Concurrent PAPER position update detected");
        }
        return new AccountingResult(newQuantity, newCostBasis, newAverage, existing.realizedPnl(),
            appliedCostBasis, BigDecimal.ZERO.setScale(2));
    }

    @Transactional
    public AccountingResult applySell(Long instrumentId, String symbol, ProductType productType,
                                      int quantity, BigDecimal netProceeds, Instant now) {
        requireCommon(symbol, productType, quantity, now);
        if (netProceeds == null || netProceeds.signum() < 0) {
            throw new IllegalArgumentException("netProceeds must be non-negative");
        }
        PositionRow existing = positions.lock(instrumentId, symbol, productType);
        if (existing == null || quantity > existing.quantity()) {
            throw new IllegalStateException("Short PAPER positions are not allowed");
        }

        BigDecimal soldCostBasis = existing.quantity() == quantity
            ? money(existing.costBasis())
            : money(existing.costBasis().multiply(BigDecimal.valueOf(quantity))
                .divide(BigDecimal.valueOf(existing.quantity()), 8, RoundingMode.HALF_UP));
        BigDecimal realizedDelta = money(netProceeds.subtract(soldCostBasis));
        BigDecimal cumulativeRealized = money(existing.realizedPnl().add(realizedDelta));
        int remaining = existing.quantity() - quantity;

        if (remaining == 0) {
            if (!positions.delete(existing)) {
                throw new IllegalStateException("Concurrent PAPER position update detected");
            }
            return new AccountingResult(0, BigDecimal.ZERO.setScale(2), null, cumulativeRealized,
                soldCostBasis, realizedDelta);
        }

        BigDecimal remainingCost = money(existing.costBasis().subtract(soldCostBasis));
        BigDecimal remainingAverage = average(remainingCost, remaining);
        if (!positions.update(existing, instrumentId, existing.exchange(), remaining, remainingCost,
                remainingAverage, cumulativeRealized, now)) {
            throw new IllegalStateException("Concurrent PAPER position update detected");
        }
        return new AccountingResult(remaining, remainingCost, remainingAverage, cumulativeRealized,
            soldCostBasis, realizedDelta);
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

    public record AccountingResult(int quantity, BigDecimal costBasis, BigDecimal averagePrice,
                                   BigDecimal realizedPnl, BigDecimal appliedCostBasis,
                                   BigDecimal realizedDelta) {}
}
