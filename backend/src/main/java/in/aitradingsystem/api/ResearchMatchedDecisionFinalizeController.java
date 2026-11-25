package in.aitradingsystem.api;

import in.aitradingsystem.intelligence.ResearchMatchedDecisionFinalizeService;
import in.aitradingsystem.intelligence.ResearchMatchedDecisionFinalizeService.FinalizedView;
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

/** Reconcile complete V61 results into research-only V56 PAPER/SHADOW evidence. */
@RestController
@RequestMapping("/api/admin/model-governance/paper-shadow")
@PreAuthorize("hasRole('ADMIN')")
public class ResearchMatchedDecisionFinalizeController {
    private final ResearchMatchedDecisionFinalizeService finalizer;

    public ResearchMatchedDecisionFinalizeController(
            ResearchMatchedDecisionFinalizeService finalizer) {
        this.finalizer = finalizer;
    }

    @PostMapping("/sessions/{sessionId}/cases/{caseKey}/finalize-research-evidence")
    public ResponseEntity<FinalizedView> finalizeCase(
            @PathVariable UUID sessionId, @PathVariable String caseKey) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(finalizer.finalizeCase(sessionId, caseKey));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail invalid(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
            "Invalid matched research evidence request");
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ProblemDetail missing(NoSuchElementException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND,
            "Required frozen research evidence was not found");
    }

    @ExceptionHandler({IllegalStateException.class, DuplicateKeyException.class})
    public ProblemDetail conflict(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
            "Model roster is incomplete, stale, unsafe or already conflicted");
    }
}
