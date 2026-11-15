package in.aitradingsystem.api;

import in.aitradingsystem.mind.MindDecisionOutcomeService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Customer-authenticated, read-only persisted strategy outcomes. Never places orders. */
@RestController
@RequestMapping("/api/customer/mind/v1")
public class CustomerMindDecisionOutcomeController {
    private final MindDecisionOutcomeService outcomes;

    public CustomerMindDecisionOutcomeController(MindDecisionOutcomeService outcomes) {
        this.outcomes = outcomes;
    }

    @GetMapping("/decision-outcomes")
    public ResponseEntity<MindDecisionOutcomeService.Outcomes> decisionOutcomes() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(outcomes.inspect());
    }
}
