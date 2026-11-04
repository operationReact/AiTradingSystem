package in.aitradingsystem.api;

import in.aitradingsystem.governance.PaperShadowDecisionJournalService;
import in.aitradingsystem.governance.PaperShadowDecisionJournalService.CaptureCase;
import in.aitradingsystem.governance.PaperShadowDecisionJournalService.CaseView;
import in.aitradingsystem.governance.PaperShadowDecisionJournalService.CreateSession;
import in.aitradingsystem.governance.PaperShadowDecisionJournalService.SessionView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADMIN-only V4 research observation intake. No PAPER placement or broker-order endpoints.
 * Only an already-created automated PAPER order can be bound to a recorded champion ENTER.
 */
@RestController
@RequestMapping("/api/admin/model-governance/paper-shadow")
@PreAuthorize("hasRole('ADMIN')")
public class PaperShadowDecisionJournalController {
    private final PaperShadowDecisionJournalService journal;

    public PaperShadowDecisionJournalController(PaperShadowDecisionJournalService journal) {
        this.journal = journal;
    }

    public record LinkPaperOrderRequest(@NotNull UUID orderId) {}

    @PostMapping("/sessions")
    public ResponseEntity<SessionView> create(@RequestBody CreateSession request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(journal.createSession(request));
    }

    @GetMapping("/sessions/{sessionId}")
    public SessionView session(@PathVariable UUID sessionId) {
        return journal.session(sessionId);
    }

    @PostMapping("/sessions/{sessionId}/cases")
    public ResponseEntity<CaseView> capture(@PathVariable UUID sessionId, @RequestBody CaptureCase request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(journal.capture(sessionId, request));
    }

    @GetMapping("/sessions/{sessionId}/cases")
    public List<CaseView> cases(@PathVariable UUID sessionId,
                                @RequestParam(defaultValue = "100") int limit) {
        return journal.recent(sessionId, limit);
    }

    @GetMapping("/sessions/{sessionId}/cases/{caseKey}")
    public CaseView caseEvidence(@PathVariable UUID sessionId, @PathVariable String caseKey) {
        return journal.evidence(sessionId, caseKey);
    }

    @PostMapping("/sessions/{sessionId}/cases/{caseKey}/paper-order-links")
    public CaseView linkPaperOrder(@PathVariable UUID sessionId, @PathVariable String caseKey,
                                   @Valid @RequestBody LinkPaperOrderRequest request) {
        return journal.linkPaperOrder(sessionId, caseKey, request.orderId());
    }

    @ExceptionHandler(NoSuchElementException.class)
    ProblemDetail notFound(NoSuchElementException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalid(IllegalArgumentException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler({IllegalStateException.class, DataIntegrityViolationException.class})
    ProblemDetail conflict(Exception ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
            "PAPER/SHADOW research state or immutable lineage conflict");
    }
}
