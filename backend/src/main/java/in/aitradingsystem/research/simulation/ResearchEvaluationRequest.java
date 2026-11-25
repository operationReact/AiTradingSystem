package in.aitradingsystem.research.simulation;

import java.time.Instant;
import java.util.UUID;

public record ResearchEvaluationRequest(
        Instant historyFrom,
        Instant historyTo,
        int simulations,
        int horizonBars,
        int blockSize,
        long seed,
        int slippageBps,
        int roundTripCostBps,
        UUID backtestRunId) {

    public ResearchEvaluationRequest {
        if (historyFrom == null || historyTo == null || !historyFrom.isBefore(historyTo)) {
            throw new IllegalArgumentException("valid history window is required");
        }
        if (simulations < 100 || simulations > 100_000) throw new IllegalArgumentException("simulations must be between 100 and 100000");
        if (horizonBars < 1 || horizonBars > 500) throw new IllegalArgumentException("horizonBars must be between 1 and 500");
        if (blockSize < 1 || blockSize > 50 || blockSize > horizonBars) throw new IllegalArgumentException("blockSize must be between 1 and min(50, horizonBars)");
        if (slippageBps < 0 || slippageBps > 500) throw new IllegalArgumentException("slippageBps must be between 0 and 500");
        if (roundTripCostBps < 0 || roundTripCostBps > 500) throw new IllegalArgumentException("roundTripCostBps must be between 0 and 500");
    }
}
