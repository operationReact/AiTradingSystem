package in.aitradingsystem.risk.rules;

import in.aitradingsystem.domain.Side;
import in.aitradingsystem.risk.RiskContext;
import in.aitradingsystem.risk.RiskRule;
import in.aitradingsystem.risk.RiskRuleCode;
import in.aitradingsystem.risk.RiskRuleResult;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Component;

@Component
public final class MinimumRewardRiskRule implements RiskRule {
    @Override public RiskRuleCode code() { return RiskRuleCode.MIN_REWARD_RISK; }
    @Override public int sequence() { return 80; }

    @Override
    public RiskRuleResult evaluate(RiskContext context) {
        if (context.side() != Side.BUY || !validLevels(context)) return RiskRuleSupport.pass(code());
        BigDecimal fill = RiskRuleSupport.fillPrice(context);
        BigDecimal riskPerShare = fill.subtract(context.stopLoss());
        if (riskPerShare.signum() <= 0) {
            return RiskRuleSupport.reject(code(), "MIN_REWARD_RISK_NOT_MET", BigDecimal.ZERO,
                    context.policy().minRewardRiskRatio(), "ratio");
        }
        BigDecimal rewardPerShare = context.targetPrice().subtract(fill);
        if (rewardPerShare.signum() <= 0) {
            return RiskRuleSupport.reject(code(), "MIN_REWARD_RISK_NOT_MET", BigDecimal.ZERO,
                    context.policy().minRewardRiskRatio(), "ratio");
        }
        BigDecimal ratio = rewardPerShare.divide(riskPerShare, 12, RoundingMode.HALF_UP);
        BigDecimal observed = ratio.setScale(8, RoundingMode.HALF_UP);
        BigDecimal limit = context.policy().minRewardRiskRatio();
        return ratio.compareTo(limit) < 0
                ? RiskRuleSupport.reject(code(), "MIN_REWARD_RISK_NOT_MET", observed, limit, "ratio")
                : RiskRuleSupport.pass(code(), observed, limit, "ratio");
    }

    private static boolean validLevels(RiskContext context) {
        return context.stopLoss() != null && context.targetPrice() != null
                && context.stopLoss().compareTo(context.referencePrice()) < 0
                && context.referencePrice().compareTo(context.targetPrice()) < 0;
    }
}
