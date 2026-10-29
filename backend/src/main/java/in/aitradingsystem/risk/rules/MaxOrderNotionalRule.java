package in.aitradingsystem.risk.rules;

import in.aitradingsystem.risk.RiskContext;
import in.aitradingsystem.risk.RiskRule;
import in.aitradingsystem.risk.RiskRuleCode;
import in.aitradingsystem.risk.RiskRuleResult;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

@Component
public final class MaxOrderNotionalRule implements RiskRule {
    @Override public RiskRuleCode code() { return RiskRuleCode.MAX_ORDER_NOTIONAL; }
    @Override public int sequence() { return 110; }

    @Override
    public RiskRuleResult evaluate(RiskContext context) {
        BigDecimal observed = RiskRuleSupport.gross(context);
        BigDecimal limit = context.policy().maxOrderNotional();
        return observed.compareTo(limit) > 0
                ? RiskRuleSupport.reject(code(), "MAX_ORDER_NOTIONAL_EXCEEDED", observed, limit, "currency")
                : RiskRuleSupport.pass(code(), observed, limit, "currency");
    }
}
