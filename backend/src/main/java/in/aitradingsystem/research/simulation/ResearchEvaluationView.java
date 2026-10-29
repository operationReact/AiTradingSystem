package in.aitradingsystem.research.simulation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ResearchEvaluationView(
        UUID evaluationId,
        long candidateId,
        UUID backtestRunId,
        UUID scenarioRunId,
        ResearchDecisionLabel decisionLabel,
        List<String> reasonCodes,
        Instant createdAt) {

    public ResearchEvaluationView {
        if (evaluationId == null || candidateId < 1 || scenarioRunId == null || decisionLabel == null || createdAt == null) {
            throw new IllegalArgumentException("evaluation view identifiers, label and createdAt are required");
        }
        reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes);
    }
}
