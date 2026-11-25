package in.aitradingsystem.tradingrooms;

import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only operator view of the futures research evaluators. No order or persistence path. */
@RestController
@RequestMapping("/api/admin/research/fno-futures")
public class AdminFnoFuturesResearchController {
    private final FnoFuturesResearchService service;
    private final Clock clock;

    @Autowired
    public AdminFnoFuturesResearchController(FnoFuturesResearchService service) {
        this(service, Clock.systemUTC());
    }

    AdminFnoFuturesResearchController(FnoFuturesResearchService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    @GetMapping("/status")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    public ResponseEntity<List<FnoFuturesResearchService.StrategyStatus>> status() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.evaluateAll(clock.instant()));
    }
}
