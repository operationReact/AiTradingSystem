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
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    private final PaperPositionRepository positions;

    public PositionAccountingService(PaperPositionRepository positions) {
        this.positions = positions;
    }

    /** Compatibility overload used by accounting-only callers that do not carry risk levels. */
    @Transactional
    public AccountingResult applyBuy(Long instrumentId, String exchange, String symbol, ProductType productType,
                                     int quantity, BigDecimal fillCostBasis, Instant now) {
        return applyBuy(instrumentId, exchange, symbol, productType, quantity, fillCostBasis,
            null, null, null, null, now);
    }

    /** Compatibility overload for fixed stop/target callers. */
    @Transactional
    public AccountingResult applyBuy(Long instrumentId, String exchange, String symbol, ProductType productType,
                                     int quantity, BigDecimal fillCostBasis, BigDecimal stopLoss,
                                     BigDecimal targetPrice, Instant now) {
        return applyBuy(instrumentId, exchange, symbol, productType, quantity, fillCostBasis,
            stopLoss, targetPrice, null, null, now);
    }

    @Transactional
    public AccountingResult applyBuy(Long instrumentId, String exchange, String symbol, ProductType productType,
                                     int quantity, BigDecimal fillCostBasis, BigDecimal stopLoss,
                                     BigDecimal targetPrice, BigDecimal trailingPercent,
                                     BigDecimal fillPrice, Instant now) {
        requireCommon(symbol, productType, quantity, now);
        if (fillCostBasis == null || fillCostBasis.signum() <= 0) {
            throw new IllegalArgumentException("fillCostBasis must be positive");
        }
        if (trailingPercent != null) {
            validateTrailing(trailingPercent, stopLoss, fillPrice);
        }

        BigDecimal appliedCostBasis = money(fillCostBasis);
        PositionRow existing = positions.lock(instrumentId, symbol, productType);
        if (existing == null) {
            BigDecimal average = average(appliedCostBasis, quantity);
            BigDecimal highWaterMark = trailingPercent == null ? null : price(fillPrice);
            BigDecimal effectiveStop = trailingPercent == null
                ? null : effectiveStop(stopLoss, highWaterMark, trailingPercent);
            positions.insert(instrumentId, exchange, symbol, productType, quantity, appliedCostBasis, average,
                stopLoss, targetPrice, trailingPercent, highWaterMark, trailingPercent == null ? null : now,
                effectiveStop, now);
            return new AccountingResult(quantity, appliedCostBasis, average, BigDecimal.ZERO.setScale(2),
                appliedCostBasis, BigDecimal.ZERO.setScale(2));
        }

        validateExistingTrailingPolicy(existing, trailingPercent);

        int newQuantity = Math.addExact(existing.quantity(), quantity);
        BigDecimal newCostBasis = money(existing.costBasis().add(appliedCostBasis));
        BigDecimal newAverage = average(newCostBasis, newQuantity);
        if (!positions.update(existing, instrumentId, exchange, newQuantity, newCostBasis, newAverage,
                existing.realizedPnl(), now)) {
            throw new IllegalStateException("Concurrent PAPER position update detected");
        }

        // If the position already has trailing protection, this authoritative fill may itself set a new high.
        if (existing.trailingPercent() != null && fillPrice != null
                && fillPrice.compareTo(existing.highWaterMark()) > 0) {
            BigDecimal highWater = price(fillPrice);
            BigDecimal stop = effectiveStop(existing.stopLoss(), highWater, existing.trailingPercent());
            if (!positions.advanceTrailingState(existing.symbol(), existing.productType(), existing.trailingVersion(),
                    highWater, now, stop)) {
                throw new IllegalStateException("Concurrent PAPER trailing-state update detected");
            }
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

    static BigDecimal trailingThreshold(BigDecimal highWaterMark, BigDecimal trailingPercent) {
        BigDecimal remaining = BigDecimal.ONE.subtract(
            trailingPercent.divide(ONE_HUNDRED, 12, RoundingMode.HALF_UP));
        return price(highWaterMark.multiply(remaining));
    }

    static BigDecimal effectiveStop(BigDecimal initialStopLoss, BigDecimal highWaterMark, BigDecimal trailingPercent) {
        BigDecimal threshold = trailingThreshold(highWaterMark, trailingPercent);
        if (initialStopLoss == null) return threshold;
        return price(initialStopLoss.max(threshold));
    }

    private static void validateExistingTrailingPolicy(PositionRow existing, BigDecimal requestedTrailingPercent) {
        if (requestedTrailingPercent == null) return; // Existing policy, if any, remains authoritative.
        if (existing.trailingPercent() == null) {
            throw new IllegalStateException(
                "Cannot enable trailing protection after a PAPER position is already open; close and reopen the position");
        }
        if (existing.trailingPercent().compareTo(requestedTrailingPercent) != 0) {
            throw new IllegalStateException("Cannot change trailing protection while a PAPER position is open");
        }
    }

    private static void validateTrailing(BigDecimal trailingPercent, BigDecimal stopLoss, BigDecimal fillPrice) {
        if (trailingPercent.signum() <= 0 || trailingPercent.compareTo(ONE_HUNDRED) >= 0) {
            throw new IllegalArgumentException("trailingPercent must be greater than 0 and less than 100");
        }
        if (stopLoss == null) {
            throw new IllegalArgumentException("Trailing PAPER positions require an initial stop loss");
        }
        if (fillPrice == null || fillPrice.signum() <= 0) {
            throw new IllegalArgumentException("Trailing PAPER positions require an authoritative positive fill price");
        }
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
