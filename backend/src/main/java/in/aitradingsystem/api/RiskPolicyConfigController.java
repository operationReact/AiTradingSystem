package in.aitradingsystem.api;

import in.aitradingsystem.risk.RiskPolicyConfigService;
import in.aitradingsystem.risk.RiskPolicyView;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
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
    public List<RiskPolicyView> list() {
        return policies.list();
    }

    @GetMapping("/{id}")
    public RiskPolicyView require(@PathVariable UUID id) {
        return policies.require(id);
    }

    @PutMapping("/{id}/activate")
    public RiskPolicyView activate(@PathVariable UUID id) {
        return policies.activate(id);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail notFound(IllegalArgumentException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail invalidState(IllegalStateException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    }
}
