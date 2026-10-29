package in.aitradingsystem.risk.rules;

import in.aitradingsystem.domain.Side;
import in.aitradingsystem.risk.RiskContext;
import in.aitradingsystem.risk.RiskRule;
import in.aitradingsystem.risk.RiskRuleCode;
import in.aitradingsystem.risk.RiskRuleResult;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

@Component
public final class PerTradeRiskBudgetRule implements RiskRule {
    @Override
    public RiskRuleCode code() {
        return RiskRuleCode.PER_TRADE_RISK_BUDGET;
    }

    @Override
    public int sequence() {
        return 100;
    }

    @Override
    public RiskRuleResult evaluate(RiskContext context) {
        if (context.side() != Side.BUY || !legacyBuyLevelsValid(context)) {
            return RiskRuleSupport.pass(code());
        }
        BigDecimal observed = RiskRuleSupport.estimatedBuyRisk(context);
        BigDecimal limit = RiskRuleSupport.riskBudget(context);
        return observed.compareTo(limit) > 0
                ? RiskRuleSupport.reject(code(), "RISK_BUDGET_EXCEEDED", observed, limit, "currency")
                : RiskRuleSupport.pass(code(), observed, limit, "currency");
    }

    private static boolean legacyBuyLevelsValid(RiskContext context) {
        return context.stopLoss() != null && context.targetPrice() != null
                && context.stopLoss().compareTo(context.referencePrice()) < 0
                && context.referencePrice().compareTo(context.targetPrice()) < 0;
    }
}
