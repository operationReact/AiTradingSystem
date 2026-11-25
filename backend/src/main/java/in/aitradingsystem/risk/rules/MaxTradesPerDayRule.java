package in.aitradingsystem.risk.rules;

import in.aitradingsystem.risk.RiskContext;
import in.aitradingsystem.risk.RiskRule;
import in.aitradingsystem.risk.RiskRuleCode;
import in.aitradingsystem.risk.RiskRuleResult;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

@Component
public final class MaxTradesPerDayRule implements RiskRule {
    @Override public RiskRuleCode code() { return RiskRuleCode.MAX_TRADES_PER_DAY; }
    @Override public int sequence() { return 170; }

    @Override
    public RiskRuleResult evaluate(RiskContext context) {
        BigDecimal observedAfter = BigDecimal.valueOf((long) context.tradesToday() + 1L);
        BigDecimal limit = BigDecimal.valueOf(context.policy().maxTradesPerDay());
        return observedAfter.compareTo(limit) > 0
                ? RiskRuleSupport.reject(code(), "MAX_TRADES_PER_DAY_EXCEEDED", observedAfter, limit, "count")
                : RiskRuleSupport.pass(code(), observedAfter, limit, "count");
    }
}
