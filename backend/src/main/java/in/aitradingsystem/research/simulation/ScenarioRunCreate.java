package in.aitradingsystem.research.simulation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ScenarioRunCreate(
        UUID id,
        UUID scanRunId,
        Long candidateId,
        long instrumentId,
        String exchange,
        String tradingSymbol,
        String interval,
        String strategy,
        Integer strategyConfigVersion,
        BigDecimal entryPrice,
        BigDecimal stopPrice,
        BigDecimal targetPrice,
        Instant historyFrom,
        Instant historyTo,
        Instant dataCutoffAt,
        int simulationCount,
        int horizonBars,
        int blockSize,
        long seed,
        int slippageBps,
        int roundTripCostBps,
        String requestFingerprint,
        String methodology,
        String regime) {

    public ScenarioRunCreate {
        if (id == null) throw new IllegalArgumentException("id is required");
        if (instrumentId < 1) throw new IllegalArgumentException("instrumentId must be positive");
        if (interval == null || interval.isBlank()) throw new IllegalArgumentException("interval is required");
        if (entryPrice == null || stopPrice == null || targetPrice == null) throw new IllegalArgumentException("price levels are required");
        if (historyFrom == null || historyTo == null || dataCutoffAt == null) throw new IllegalArgumentException("history provenance is required");
        if (!historyFrom.isBefore(historyTo) || dataCutoffAt.isBefore(historyTo)) throw new IllegalArgumentException("history must be bounded by data cutoff");
        if (simulationCount < 1 || horizonBars < 1 || blockSize < 1) throw new IllegalArgumentException("simulation parameters must be positive");
        if (requestFingerprint != null && !requestFingerprint.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("requestFingerprint must be a lowercase SHA-256 hex string");
        if (methodology == null || methodology.isBlank()) throw new IllegalArgumentException("methodology is required");
        if (regime == null || regime.isBlank()) throw new IllegalArgumentException("regime is required");
    }

    /** Compatibility constructor for direct scenario runs that do not participate in orchestration reuse. */
    public ScenarioRunCreate(
            UUID id, UUID scanRunId, Long candidateId, long instrumentId, String exchange, String tradingSymbol,
            String interval, String strategy, Integer strategyConfigVersion, BigDecimal entryPrice,
            BigDecimal stopPrice, BigDecimal targetPrice, Instant historyFrom, Instant historyTo,
            Instant dataCutoffAt, int simulationCount, int horizonBars, int blockSize, long seed,
            int slippageBps, int roundTripCostBps, String methodology, String regime) {
        this(id, scanRunId, candidateId, instrumentId, exchange, tradingSymbol, interval, strategy,
                strategyConfigVersion, entryPrice, stopPrice, targetPrice, historyFrom, historyTo,
                dataCutoffAt, simulationCount, horizonBars, blockSize, seed, slippageBps, roundTripCostBps,
                null, methodology, regime);
    }
}
