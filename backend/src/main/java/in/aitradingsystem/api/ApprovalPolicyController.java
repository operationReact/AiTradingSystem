package in.aitradingsystem.api;

import in.aitradingsystem.audit.AuditService;
import in.aitradingsystem.execution.ApprovalMode;
import in.aitradingsystem.execution.ExecutionPolicy;
import in.aitradingsystem.execution.ExecutionPolicyService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/approval-policies")
public class ApprovalPolicyController {
    private final ExecutionPolicyService policies;
    private final AuditService audit;

    public ApprovalPolicyController(ExecutionPolicyService policies, AuditService audit) {
        this.policies = policies;
        this.audit = audit;
    }

    public record UpdateApprovalPolicyRequest(@NotNull ApprovalMode approvalMode) {}

    @GetMapping
    public List<ExecutionPolicy> list() {
        return policies.listPolicies();
    }

    @PutMapping("/{policyId}")
    public ExecutionPolicy update(@PathVariable UUID policyId,
                                  @Valid @RequestBody UpdateApprovalPolicyRequest request) {
        ExecutionPolicy updated = policies.updateApprovalMode(policyId, request.approvalMode());
        audit.record("APPROVAL_POLICY_UPDATED", "EXECUTION_POLICY", policyId.toString(),
            Map.of("policyVersion", updated.policyVersion(), "approvalMode", updated.approvalMode().name()));
        return updated;
    }
}
