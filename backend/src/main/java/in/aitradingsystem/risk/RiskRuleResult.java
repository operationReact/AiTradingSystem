package in.aitradingsystem.risk;

import java.math.BigDecimal;
import java.util.Map;

public record RiskRuleResult(
        RiskRuleCode code,
        RiskOutcome outcome,
        String reasonCode,
        BigDecimal observedValue,
        BigDecimal limitValue,
        String unit,
        Map<String, Object> details) {

    public RiskRuleResult {
        if (code == null) throw new IllegalArgumentException("code is required");
        if (outcome == null) throw new IllegalArgumentException("outcome is required");
        if (reasonCode == null || reasonCode.isBlank()) throw new IllegalArgumentException("reasonCode is required");
        details = details == null ? Map.of() : Map.copyOf(details);
    }

    public static RiskRuleResult pass(RiskRuleCode code) {
        return new RiskRuleResult(code, RiskOutcome.PASS, code.name() + "_PASS", null, null, null, Map.of());
    }

    public static RiskRuleResult reject(RiskRuleCode code, String reasonCode) {
        return new RiskRuleResult(code, RiskOutcome.HARD_REJECT, reasonCode, null, null, null, Map.of());
    }
}
