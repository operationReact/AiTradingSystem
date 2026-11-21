package in.aitradingsystem.tradingrooms;

import java.time.Clock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Admin-only read-only coverage view. It does not evaluate or place broker orders. */
@RestController
@RequestMapping("/api/admin/research/fno-43")
public class AdminFno43ReadinessController {
    private final Fno43StrategyReadinessService service;
    private final Clock clock;

    @Autowired
    public AdminFno43ReadinessController(Fno43StrategyReadinessService service) {
        this(service, Clock.systemUTC());
    }

    AdminFno43ReadinessController(Fno43StrategyReadinessService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    @GetMapping("/readiness")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    public ResponseEntity<Fno43StrategyReadinessService.Coverage> readiness() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.inspect(clock.instant()));
    }
}
