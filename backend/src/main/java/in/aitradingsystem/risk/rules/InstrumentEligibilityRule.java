package in.aitradingsystem.risk.rules;

import in.aitradingsystem.risk.RiskContext;
import in.aitradingsystem.risk.RiskRule;
import in.aitradingsystem.risk.RiskRuleCode;
import in.aitradingsystem.risk.RiskRuleResult;
import org.springframework.stereotype.Component;

@Component
public final class InstrumentEligibilityRule implements RiskRule {
    @Override public RiskRuleCode code() { return RiskRuleCode.INSTRUMENT_ELIGIBILITY; }
    @Override public int sequence() { return 20; }

    @Override
    public RiskRuleResult evaluate(RiskContext context) {
        return context.instrumentEligible()
                ? RiskRuleSupport.pass(code())
                : RiskRuleResult.reject(code(), "INSTRUMENT_NOT_ELIGIBLE");
    }
}
