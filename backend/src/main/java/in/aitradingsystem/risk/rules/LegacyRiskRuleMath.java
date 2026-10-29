package in.aitradingsystem.risk.rules;

import in.aitradingsystem.domain.Side;
import in.aitradingsystem.paper.PaperExecutionModel;
import in.aitradingsystem.risk.RiskContext;
import java.math.BigDecimal;

final class LegacyRiskRuleMath {
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private LegacyRiskRuleMath() {}

    static BigDecimal fillPrice(RiskContext context) {
        return PaperExecutionModel.fillPrice(context.side(), context.referencePrice(), context.policy().slippageRate());
    }

    static BigDecimal quantity(RiskContext context) {
        return BigDecimal.valueOf(context.quantity());
    }

    static BigDecimal gross(RiskContext context) {
        return PaperExecutionModel.money(fillPrice(context).multiply(quantity(context)));
    }

    static BigDecimal fee(RiskContext context) {
        return PaperExecutionModel.fee(gross(context), context.policy().feeRate());
    }

    static BigDecimal riskBudget(RiskContext context) {
        return PaperExecutionModel.money(context.startingCapital()
                .multiply(context.policy().riskPerTradePercent()).divide(HUNDRED));
    }

    static BigDecimal dailyLossLimit(RiskContext context) {
        return PaperExecutionModel.money(context.startingCapital()
                .multiply(context.policy().maxDailyDrawdownPercent()).divide(HUNDRED));
    }

    static BigDecimal estimatedBuyRisk(RiskContext context) {
        return PaperExecutionModel.money(fillPrice(context).subtract(context.stopLoss())
                .multiply(quantity(context)).add(fee(context)));
    }

    static boolean validLegacyBuyLevels(RiskContext context) {
        return context.side() != Side.BUY || context.stopLoss() != null && context.targetPrice() != null
                && context.stopLoss().compareTo(context.referencePrice()) < 0
                && context.referencePrice().compareTo(context.targetPrice()) < 0;
    }
}
