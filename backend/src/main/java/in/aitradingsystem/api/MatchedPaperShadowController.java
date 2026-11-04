package in.aitradingsystem.api;

import in.aitradingsystem.governance.MatchedPaperShadowIntakeService;
import in.aitradingsystem.governance.MatchedPaperShadowIntakeService.EnrollInput;
import in.aitradingsystem.governance.MatchedPaperShadowIntakeService.ObservationInput;
import in.aitradingsystem.governance.MatchedPaperShadowIntakeService.ObservationView;
import in.aitradingsystem.governance.MatchedPaperShadowIntakeService.SessionView;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Administrative evidence intake, not a trading endpoint or actual model runner. */
@RestController
@RequestMapping("/api/admin/model-governance/paper-shadow")
@PreAuthorize("hasRole('ADMIN')")
public class MatchedPaperShadowController {
    private final MatchedPaperShadowIntakeService intake;

    public MatchedPaperShadowController(MatchedPaperShadowIntakeService intake) {
        this.intake = intake;
    }

    @PostMapping("/sessions")
    public ResponseEntity<SessionView> enroll(@RequestBody EnrollInput request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(intake.enroll(request));
    }

    @GetMapping("/sessions/{sessionId}")
    public SessionView session(@PathVariable UUID sessionId) {
        return intake.session(sessionId);
    }

    @PostMapping("/sessions/{sessionId}/observations")
    public ResponseEntity<ObservationView> append(
            @PathVariable UUID sessionId, @RequestBody ObservationInput request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(intake.append(sessionId, request));
    }

    @GetMapping("/sessions/{sessionId}/observations/{caseKey}")
    public ObservationView observation(
            @PathVariable UUID sessionId, @PathVariable String caseKey) {
        return intake.observation(sessionId, caseKey);
    }

    @GetMapping("/sessions/{sessionId}/observations")
    public List<ObservationView> recent(
            @PathVariable UUID sessionId, @RequestParam(defaultValue = "50") int limit) {
        return intake.recent(sessionId, limit);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail invalid(IllegalArgumentException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ProblemDetail unknown(NoSuchElementException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    @ExceptionHandler({IllegalStateException.class, DuplicateKeyException.class})
    public ProblemDetail conflict(RuntimeException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
    }
}
