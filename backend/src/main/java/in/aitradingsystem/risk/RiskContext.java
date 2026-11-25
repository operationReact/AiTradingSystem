package in.aitradingsystem.risk;

import in.aitradingsystem.domain.Side;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record RiskContext(
        UUID orderId,
        String correlationId,
        ExecutionMode executionMode,
        Side side,
        long instrumentId,
        String exchange,
        String tradingSymbol,
        String strategy,
        TradingStyle tradingStyle,
        int quantity,
        BigDecimal referencePrice,
        BigDecimal stopLoss,
        BigDecimal targetPrice,
        Instant signalGeneratedAt,
        Instant marketPriceAsOf,
        Instant now,
        BigDecimal startingCapital,
        BigDecimal availableCash,
        BigDecimal currentEquity,
        BigDecimal realizedPnlToday,
        int heldQuantity,
        BigDecimal heldMarketValue,
        int openPositionCount,
        BigDecimal currentGrossExposure,
        BigDecimal currentSymbolExposure,
        int tradesToday,
        boolean duplicatePendingExposure,
        boolean emergencyStopActive,
        MarketSessionState marketSessionState,
        boolean instrumentEligible,
        RiskPolicySnapshot policy) {

    public RiskContext {
        if (orderId == null) throw new IllegalArgumentException("orderId is required");
        if (correlationId == null || correlationId.isBlank()) throw new IllegalArgumentException("correlationId is required");
        if (executionMode == null || side == null || tradingStyle == null || marketSessionState == null || policy == null) {
            throw new IllegalArgumentException("execution, side, trading style, market session and policy are required");
        }
        if (instrumentId < 1) throw new IllegalArgumentException("instrumentId must be positive");
        if (exchange == null || exchange.isBlank() || tradingSymbol == null || tradingSymbol.isBlank()) {
            throw new IllegalArgumentException("instrument identity is required");
        }
        if (strategy == null || strategy.isBlank()) throw new IllegalArgumentException("strategy is required");
        if (quantity < 1) throw new IllegalArgumentException("quantity must be positive");
        if (referencePrice == null || referencePrice.signum() <= 0) throw new IllegalArgumentException("referencePrice must be positive");
        if (now == null) throw new IllegalArgumentException("now is required");
        if (startingCapital == null || availableCash == null || currentEquity == null || realizedPnlToday == null
                || heldMarketValue == null || currentGrossExposure == null || currentSymbolExposure == null) {
            throw new IllegalArgumentException("account and exposure snapshots are required");
        }
        if (heldQuantity < 0 || openPositionCount < 0 || tradesToday < 0) {
            throw new IllegalArgumentException("position and trade counters cannot be negative");
        }
    }
}
