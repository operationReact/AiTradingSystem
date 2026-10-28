package in.aitradingsystem.api;

import in.aitradingsystem.paper.PaperReconciliationService;
import in.aitradingsystem.paper.PaperReconciliationService.ReconciliationStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only PAPER accounting/reconciliation evidence. */
@RestController
@RequestMapping("/api/paper-reconciliation")
public class PaperReconciliationController {
    private final PaperReconciliationService reconciliation;

    public PaperReconciliationController(PaperReconciliationService reconciliation) {
        this.reconciliation = reconciliation;
    }

    @GetMapping
    public ReconciliationStatus status() {
        return reconciliation.status();
    }
}
