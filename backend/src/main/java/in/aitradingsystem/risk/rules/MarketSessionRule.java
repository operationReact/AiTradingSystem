package in.aitradingsystem.risk.rules;

import in.aitradingsystem.risk.MarketSessionState;
import in.aitradingsystem.risk.RiskContext;
import in.aitradingsystem.risk.RiskOutcome;
import in.aitradingsystem.risk.RiskRule;
import in.aitradingsystem.risk.RiskRuleCode;
import in.aitradingsystem.risk.RiskRuleResult;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public final class MarketSessionRule implements RiskRule {
    @Override public RiskRuleCode code() { return RiskRuleCode.MARKET_SESSION; }
    @Override public int sequence() { return 60; }

    @Override
    public RiskRuleResult evaluate(RiskContext context) {
        if (!context.policy().enforceMarketSession()) {
            return new RiskRuleResult(code(), RiskOutcome.PASS, code().name() + "_PASS",
                    null, null, null, Map.of("disabledByPolicy", true));
        }
        return context.marketSessionState() == MarketSessionState.OPEN
                ? RiskRuleSupport.pass(code())
                : new RiskRuleResult(code(), RiskOutcome.HARD_REJECT, "MARKET_SESSION_CLOSED",
                        null, null, null, Map.of("marketSessionState", context.marketSessionState().name()));
    }
}
