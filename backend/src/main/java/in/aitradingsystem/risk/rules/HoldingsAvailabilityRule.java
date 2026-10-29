package in.aitradingsystem.risk.rules;

import in.aitradingsystem.domain.Side;
import in.aitradingsystem.risk.RiskContext;
import in.aitradingsystem.risk.RiskRule;
import in.aitradingsystem.risk.RiskRuleCode;
import in.aitradingsystem.risk.RiskRuleResult;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

@Component
public final class HoldingsAvailabilityRule implements RiskRule {
    @Override
    public RiskRuleCode code() {
        return RiskRuleCode.HOLDINGS_AVAILABILITY;
    }

    @Override
    public int sequence() {
        return 120;
    }

    @Override
    public RiskRuleResult evaluate(RiskContext context) {
        if (context.side() != Side.SELL) return RiskRuleSupport.pass(code());
        BigDecimal requested = BigDecimal.valueOf(context.quantity());
        BigDecimal held = BigDecimal.valueOf(context.heldQuantity());
        return context.heldQuantity() < context.quantity()
                ? RiskRuleSupport.reject(code(), "INSUFFICIENT_HOLDINGS", requested, held, "quantity")
                : RiskRuleSupport.pass(code(), requested, held, "quantity");
    }
}
