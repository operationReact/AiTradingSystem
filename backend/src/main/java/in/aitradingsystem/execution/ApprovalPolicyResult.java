package in.aitradingsystem.execution;

import java.util.List;
import java.util.UUID;

public record ApprovalPolicyResult(
    ApprovalEvaluationDecision decision,
    UUID policyId,
    int policyVersion,
    List<String> reasonCodes
) {
    public ApprovalPolicyResult {
        reasonCodes = List.copyOf(reasonCodes);
    }
}
