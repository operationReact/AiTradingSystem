package in.aitradingsystem.api;

import in.aitradingsystem.intelligence.ResearchReceiptDiagnosticsService;
import in.aitradingsystem.intelligence.ResearchReceiptDiagnosticsService.Report;
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

/** ADMIN-only research attempt diagnostics: read-only, never LIVE authority. */
@RestController
@RequestMapping("/api/admin/model-governance/paper-shadow")
@PreAuthorize("hasRole('ADMIN')")
public class ResearchReceiptDiagnosticsController {
    private final ResearchReceiptDiagnosticsService diagnostics;

    public ResearchReceiptDiagnosticsController(ResearchReceiptDiagnosticsService diagnostics) {
        this.diagnostics = diagnostics;
    }

    @GetMapping("/sessions/{sessionId}/cases/{caseKey}/research-receipts")
    public Report inspect(@PathVariable UUID sessionId, @PathVariable String caseKey) {
        return diagnostics.inspect(sessionId, caseKey);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail invalid(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
            "Invalid research evidence case identity");
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ProblemDetail missing(NoSuchElementException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND,
            "Registered PAPER/SHADOW session not found");
    }

    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail conflict(IllegalStateException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
            "Research roster is unavailable or invalid");
    }
}
