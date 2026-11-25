package in.aitradingsystem.api;

import in.aitradingsystem.governance.MatchedPaperShadowSessionEvidenceService;
import in.aitradingsystem.governance.MatchedPaperShadowSessionEvidenceService.SessionEvidence;
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
 * Read-only ADMIN evidence: the report never qualifies real PAPER/SHADOW
 * acceptance, performs inference or authorizes capital execution.
 */
@RestController
@RequestMapping("/api/admin/model-governance/paper-shadow/sessions/{sessionId}/evidence")
@PreAuthorize("hasRole('ADMIN')")
public class MatchedPaperShadowSessionEvidenceController {
    private final MatchedPaperShadowSessionEvidenceService evidence;

    public MatchedPaperShadowSessionEvidenceController(
            MatchedPaperShadowSessionEvidenceService evidence) {
        this.evidence = evidence;
    }

    @GetMapping
    public SessionEvidence read(@PathVariable UUID sessionId) {
        return evidence.read(sessionId);
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ProblemDetail missing(NoSuchElementException exception) {
        return ProblemDetail.forStatusAndDetail(
            HttpStatus.NOT_FOUND, "Unknown registered PAPER/SHADOW session");
    }
}
