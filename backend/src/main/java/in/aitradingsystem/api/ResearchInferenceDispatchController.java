package in.aitradingsystem.api;

import in.aitradingsystem.intelligence.ResearchInferenceDispatchService;
import in.aitradingsystem.intelligence.ResearchInferenceDispatchService.DispatchView;
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
 * Opt-in ADMIN-only, PAPER-gated per-model research inference.
 * No order, promotion, bulk retry or LIVE authority endpoint exists here.
 */
@RestController
@RequestMapping("/api/admin/model-governance/paper-shadow")
@PreAuthorize("hasRole('ADMIN')")
public final class ResearchInferenceDispatchController {
    private final ResearchInferenceDispatchService dispatch;

    public ResearchInferenceDispatchController(ResearchInferenceDispatchService dispatch) {
        this.dispatch = dispatch;
    }

    @PostMapping("/sessions/{sessionId}/cases/{caseKey}/models/{modelVersionId}/research-inference")
    public ResponseEntity<DispatchView> run(
            @PathVariable UUID sessionId, @PathVariable String caseKey,
            @PathVariable UUID modelVersionId) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(dispatch.dispatch(sessionId, caseKey, modelVersionId));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail invalid(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
            "Invalid research-only inference request");
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ProblemDetail unknown(NoSuchElementException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND,
            "Required frozen research evidence does not exist");
    }

    @ExceptionHandler({IllegalStateException.class, DuplicateKeyException.class})
    public ProblemDetail conflict(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
            "Research inference is disabled, unsafe, or already attempted");
    }
}
