package in.aitradingsystem.risk.rules;

import in.aitradingsystem.risk.RiskContext;
import in.aitradingsystem.risk.RiskRule;
import in.aitradingsystem.risk.RiskRuleCode;
import in.aitradingsystem.risk.RiskRuleResult;
import org.springframework.stereotype.Component;

@Component
public final class DuplicatePendingExposureRule implements RiskRule {
    @Override public RiskRuleCode code() { return RiskRuleCode.DUPLICATE_PENDING_EXPOSURE; }
    @Override public int sequence() { return 180; }

    @Override
    public RiskRuleResult evaluate(RiskContext context) {
        if (!context.policy().rejectDuplicatePendingExposure()) return RiskRuleSupport.pass(code());
        return context.duplicatePendingExposure()
                ? RiskRuleResult.reject(code(), "DUPLICATE_PENDING_EXPOSURE")
                : RiskRuleSupport.pass(code());
    }
}
