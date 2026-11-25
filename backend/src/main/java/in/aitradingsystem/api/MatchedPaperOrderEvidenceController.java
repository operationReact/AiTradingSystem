package in.aitradingsystem.api;

import in.aitradingsystem.governance.MatchedPaperOrderEvidenceService;
import in.aitradingsystem.governance.MatchedPaperOrderEvidenceService.LinkInput;
import in.aitradingsystem.governance.MatchedPaperOrderEvidenceService.LinkView;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
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
import org.springframework.web.bind.annotation.RestController;

/** ADMIN-only evidence link to an existing PAPER order; this endpoint cannot place one. */
@RestController
@RequestMapping("/api/admin/model-governance/paper-shadow/sessions/{sessionId}/observations/{caseKey}/paper-order-link")
@PreAuthorize("hasRole('ADMIN')")
public class MatchedPaperOrderEvidenceController {
    private final MatchedPaperOrderEvidenceService evidence;

    public MatchedPaperOrderEvidenceController(MatchedPaperOrderEvidenceService evidence) {
        this.evidence = evidence;
    }

    @PostMapping
    public ResponseEntity<LinkView> attach(
            @PathVariable UUID sessionId, @PathVariable String caseKey, @RequestBody LinkInput input) {
        return ResponseEntity.status(HttpStatus.CREATED).body(evidence.attach(sessionId, caseKey, input));
    }

    @GetMapping
    public LinkView read(@PathVariable UUID sessionId, @PathVariable String caseKey) {
        return evidence.byCase(sessionId, caseKey);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail invalid(IllegalArgumentException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ProblemDetail notFound(NoSuchElementException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler({IllegalStateException.class, DataIntegrityViolationException.class})
    public ProblemDetail conflict(RuntimeException ex) {
        return ProblemDetail.forStatusAndDetail(
            HttpStatus.CONFLICT, "Immutable PAPER order evidence is unavailable or conflicts with prior evidence");
    }
}
