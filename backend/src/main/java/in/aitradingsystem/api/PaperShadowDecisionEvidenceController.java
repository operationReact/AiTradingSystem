package in.aitradingsystem.api;

import in.aitradingsystem.paper.PaperShadowDecisionEvidenceService;
import in.aitradingsystem.paper.PaperShadowDecisionEvidenceService.CaseCapture;
import in.aitradingsystem.paper.PaperShadowDecisionEvidenceService.CaseEvidence;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin-only evidence intake, NOT a PAPER order endpoint or SHADOW execution API.
 * Source times/hashes are operator declarations; independent custody remains unverified.
 */
@RestController
@Validated
@RequestMapping("/api/admin/model-governance/paper-shadow/evidence")
@PreAuthorize("hasRole('ADMIN')")
public class PaperShadowDecisionEvidenceController {
    private final PaperShadowDecisionEvidenceService evidence;

    public PaperShadowDecisionEvidenceController(PaperShadowDecisionEvidenceService evidence) {
        this.evidence = evidence;
    }

    @PostMapping("/cases")
    public CaseEvidence capture(@Valid @RequestBody CaseCapture request) {
        return evidence.capture(request);
    }

    @GetMapping("/cases/{caseId}")
    public CaseEvidence byId(@PathVariable UUID caseId) {
        return evidence.byId(caseId);
    }

    @GetMapping("/cases")
    public List<CaseEvidence> recent(@RequestParam(defaultValue = "25")
                                     @Min(1) @Max(50) int limit) {
        return evidence.recent(limit);
    }

    @ExceptionHandler(NoSuchElementException.class)
    ProblemDetail notFound(NoSuchElementException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalid(IllegalArgumentException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail conflict(DataIntegrityViolationException ex) {
        return ProblemDetail.forStatusAndDetail(
            HttpStatus.CONFLICT, "The immutable PAPER/SHADOW case or order link already exists");
    }
}
