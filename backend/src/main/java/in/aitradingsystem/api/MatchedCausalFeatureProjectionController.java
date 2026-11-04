package in.aitradingsystem.api;

import in.aitradingsystem.governance.MatchedCausalFeatureProjectionService;
import in.aitradingsystem.governance.MatchedCausalFeatureProjectionService.ProjectionView;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** ADMIN-only causal research feature projection; no model calls or broker actions. */
@RestController
@RequestMapping(
    "/api/admin/model-governance/paper-shadow/sessions/{sessionId}/source-cases/{caseKey}/features")
@PreAuthorize("hasRole('ADMIN')")
public class MatchedCausalFeatureProjectionController {
    private final MatchedCausalFeatureProjectionService features;

    public MatchedCausalFeatureProjectionController(MatchedCausalFeatureProjectionService features) {
        this.features = features;
    }

    @PostMapping
    public ResponseEntity<ProjectionView> freeze(
            @PathVariable UUID sessionId, @PathVariable String caseKey) {
        return ResponseEntity.status(HttpStatus.CREATED).body(features.freeze(sessionId, caseKey));
    }

    @GetMapping
    public ProjectionView byCase(@PathVariable UUID sessionId, @PathVariable String caseKey) {
        return features.byCase(sessionId, caseKey);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail invalid(IllegalArgumentException error) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, error.getMessage());
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ProblemDetail missing(NoSuchElementException error) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, error.getMessage());
    }

    @ExceptionHandler({IllegalStateException.class, DuplicateKeyException.class})
    public ProblemDetail conflict(RuntimeException error) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, error.getMessage());
    }
}
