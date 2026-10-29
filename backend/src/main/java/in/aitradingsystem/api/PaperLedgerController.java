package in.aitradingsystem.api;

import in.aitradingsystem.paper.PaperLedgerQueryService;
import in.aitradingsystem.paper.PaperLedgerQueryService.CashLedgerView;
import in.aitradingsystem.paper.PaperLedgerQueryService.FillLedgerView;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@Validated
public class PaperLedgerController {
    private final PaperLedgerQueryService ledgers;

    public PaperLedgerController(PaperLedgerQueryService ledgers) {
        this.ledgers = ledgers;
    }

    @GetMapping("/paper-fills")
    public List<FillLedgerView> fills(@RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit) {
        return ledgers.recentFills(limit);
    }

    @GetMapping("/paper-cash-ledger")
    public List<CashLedgerView> cashLedger(@RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit) {
        return ledgers.recentCashEntries(limit);
    }
}
