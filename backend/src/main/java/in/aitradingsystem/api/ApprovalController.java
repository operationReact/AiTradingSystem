package in.aitradingsystem.api;

import in.aitradingsystem.common.PageResponse;
import in.aitradingsystem.execution.OrderApprovalService;
import in.aitradingsystem.execution.OrderApprovalState;
import in.aitradingsystem.execution.OrderApprovalView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/approvals")
@Validated
public class ApprovalController {
    private final OrderApprovalService approvals;

    public ApprovalController(OrderApprovalService approvals) {
        this.approvals = approvals;
    }

    public record ApprovalDecisionRequest(
        @NotNull @Min(0) Integer expectedRowVersion,
        @NotBlank @Size(max = 120) String actor,
        @Size(max = 300) String reason
    ) {}

    @GetMapping
    public PageResponse<OrderApprovalView> list(
            @RequestParam(required = false) OrderApprovalState state,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return approvals.list(state, page, size);
    }

    @GetMapping("/{approvalId}")
    public OrderApprovalView require(@PathVariable UUID approvalId) {
        return approvals.require(approvalId);
    }

    @PostMapping("/{approvalId}/approve")
    public OrderApprovalView approve(@PathVariable UUID approvalId,
                                     @Valid @RequestBody ApprovalDecisionRequest request) {
        return decide(approvalId, request, OrderApprovalState.APPROVED);
    }

    @PostMapping("/{approvalId}/reject")
    public OrderApprovalView reject(@PathVariable UUID approvalId,
                                    @Valid @RequestBody ApprovalDecisionRequest request) {
        return decide(approvalId, request, OrderApprovalState.REJECTED);
    }

    @PostMapping("/{approvalId}/cancel")
    public OrderApprovalView cancel(@PathVariable UUID approvalId,
                                    @Valid @RequestBody ApprovalDecisionRequest request) {
        return decide(approvalId, request, OrderApprovalState.CANCELLED);
    }

    private OrderApprovalView decide(UUID approvalId, ApprovalDecisionRequest request,
                                     OrderApprovalState decision) {
        return approvals.decide(
            approvalId, request.expectedRowVersion(), decision, request.actor(), normalize(request.reason()));
    }

    private static String normalize(String reason) {
        if (reason == null) return null;
        String value = reason.trim();
        return value.isEmpty() ? null : value;
    }
}
