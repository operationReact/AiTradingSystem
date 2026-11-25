package in.aitradingsystem.api;

import in.aitradingsystem.governance.ProspectiveMatchedSourceService;
import in.aitradingsystem.governance.ProspectiveMatchedSourceService.CaptureInput;
import in.aitradingsystem.governance.ProspectiveMatchedSourceService.CaptureView;
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
import org.springframework.web.bind.annotation.RestController;

/** ADMIN-only server source capture for future PAPER/SHADOW inference workers. */
@RestController
@RequestMapping("/api/admin/model-governance/paper-shadow/sessions/{sessionId}/source-cases")
@PreAuthorize("hasRole('ADMIN')")
public class ProspectiveMatchedSourceController {
    private final ProspectiveMatchedSourceService source;

    public ProspectiveMatchedSourceController(ProspectiveMatchedSourceService source) {
        this.source = source;
    }

    @PostMapping
    public ResponseEntity<CaptureView> capture(
            @PathVariable UUID sessionId, @RequestBody CaptureInput input) {
        return ResponseEntity.status(HttpStatus.CREATED).body(source.capture(sessionId, input));
    }

    @GetMapping("/{caseKey}")
    public CaptureView byCase(@PathVariable UUID sessionId, @PathVariable String caseKey) {
        return source.byCase(sessionId, caseKey);
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
