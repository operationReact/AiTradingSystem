package in.aitradingsystem.api;

import in.aitradingsystem.mind.MindResearchEngineRegistry;
import java.time.Clock;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only engine capability view. Authenticated by the customer security rule. */
@RestController
public class CustomerMindResearchEngineController {
    private final Clock clock;

    public CustomerMindResearchEngineController(Clock clock) {
        this.clock = clock;
    }

    @GetMapping("/api/customer/mind/v1/research-engines")
    public ResponseEntity<MindResearchEngineRegistry.Registry> engines() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(MindResearchEngineRegistry.snapshot(clock));
    }
}
