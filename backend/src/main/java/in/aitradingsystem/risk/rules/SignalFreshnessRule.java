package in.aitradingsystem.risk.rules;

import in.aitradingsystem.risk.RiskContext;
import in.aitradingsystem.risk.RiskRule;
import in.aitradingsystem.risk.RiskRuleCode;
import in.aitradingsystem.risk.RiskRuleResult;
import java.math.BigDecimal;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
public final class SignalFreshnessRule implements RiskRule {
    @Override
    public RiskRuleCode code() {
        return RiskRuleCode.SIGNAL_FRESHNESS;
    }

    @Override
    public int sequence() {
        return 40;
    }

    @Override
    public RiskRuleResult evaluate(RiskContext context) {
        if (context.signalGeneratedAt() == null || context.signalGeneratedAt().isAfter(context.now())) {
            return RiskRuleSupport.pass(code());
        }
        long ageMinutes = Duration.between(context.signalGeneratedAt(), context.now()).toMinutes();
        BigDecimal observed = BigDecimal.valueOf(ageMinutes);
        BigDecimal limit = BigDecimal.valueOf(context.policy().signalMaxAgeMinutes());
        return ageMinutes > context.policy().signalMaxAgeMinutes()
                ? RiskRuleSupport.reject(code(), "STALE_SIGNAL", observed, limit, "minutes")
                : RiskRuleSupport.pass(code(), observed, limit, "minutes");
    }
}
