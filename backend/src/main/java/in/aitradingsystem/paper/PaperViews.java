package in.aitradingsystem.paper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class PaperViews {
    private PaperViews() {}

    public record OrderView(
        UUID id,
        Long instrumentId,
        String exchange,
        String symbol,
        String side,
        int quantity,
        int submittedQuantity,
        int filledQuantity,
        String orderType,
        BigDecimal limitPrice,
        String productType,
        String strategy,
        Long candidateId,
        BigDecimal averageFillPrice,
        BigDecimal entryPrice,
        BigDecimal stopLoss,
        BigDecimal target,
        String status,
        String rejectionReason,
        Instant signalGeneratedAt,
        Instant createdAt,
        Instant updatedAt,
        String correlationId) {}

    public record DecisionView(UUID id, UUID orderId, String decision, List<String> reasonCodes, BigDecimal riskBudget,
        BigDecimal estimatedRisk, BigDecimal availableCash, int openPositions,
        BigDecimal realizedPnlToday, BigDecimal dailyLossLimit, String policyVersion, Instant evaluatedAt) {}

    public record FillView(UUID id, BigDecimal fillPrice, BigDecimal grossAmount, BigDecimal feeAmount,
        BigDecimal slippageAmount, BigDecimal netAmount, BigDecimal costBasis, BigDecimal realizedPnl,
        Instant filledAt) {}

    public record OrderResult(OrderView order, DecisionView decision, FillView fill, boolean replayed) {}

    public record PositionView(String symbol, int quantity, BigDecimal averageCost, BigDecimal costBasis,
        BigDecimal markPrice, BigDecimal marketValue, BigDecimal unrealizedPnl, BigDecimal stopLoss,
        BigDecimal target, Instant openedAt) {}

    public record PortfolioView(String mode, boolean liveTradingEnabled, boolean newOrdersHalted,
        BigDecimal startingCapital, BigDecimal cash, BigDecimal invested, BigDecimal marketValue,
        BigDecimal equity, BigDecimal realizedPnl, BigDecimal unrealizedPnl, BigDecimal realizedPnlToday,
        BigDecimal exposurePercent, int openPositions, boolean cashReconciled, String markPriceSource,
        List<PositionView> positions, Instant asOf) {}

    public record EquityPoint(String date, BigDecimal realizedPnl, BigDecimal cumulativeRealizedPnl,
        BigDecimal realizedEquity) {}
}
