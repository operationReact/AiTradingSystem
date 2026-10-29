package in.aitradingsystem.api;

import in.aitradingsystem.research.simulation.ResearchEvaluationRepository;
import in.aitradingsystem.research.simulation.ResearchEvaluationRequest;
import in.aitradingsystem.research.simulation.ResearchEvaluationResult;
import in.aitradingsystem.research.simulation.ResearchEvaluationView;
import in.aitradingsystem.research.simulation.ScenarioResearchOrchestrator;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/research/candidates")
public class ResearchEvaluationController {
    private final ScenarioResearchOrchestrator orchestrator;
    private final ResearchEvaluationRepository evaluations;

    public ResearchEvaluationController(
            ScenarioResearchOrchestrator orchestrator,
            ResearchEvaluationRepository evaluations) {
        this.orchestrator = orchestrator;
        this.evaluations = evaluations;
    }

    @PostMapping("/{candidateId}/evaluate")
    public ResponseEntity<ResearchEvaluationResult> evaluate(
            @PathVariable long candidateId,
            @RequestBody ResearchEvaluationRequest request) {
        return ResponseEntity.ok(orchestrator.evaluateCandidate(candidateId, request));
    }

    @GetMapping("/{candidateId}/evaluations")
    public ResponseEntity<List<ResearchEvaluationView>> evaluations(@PathVariable long candidateId) {
        return ResponseEntity.ok(evaluations.listEvaluations(candidateId));
    }
}
