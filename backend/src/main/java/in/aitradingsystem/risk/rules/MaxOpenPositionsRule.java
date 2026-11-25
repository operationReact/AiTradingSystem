package in.aitradingsystem.risk.rules;

import in.aitradingsystem.domain.Side;
import in.aitradingsystem.risk.RiskContext;
import in.aitradingsystem.risk.RiskRule;
import in.aitradingsystem.risk.RiskRuleCode;
import in.aitradingsystem.risk.RiskRuleResult;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

@Component
public final class MaxOpenPositionsRule implements RiskRule {
    @Override
    public RiskRuleCode code() {
        return RiskRuleCode.MAX_OPEN_POSITIONS;
    }

    @Override
    public int sequence() {
        return 140;
    }

    @Override
    public RiskRuleResult evaluate(RiskContext context) {
        if (context.side() != Side.BUY) return RiskRuleSupport.pass(code());
        // Preserve V1's held-symbol else-if: it emitted SYMBOL_ALREADY_HELD instead of both reasons.
        if (context.policy().rejectExistingSymbolPosition() && context.heldQuantity() > 0) {
            return RiskRuleSupport.pass(code());
        }
        BigDecimal observed = BigDecimal.valueOf(context.openPositionCount());
        BigDecimal limit = BigDecimal.valueOf(context.policy().maxOpenPositions());
        return context.openPositionCount() >= context.policy().maxOpenPositions()
                ? RiskRuleSupport.reject(code(), "MAX_OPEN_POSITIONS", observed, limit, "count")
                : RiskRuleSupport.pass(code(), observed, limit, "count");
    }
}
