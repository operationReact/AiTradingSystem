package in.aitradingsystem.api;

import in.aitradingsystem.paper.PaperReconciliationQueryService;
import in.aitradingsystem.paper.PaperReconciliationQueryService.ReconciliationRunView;
import in.aitradingsystem.paper.PaperReconciliationService;
import in.aitradingsystem.paper.PaperReconciliationService.ReconciliationResult;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/paper-reconciliation")
@Validated
public class PaperReconciliationController {
    private final PaperReconciliationService reconciliation;
    private final PaperReconciliationQueryService queries;

    public PaperReconciliationController(PaperReconciliationService reconciliation,
                                         PaperReconciliationQueryService queries) {
        this.reconciliation = reconciliation;
        this.queries = queries;
    }

    @GetMapping("/runs")
    public List<ReconciliationRunView> runs(
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return queries.recentRuns(limit);
    }

    @PostMapping
    public ReconciliationResult run() {
        return reconciliation.run();
    }
}
