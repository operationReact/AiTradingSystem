package in.aitradingsystem.api;

import in.aitradingsystem.research.simulation.ScenarioRunRepository;
import in.aitradingsystem.research.simulation.ScenarioRunView;
import in.aitradingsystem.research.simulation.ScenarioSimulationRequest;
import in.aitradingsystem.research.simulation.ScenarioSimulationResult;
import in.aitradingsystem.research.simulation.ScenarioSimulationService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/research/simulations")
public class ScenarioSimulationController {
    private final ScenarioSimulationService service;
    private final ScenarioRunRepository runs;

    public ScenarioSimulationController(ScenarioSimulationService service, ScenarioRunRepository runs) {
        this.service = service;
        this.runs = runs;
    }

    /** Expert/stateless simulation path retained for direct research use. */
    @PostMapping
    public ResponseEntity<ScenarioSimulationResult> simulate(@RequestBody ScenarioSimulationRequest request) {
        return ResponseEntity.ok(service.simulate(request));
    }

    /** Persisted scenario evidence produced by candidate evaluation/orchestration. */
    @GetMapping("/{id}")
    public ResponseEntity<ScenarioRunView> getSimulation(@PathVariable UUID id) {
        return ResponseEntity.ok(runs.requireView(id));
    }
}
