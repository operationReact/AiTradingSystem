package in.aitradingsystem.paper;

import in.aitradingsystem.domain.ProductType;
import in.aitradingsystem.domain.Side;
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

    /** Compatibility overload used by accounting-only callers that do not carry risk levels. */
    @Transactional
    public AccountingResult applyBuy(Long instrumentId, String exchange, String symbol, ProductType productType,
                                     int quantity, BigDecimal fillCostBasis, Instant now) {
        return applyBuy(instrumentId, exchange, symbol, productType, quantity, fillCostBasis, null, null, now);
    }

    /** Compatibility overload: fixed stop/target with trailing protection disabled. */
    @Transactional
    public AccountingResult applyBuy(Long instrumentId, String exchange, String symbol, ProductType productType,
                                     int quantity, BigDecimal fillCostBasis, BigDecimal stopLoss,
                                     BigDecimal targetPrice, Instant now) {
        return applyBuy(instrumentId, exchange, symbol, productType, quantity, fillCostBasis, stopLoss, targetPrice,
            null, null, now);
    }

    @Transactional
    public AccountingResult applyBuy(Long instrumentId, String exchange, String symbol, ProductType productType,
                                     int quantity, BigDecimal fillCostBasis, BigDecimal stopLoss,
                                     BigDecimal targetPrice, BigDecimal fillPrice, BigDecimal trailingStopPercent,
                                     Instant now) {
        requireCommon(symbol, productType, quantity, now);
        if (fillCostBasis == null || fillCostBasis.signum() <= 0) {
            throw new IllegalArgumentException("fillCostBasis must be positive");
        }
        BigDecimal trailing = TrailingProtectionPolicy.validate(Side.BUY, trailingStopPercent);
        if (trailing != null && (fillPrice == null || fillPrice.signum() <= 0)) {
            throw new IllegalArgumentException("fillPrice must be positive when trailing protection is enabled");
        }

        BigDecimal appliedCostBasis = money(fillCostBasis);
        PositionRow existing = positions.lock(instrumentId, symbol, productType);
        if (existing == null) {
            BigDecimal average = average(appliedCostBasis, quantity);
            BigDecimal highWater = trailing == null ? null : price(fillPrice);
            BigDecimal effectiveStop = trailing == null
                ? null : TrailingProtectionPolicy.effectiveStop(stopLoss, highWater, trailing);
            positions.insert(instrumentId, exchange, symbol, productType, quantity, appliedCostBasis, average,
                stopLoss, targetPrice, trailing, highWater, trailing == null ? null : now, effectiveStop, now);
            return new AccountingResult(quantity, appliedCostBasis, average, BigDecimal.ZERO.setScale(2),
                appliedCostBasis, BigDecimal.ZERO.setScale(2));
        }

        if (!sameTrailing(existing.trailingStopPercent(), trailing)) {
            throw new IllegalArgumentException(
                "Open PAPER position already has a different trailing protection setting; close it before changing protection");
        }

        int newQuantity = Math.addExact(existing.quantity(), quantity);
        BigDecimal newCostBasis = money(existing.costBasis().add(appliedCostBasis));
        BigDecimal newAverage = average(newCostBasis, newQuantity);
        BigDecimal highWater = existing.highWaterMark();
        Instant highWaterAt = existing.highWaterAt();
        BigDecimal effectiveStop = existing.effectiveProtectiveStop();
        if (trailing != null) {
            BigDecimal candidateHigh = price(fillPrice);
            if (highWater == null || candidateHigh.compareTo(highWater) > 0) {
                highWater = candidateHigh;
                highWaterAt = now;
            }
            BigDecimal candidateStop = TrailingProtectionPolicy.effectiveStop(existing.stopLoss(), highWater, trailing);
            effectiveStop = effectiveStop == null ? candidateStop : effectiveStop.max(candidateStop);
        }
        if (!positions.update(existing, instrumentId, exchange, newQuantity, newCostBasis, newAverage,
                existing.realizedPnl(), trailing, highWater, highWaterAt, effectiveStop, now)) {
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

    private static boolean sameTrailing(BigDecimal existing, BigDecimal requested) {
        if (existing == null || requested == null) return existing == null && requested == null;
        return existing.compareTo(requested) == 0;
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

    private static BigDecimal price(BigDecimal value) {
        return value.setScale(6, RoundingMode.HALF_UP);
    }

    public record AccountingResult(int quantity, BigDecimal costBasis, BigDecimal averagePrice,
                                   BigDecimal realizedPnl, BigDecimal appliedCostBasis,
                                   BigDecimal realizedDelta) {}
}
