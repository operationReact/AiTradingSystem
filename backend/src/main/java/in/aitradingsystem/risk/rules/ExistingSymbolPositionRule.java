package in.aitradingsystem.risk.rules;

import in.aitradingsystem.domain.Side;
import in.aitradingsystem.risk.RiskContext;
import in.aitradingsystem.risk.RiskRule;
import in.aitradingsystem.risk.RiskRuleCode;
import in.aitradingsystem.risk.RiskRuleResult;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

@Component
public final class ExistingSymbolPositionRule implements RiskRule {
    @Override
    public RiskRuleCode code() {
        return RiskRuleCode.EXISTING_SYMBOL_POSITION;
    }

    @Override
    public int sequence() {
        return 130;
    }

    @Override
    public RiskRuleResult evaluate(RiskContext context) {
        if (context.side() != Side.BUY || !context.policy().rejectExistingSymbolPosition()) {
            return RiskRuleSupport.pass(code());
        }
        BigDecimal observed = BigDecimal.valueOf(context.heldQuantity());
        BigDecimal limit = BigDecimal.ZERO;
        return context.heldQuantity() > 0
                ? RiskRuleSupport.reject(code(), "SYMBOL_ALREADY_HELD", observed, limit, "quantity")
                : RiskRuleSupport.pass(code(), observed, limit, "quantity");
    }
}
