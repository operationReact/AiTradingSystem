package in.aitradingsystem.risk.rules;

import in.aitradingsystem.domain.Side;
import in.aitradingsystem.risk.RiskContext;
import in.aitradingsystem.risk.RiskRule;
import in.aitradingsystem.risk.RiskRuleCode;
import in.aitradingsystem.risk.RiskRuleResult;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

@Component
public final class CashAvailabilityRule implements RiskRule {
    @Override
    public RiskRuleCode code() {
        return RiskRuleCode.CASH_AVAILABILITY;
    }

    @Override
    public int sequence() {
        return 120;
    }

    @Override
    public RiskRuleResult evaluate(RiskContext context) {
        if (context.side() != Side.BUY) return RiskRuleSupport.pass(code());
        BigDecimal required = RiskRuleSupport.gross(context).add(RiskRuleSupport.fee(context));
        return required.compareTo(context.availableCash()) > 0
                ? RiskRuleSupport.reject(code(), "INSUFFICIENT_CASH", required, context.availableCash(), "currency")
                : RiskRuleSupport.pass(code(), required, context.availableCash(), "currency");
    }
}
