package in.aitradingsystem.research.simulation;

import java.math.BigDecimal;
import java.time.Instant;

public record ScenarioSimulationRequest(
        long instrumentId,
        String interval,
        BigDecimal entryPrice,
        BigDecimal stopPrice,
        BigDecimal targetPrice,
        Instant historyFrom,
        Instant historyTo,
        int simulations,
        int horizonBars,
        int blockSize,
        long seed,
        int slippageBps,
        int roundTripCostBps) {

    public ScenarioSimulationRequest {
        if (instrumentId < 1) throw new IllegalArgumentException("instrumentId must be positive");
        if (interval == null || interval.isBlank()) throw new IllegalArgumentException("interval is required");
        if (entryPrice == null || stopPrice == null || targetPrice == null) throw new IllegalArgumentException("price levels are required");
        if (entryPrice.signum() <= 0 || stopPrice.signum() <= 0 || targetPrice.signum() <= 0) throw new IllegalArgumentException("price levels must be positive");
        if (stopPrice.compareTo(entryPrice) >= 0 || targetPrice.compareTo(entryPrice) <= 0) throw new IllegalArgumentException("long scenario requires stop < entry < target");
        if (historyFrom == null || historyTo == null || !historyFrom.isBefore(historyTo)) throw new IllegalArgumentException("valid history window is required");
        if (simulations < 100 || simulations > 100_000) throw new IllegalArgumentException("simulations must be between 100 and 100000");
        if (horizonBars < 1 || horizonBars > 500) throw new IllegalArgumentException("horizonBars must be between 1 and 500");
        if (blockSize < 1 || blockSize > 50 || blockSize > horizonBars) throw new IllegalArgumentException("blockSize must be between 1 and min(50, horizonBars)");
        if (slippageBps < 0 || slippageBps > 500) throw new IllegalArgumentException("slippageBps must be between 0 and 500");
        if (roundTripCostBps < 0 || roundTripCostBps > 500) throw new IllegalArgumentException("roundTripCostBps must be between 0 and 500");
    }
}
