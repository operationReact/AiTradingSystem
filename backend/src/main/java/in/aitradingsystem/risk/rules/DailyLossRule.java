package in.aitradingsystem.risk.rules;

import in.aitradingsystem.paper.PaperExecutionModel;
import in.aitradingsystem.risk.RiskContext;
import in.aitradingsystem.risk.RiskRule;
import in.aitradingsystem.risk.RiskRuleCode;
import in.aitradingsystem.risk.RiskRuleResult;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

@Component
public final class DailyLossRule implements RiskRule {
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    @Override
    public RiskRuleCode code() {
        return RiskRuleCode.DAILY_LOSS_LIMIT;
    }

    @Override
    public int sequence() {
        return 90;
    }

    @Override
    public RiskRuleResult evaluate(RiskContext context) {
        BigDecimal limit = PaperExecutionModel.money(context.startingCapital()
                .multiply(context.policy().maxDailyDrawdownPercent()).divide(HUNDRED));
        BigDecimal floor = limit.negate();
        return context.realizedPnlToday().compareTo(floor) <= 0
                ? RiskRuleSupport.reject(code(), "DAILY_LOSS_LIMIT_REACHED", context.realizedPnlToday(), floor, "currency")
                : RiskRuleSupport.pass(code(), context.realizedPnlToday(), floor, "currency");
    }
}
