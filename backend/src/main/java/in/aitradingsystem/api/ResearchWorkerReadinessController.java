package in.aitradingsystem.api;

import in.aitradingsystem.intelligence.ResearchWorkerReadinessService;
import in.aitradingsystem.intelligence.ResearchWorkerReadinessService.ReadinessView;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only ADMIN-only V72 operational evidence. This is neither an order
 * endpoint nor an authorization to dispatch or promote research models.
 */
@RestController
@RequestMapping("/api/admin/model-governance/paper-shadow")
@PreAuthorize("hasRole('ADMIN')")
public class ResearchWorkerReadinessController {
    private final ResearchWorkerReadinessService readiness;

    public ResearchWorkerReadinessController(ResearchWorkerReadinessService readiness) {
        this.readiness = readiness;
    }

    @GetMapping("/sessions/{sessionId}/worker-readiness")
    public ReadinessView inspect(@PathVariable UUID sessionId) {
        return readiness.inspect(sessionId);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail invalid(IllegalArgumentException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
            "Invalid enrolled research session");
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ProblemDetail missing(NoSuchElementException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND,
            "Research enrollment not found");
    }

    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail unavailable(IllegalStateException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
            "Research worker readiness disabled, unavailable or evidence unsafe");
    }
}
