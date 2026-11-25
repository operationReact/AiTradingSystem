package in.aitradingsystem.api;

import in.aitradingsystem.execution.ApprovalMode;
import in.aitradingsystem.execution.ExecutionPolicy;
import in.aitradingsystem.execution.ExecutionPolicyService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
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

    public ApprovalPolicyController(ExecutionPolicyService policies) {
        this.policies = policies;
    }

    public record UpdateApprovalPolicyRequest(@NotNull ApprovalMode approvalMode) {}

    @GetMapping
    public List<ExecutionPolicy> list() {
        return policies.listPolicies();
    }

    @PutMapping("/{policyId}")
    public ExecutionPolicy update(@PathVariable UUID policyId,
                                  @Valid @RequestBody UpdateApprovalPolicyRequest request) {
        return policies.updateApprovalMode(policyId, request.approvalMode());
    }
}
