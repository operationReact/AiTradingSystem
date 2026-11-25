package in.aitradingsystem.risk;

import java.math.BigDecimal;
import java.util.Map;

/** Advisory only. Actual order placement always re-evaluates fresh risk state. */
public record PositionSizeSuggestion(
        int quantity,
        BigDecimal riskPerShare,
        BigDecimal riskBudgetAmount,
        BigDecimal estimatedRisk,
        BigDecimal estimatedNotional,
        BigDecimal estimatedFee,
        RiskRuleCode limitingRule,
        Map<RiskRuleCode, Integer> quantityCaps,
        int policyVersion) {

    public PositionSizeSuggestion {
        if (quantity < 0) throw new IllegalArgumentException("quantity cannot be negative");
        quantityCaps = quantityCaps == null ? Map.of() : Map.copyOf(quantityCaps);
        if (policyVersion < 1) throw new IllegalArgumentException("policyVersion must be positive");
    }
}
