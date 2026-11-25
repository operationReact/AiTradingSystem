package in.aitradingsystem.risk.rules;

import in.aitradingsystem.domain.Side;
import in.aitradingsystem.risk.RiskContext;
import in.aitradingsystem.risk.RiskRule;
import in.aitradingsystem.risk.RiskRuleCode;
import in.aitradingsystem.risk.RiskRuleResult;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public final class PriceLevelsRule implements RiskRule {
    @Override
    public RiskRuleCode code() {
        return RiskRuleCode.PRICE_LEVELS;
    }

    @Override
    public int sequence() {
        return 70;
    }

    @Override
    public RiskRuleResult evaluate(RiskContext context) {
        if (context.side() != Side.BUY) return RiskRuleSupport.pass(code());
        boolean valid = context.stopLoss() != null && context.targetPrice() != null
                && context.stopLoss().compareTo(context.referencePrice()) < 0
                && context.referencePrice().compareTo(context.targetPrice()) < 0;
        if (valid) return RiskRuleSupport.pass(code());
        return new RiskRuleResult(
                code(),
                in.aitradingsystem.risk.RiskOutcome.HARD_REJECT,
                "INVALID_PRICE_LEVELS",
                context.referencePrice(),
                null,
                "price",
                Map.of(
                        "stopLoss", context.stopLoss() == null ? "null" : context.stopLoss().toPlainString(),
                        "targetPrice", context.targetPrice() == null ? "null" : context.targetPrice().toPlainString()));
    }
}
