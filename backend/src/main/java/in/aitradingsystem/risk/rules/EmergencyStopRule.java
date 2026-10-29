package in.aitradingsystem.risk.rules;

import in.aitradingsystem.risk.RiskContext;
import in.aitradingsystem.risk.RiskRule;
import in.aitradingsystem.risk.RiskRuleCode;
import in.aitradingsystem.risk.RiskRuleResult;
import org.springframework.stereotype.Component;

@Component
public final class EmergencyStopRule implements RiskRule {
    @Override
    public RiskRuleCode code() {
        return RiskRuleCode.EMERGENCY_STOP;
    }

    @Override
    public int sequence() {
        return 10;
    }

    @Override
    public RiskRuleResult evaluate(RiskContext context) {
        return context.emergencyStopActive()
                ? RiskRuleResult.reject(code(), "EMERGENCY_STOP_ACTIVE")
                : RiskRuleSupport.pass(code());
    }
}
