package in.aitradingsystem.risk;

import java.math.BigDecimal;
import java.util.List;

public record RiskDecisionDetail(
        RiskDecisionSummary summary,
        BigDecimal estimatedNotional,
        RiskPolicySnapshot policySnapshot,
        List<RiskRuleResult> ruleResults) {
    public RiskDecisionDetail {
        if (summary == null) throw new IllegalArgumentException("summary is required");
        ruleResults = ruleResults == null ? List.of() : List.copyOf(ruleResults);
    }
}
