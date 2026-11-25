package in.aitradingsystem.risk;

public interface RiskRule {
    RiskRuleCode code();
    int sequence();
    RiskRuleResult evaluate(RiskContext context);
}
