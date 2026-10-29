package in.aitradingsystem.execution;

import in.aitradingsystem.risk.RiskDecisionClass;
import java.math.BigDecimal;
import java.time.LocalTime;

public record ApprovalPolicyInput(
    RiskDecisionClass riskDecisionClass,
    String symbol,
    BigDecimal orderNotional,
    int dailyTradesAfter,
    BigDecimal grossExposureAfter,
    BigDecimal dailyLoss,
    LocalTime marketTime
) {
    public ApprovalPolicyInput {
        if (riskDecisionClass == null) throw new IllegalArgumentException("riskDecisionClass is required");
        if (symbol == null || symbol.isBlank()) throw new IllegalArgumentException("symbol is required");
        if (orderNotional == null || orderNotional.signum() < 0) throw new IllegalArgumentException("orderNotional must be non-negative");
        if (dailyTradesAfter < 0) throw new IllegalArgumentException("dailyTradesAfter must be non-negative");
        if (grossExposureAfter == null || grossExposureAfter.signum() < 0) throw new IllegalArgumentException("grossExposureAfter must be non-negative");
        if (dailyLoss == null || dailyLoss.signum() < 0) throw new IllegalArgumentException("dailyLoss must be non-negative");
        if (marketTime == null) throw new IllegalArgumentException("marketTime is required");
        symbol = symbol.trim().toUpperCase();
    }
}
