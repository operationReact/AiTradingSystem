package in.aitradingsystem.trading;

import java.math.BigDecimal;

public record RiskEvaluationRequest(
    TradingMode tradingMode,
    ExecutionMode executionMode,
    int quantity,
    BigDecimal entryPrice,
    BigDecimal stopLoss,
    BigDecimal target,
    BigDecimal availableCapital,
    BigDecimal maxRiskAmount,
    boolean emergencyStopActive
) {}
