package in.aitradingsystem.risk.rules;

import in.aitradingsystem.risk.RiskContext;
import in.aitradingsystem.risk.RiskRule;
import in.aitradingsystem.risk.RiskRuleCode;
import in.aitradingsystem.risk.RiskRuleResult;
import org.springframework.stereotype.Component;

@Component
public final class SignalTimestampRule implements RiskRule {
    @Override
    public RiskRuleCode code() {
        return RiskRuleCode.SIGNAL_TIMESTAMP;
    }

    @Override
    public int sequence() {
        return 30;
    }

    @Override
    public RiskRuleResult evaluate(RiskContext context) {
        // V1 permitted a null signal timestamp. Preserve that compatibility until the production context switch.
        if (context.signalGeneratedAt() == null) return RiskRuleSupport.pass(code());
        return context.signalGeneratedAt().isAfter(context.now())
                ? RiskRuleResult.reject(code(), "SIGNAL_TIMESTAMP_INVALID")
                : RiskRuleSupport.pass(code());
    }
}
