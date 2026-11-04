package in.aitradingsystem.api;

import in.aitradingsystem.governance.MatchedPaperShadowFeatureCaptureService;
import in.aitradingsystem.governance.MatchedPaperShadowFeatureCaptureService.FeatureCapture;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Admin model feature evidence, not a model-run or capital execution endpoint. */
@RestController
@RequestMapping("/api/admin/model-governance/paper-shadow/sessions/{sessionId}/source-cases/{caseKey}/features")
@PreAuthorize("hasRole('ADMIN')")
public class MatchedPaperShadowFeatureController {
    private final MatchedPaperShadowFeatureCaptureService service;

    public MatchedPaperShadowFeatureController(
            MatchedPaperShadowFeatureCaptureService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<FeatureCapture> freeze(
            @PathVariable UUID sessionId, @PathVariable String caseKey) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(service.freeze(sessionId, caseKey));
    }

    @GetMapping
    public FeatureCapture read(@PathVariable UUID sessionId, @PathVariable String caseKey) {
        return service.byCase(sessionId, caseKey);
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ProblemDetail missing(NoSuchElementException error) {
        return ProblemDetail.forStatusAndDetail(
            HttpStatus.NOT_FOUND, "Missing registered point-in-time source or feature");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail invalid(IllegalArgumentException error) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, error.getMessage());
    }

    @ExceptionHandler({IllegalStateException.class, DataIntegrityViolationException.class})
    public ProblemDetail conflict(RuntimeException error) {
        return ProblemDetail.forStatusAndDetail(
            HttpStatus.CONFLICT, "Point-in-time model feature evidence is unavailable or unsafe");
    }
}
