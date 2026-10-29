package in.aitradingsystem.research.simulation;

import java.util.List;
import java.util.UUID;

public record ResearchEvaluationResult(
        UUID evaluationId,
        long candidateId,
        UUID backtestRunId,
        UUID scenarioRunId,
        ResearchDecisionLabel decisionLabel,
        List<String> reasonCodes,
        boolean scenarioReused) {

    public ResearchEvaluationResult {
        if (evaluationId == null || candidateId < 1 || scenarioRunId == null || decisionLabel == null) {
            throw new IllegalArgumentException("evaluation result identifiers and decision label are required");
        }
        reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes);
    }
}
