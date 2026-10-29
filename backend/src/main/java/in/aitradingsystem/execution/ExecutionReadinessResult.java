package in.aitradingsystem.execution;

import in.aitradingsystem.paper.OrderCommand;
import in.aitradingsystem.risk.RiskContext;
import in.aitradingsystem.risk.RiskDecisionV2;

public record ExecutionReadinessResult(
    OrderCommand command,
    RiskContext riskContext,
    RiskDecisionV2 riskDecision,
    ExecutionPolicy executionPolicy,
    ApprovalPolicyResult policyResult
) {}
