package in.aitradingsystem.risk.rules;

import in.aitradingsystem.paper.PaperExecutionModel;
import in.aitradingsystem.risk.RiskContext;
import in.aitradingsystem.risk.RiskOutcome;
import in.aitradingsystem.risk.RiskRuleCode;
import in.aitradingsystem.risk.RiskRuleResult;
import java.math.BigDecimal;
import java.util.Map;

final class RiskRuleSupport {
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private RiskRuleSupport() {}

    static BigDecimal fillPrice(RiskContext context) {
        return PaperExecutionModel.fillPrice(
                context.side(), context.referencePrice(), context.policy().slippageRate());
    }

    static BigDecimal gross(RiskContext context) {
        return PaperExecutionModel.money(fillPrice(context).multiply(BigDecimal.valueOf(context.quantity())));
    }

    static BigDecimal fee(RiskContext context) {
        return PaperExecutionModel.fee(gross(context), context.policy().feeRate());
    }

    static BigDecimal riskBudget(RiskContext context) {
        return PaperExecutionModel.money(context.startingCapital()
                .multiply(context.policy().riskPerTradePercent()).divide(HUNDRED));
    }

    static BigDecimal estimatedBuyRisk(RiskContext context) {
        BigDecimal fill = fillPrice(context);
        BigDecimal priceRisk = fill.subtract(context.stopLoss()).multiply(BigDecimal.valueOf(context.quantity()));
        return PaperExecutionModel.money(priceRisk.add(fee(context)));
    }

    static RiskRuleResult pass(RiskRuleCode code) {
        return RiskRuleResult.pass(code);
    }

    static RiskRuleResult pass(RiskRuleCode code, BigDecimal observed, BigDecimal limit, String unit) {
        return new RiskRuleResult(code, RiskOutcome.PASS, code.name() + "_PASS", observed, limit, unit, Map.of());
    }

    static RiskRuleResult reject(
            RiskRuleCode code, String reasonCode, BigDecimal observed, BigDecimal limit, String unit) {
        return new RiskRuleResult(code, RiskOutcome.HARD_REJECT, reasonCode, observed, limit, unit, Map.of());
    }
}
