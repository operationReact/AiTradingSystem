package in.aitradingsystem.research.simulation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ScenarioRunView(
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
        String regime,
        int historicalSamples,
        int eligibleBlockCount,
        String fallbackTier,
        int targetFirstCount,
        int stopFirstCount,
        int neitherCount,
        BigDecimal targetFirstProbability,
        BigDecimal stopFirstProbability,
        BigDecimal neitherProbability,
        BigDecimal expectedR,
        BigDecimal p05R,
        BigDecimal p50R,
        BigDecimal p95R,
        ScenarioRunStatus status,
        String errorCode,
        String errorMessage,
        Instant startedAt,
        Instant completedAt,
        Instant createdAt) {

    public ScenarioRunView {
        if (id == null || instrumentId < 1 || interval == null || interval.isBlank()
                || historyFrom == null || historyTo == null || dataCutoffAt == null
                || methodology == null || methodology.isBlank() || regime == null || regime.isBlank()
                || status == null || createdAt == null) {
            throw new IllegalArgumentException("scenario run identity, provenance and lifecycle fields are required");
        }
    }
}
