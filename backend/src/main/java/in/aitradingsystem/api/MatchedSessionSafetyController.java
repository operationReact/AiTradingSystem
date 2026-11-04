package in.aitradingsystem.api;

import in.aitradingsystem.governance.MatchedSessionSafetyService;
import in.aitradingsystem.governance.MatchedSessionSafetyService.Action;
import in.aitradingsystem.governance.MatchedSessionSafetyService.StateView;
import in.aitradingsystem.governance.MatchedSessionSafetyService.TransitionView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Controls only V4 PAPER/SHADOW evidence capture; does not trade or promote models. */
@RestController
@RequestMapping("/api/admin/model-governance/paper-shadow/sessions/{sessionId}/control")
@PreAuthorize("hasRole('ADMIN')")
public class MatchedSessionSafetyController {
    private final MatchedSessionSafetyService safety;

    public MatchedSessionSafetyController(MatchedSessionSafetyService safety) {
        this.safety = safety;
    }

    public record TransitionRequest(
        @NotNull Action action,
        @NotBlank @Size(min = 10, max = 512) String reason) {}

    @GetMapping
    public StateView state(@PathVariable UUID sessionId) {
        return safety.state(sessionId);
    }

    @PutMapping
    public StateView transition(
            @PathVariable UUID sessionId,
            @Valid @RequestBody TransitionRequest request) {
        return safety.transition(sessionId, request.action(), request.reason());
    }

    @GetMapping("/history")
    public List<TransitionView> history(
            @PathVariable UUID sessionId,
            @RequestParam(defaultValue = "50") int limit) {
        return safety.history(sessionId, limit);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail invalid(IllegalArgumentException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ProblemDetail missing(NoSuchElementException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail conflict(IllegalStateException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
    }
}
