package in.aitradingsystem.risk.rules;

import in.aitradingsystem.domain.Side;
import in.aitradingsystem.risk.RiskContext;
import in.aitradingsystem.risk.RiskRule;
import in.aitradingsystem.risk.RiskRuleCode;
import in.aitradingsystem.risk.RiskRuleResult;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

@Component
public final class GrossPortfolioExposureRule implements RiskRule {
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    @Override public RiskRuleCode code() { return RiskRuleCode.MAX_GROSS_EXPOSURE; }
    @Override public int sequence() { return 150; }

    @Override
    public RiskRuleResult evaluate(RiskContext context) {
        BigDecimal orderNotional = RiskRuleSupport.gross(context);
        BigDecimal limit = context.currentEquity()
                .multiply(context.policy().maxGrossExposurePercent()).divide(HUNDRED);
        BigDecimal projected = context.side() == Side.BUY
                ? context.currentGrossExposure().add(orderNotional)
                : context.currentGrossExposure().subtract(orderNotional).max(BigDecimal.ZERO);
        if (context.side() == Side.SELL) return RiskRuleSupport.pass(code(), projected, limit, "currency");
        return projected.compareTo(limit) > 0
                ? RiskRuleSupport.reject(code(), "MAX_GROSS_EXPOSURE_EXCEEDED", projected, limit, "currency")
                : RiskRuleSupport.pass(code(), projected, limit, "currency");
    }
}
