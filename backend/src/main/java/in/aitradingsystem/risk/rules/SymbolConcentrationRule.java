package in.aitradingsystem.risk.rules;

import in.aitradingsystem.domain.Side;
import in.aitradingsystem.risk.RiskContext;
import in.aitradingsystem.risk.RiskRule;
import in.aitradingsystem.risk.RiskRuleCode;
import in.aitradingsystem.risk.RiskRuleResult;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

@Component
public final class SymbolConcentrationRule implements RiskRule {
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    @Override public RiskRuleCode code() { return RiskRuleCode.MAX_SYMBOL_EXPOSURE; }
    @Override public int sequence() { return 160; }

    @Override
    public RiskRuleResult evaluate(RiskContext context) {
        BigDecimal orderNotional = RiskRuleSupport.gross(context);
        BigDecimal limit = context.currentEquity()
                .multiply(context.policy().maxSymbolExposurePercent()).divide(HUNDRED);
        BigDecimal projected = context.side() == Side.BUY
                ? context.currentSymbolExposure().add(orderNotional)
                : context.currentSymbolExposure().subtract(orderNotional).max(BigDecimal.ZERO);
        if (context.side() == Side.SELL) return RiskRuleSupport.pass(code(), projected, limit, "currency");
        return projected.compareTo(limit) > 0
                ? RiskRuleSupport.reject(code(), "MAX_SYMBOL_EXPOSURE_EXCEEDED", projected, limit, "currency")
                : RiskRuleSupport.pass(code(), projected, limit, "currency");
    }
}
