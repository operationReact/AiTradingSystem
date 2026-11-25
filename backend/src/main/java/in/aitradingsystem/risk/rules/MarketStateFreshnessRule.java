package in.aitradingsystem.risk.rules;

import in.aitradingsystem.risk.RiskContext;
import in.aitradingsystem.risk.RiskRule;
import in.aitradingsystem.risk.RiskRuleCode;
import in.aitradingsystem.risk.RiskRuleResult;
import java.math.BigDecimal;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
public final class MarketStateFreshnessRule implements RiskRule {
    @Override public RiskRuleCode code() { return RiskRuleCode.MARKET_STATE_FRESHNESS; }
    @Override public int sequence() { return 50; }

    @Override
    public RiskRuleResult evaluate(RiskContext context) {
        BigDecimal limit = BigDecimal.valueOf(context.policy().marketStateMaxAgeSeconds());
        if (context.marketPriceAsOf() == null) {
            return RiskRuleSupport.reject(code(), "MARKET_STATE_UNAVAILABLE", null, limit, "seconds");
        }
        if (context.marketPriceAsOf().isAfter(context.now())) {
            return RiskRuleSupport.reject(code(), "MARKET_STATE_STALE", BigDecimal.valueOf(-1), limit, "seconds");
        }
        long ageSeconds = Duration.between(context.marketPriceAsOf(), context.now()).getSeconds();
        BigDecimal observed = BigDecimal.valueOf(ageSeconds);
        return ageSeconds > context.policy().marketStateMaxAgeSeconds()
                ? RiskRuleSupport.reject(code(), "MARKET_STATE_STALE", observed, limit, "seconds")
                : RiskRuleSupport.pass(code(), observed, limit, "seconds");
    }
}
