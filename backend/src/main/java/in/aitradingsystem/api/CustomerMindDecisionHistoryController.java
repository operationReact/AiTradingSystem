package in.aitradingsystem.api;

import in.aitradingsystem.mind.MindDecisionHistoryService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Customer-authenticated, read-only persisted research candidate history. Never places orders. */
@RestController
@RequestMapping("/api/customer/mind/v1")
public class CustomerMindDecisionHistoryController {
    private final MindDecisionHistoryService history;

    public CustomerMindDecisionHistoryController(MindDecisionHistoryService history) {
        this.history = history;
    }

    @GetMapping("/decision-history")
    public ResponseEntity<MindDecisionHistoryService.History> decisionHistory() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(history.inspect());
    }
}
