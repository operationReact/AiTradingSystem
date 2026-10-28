package in.aitradingsystem.api;

import in.aitradingsystem.research.simulation.ScenarioSimulationRequest;
import in.aitradingsystem.research.simulation.ScenarioSimulationResult;
import in.aitradingsystem.research.simulation.ScenarioSimulationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/research/simulations")
public class ScenarioSimulationController {
    private final ScenarioSimulationService service;

    public ScenarioSimulationController(ScenarioSimulationService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ScenarioSimulationResult> simulate(@RequestBody ScenarioSimulationRequest request) {
        return ResponseEntity.ok(service.simulate(request));
    }
}
