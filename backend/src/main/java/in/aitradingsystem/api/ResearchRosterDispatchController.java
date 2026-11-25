package in.aitradingsystem.api;

import in.aitradingsystem.intelligence.ResearchRosterDispatchService;
import in.aitradingsystem.intelligence.ResearchRosterDispatchService.RosterDispatchView;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADMIN-only, operator-opt-in research roster runner. No LIVE execution,
 * PAPER order submission, auto-promotion or uncertain-outcome retry.
 */
@RestController
@RequestMapping("/api/admin/model-governance/paper-shadow")
@PreAuthorize("hasRole('ADMIN')")
public class ResearchRosterDispatchController {
    private final ResearchRosterDispatchService service;

    public ResearchRosterDispatchController(ResearchRosterDispatchService service) {
        this.service = service;
    }

    @PostMapping("/sessions/{sessionId}/cases/{caseKey}/dispatch-research-roster")
    public ResponseEntity<RosterDispatchView> dispatch(
            @PathVariable UUID sessionId, @PathVariable String caseKey) {
        return ResponseEntity.status(HttpStatus.OK)
            .body(service.dispatchRoster(sessionId, caseKey));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail invalid(IllegalArgumentException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
            "Invalid bounded research roster request");
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ProblemDetail missing(NoSuchElementException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND,
            "Research roster/source was not found");
    }

    @ExceptionHandler({IllegalStateException.class, DuplicateKeyException.class})
    public ProblemDetail conflict(RuntimeException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
            "Research roster disabled, unsafe, already attempted or interrupted");
    }
}
