package in.aitradingsystem.risk;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RiskDecisionV2(
        UUID decisionId,
        RiskDecisionClass decisionClass,
        List<RiskRuleResult> ruleResults,
        BigDecimal effectiveFillPrice,
        BigDecimal grossNotional,
        BigDecimal estimatedFee,
        BigDecimal estimatedSlippage,
        BigDecimal estimatedRisk,
        BigDecimal riskBudget,
        BigDecimal rewardRiskRatio,
        BigDecimal grossExposureAfter,
        BigDecimal symbolExposureAfter,
        int tradesTodayAfter,
        UUID policyConfigId,
        int policyVersion,
        Instant evaluatedAt) {

    public RiskDecisionV2 {
        if (decisionId == null || decisionClass == null || evaluatedAt == null || policyConfigId == null) {
            throw new IllegalArgumentException("decision identity, class, policy and evaluation time are required");
        }
        if (policyVersion < 1) throw new IllegalArgumentException("policyVersion must be positive");
        ruleResults = ruleResults == null ? List.of() : List.copyOf(ruleResults);
    }

    public boolean approvedForRisk() {
        return decisionClass != RiskDecisionClass.REJECTED;
    }
}
