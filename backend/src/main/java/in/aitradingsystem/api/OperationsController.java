package in.aitradingsystem.api;

import in.aitradingsystem.operations.OperationalHealthService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/operations")
public class OperationsController {
    private final OperationalHealthService health;
    public OperationsController(OperationalHealthService health) { this.health = health; }
    @GetMapping("/health")
    public OperationalHealthService.HealthSnapshot health() { return health.snapshot(); }
}
