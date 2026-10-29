package in.aitradingsystem.api;

import in.aitradingsystem.common.PageResponse;
import in.aitradingsystem.risk.CreateRiskPolicyRequest;
import in.aitradingsystem.risk.RiskPolicyConfigService;
import in.aitradingsystem.risk.RiskPolicyView;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/risk/policies")
public class RiskPolicyConfigController {
    private final RiskPolicyConfigService policies;

    public RiskPolicyConfigController(RiskPolicyConfigService policies) {
        this.policies = policies;
    }

    @GetMapping("/active")
    public RiskPolicyView active() {
        return policies.active();
    }

    @GetMapping
    public PageResponse<RiskPolicyView> history(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return policies.history(page, size);
    }

    @GetMapping("/{id}")
    public RiskPolicyView get(@PathVariable UUID id) {
        return policies.get(id);
    }

    @PostMapping
    public RiskPolicyView createAndActivate(@RequestBody CreateRiskPolicyRequest request, Authentication authentication) {
        return policies.createAndActivate(request, authentication == null ? null : authentication.getName());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalidRequest(IllegalArgumentException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail invalidState(IllegalStateException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    }
}
