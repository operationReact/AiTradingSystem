package in.aitradingsystem.strategy;

import java.time.Instant;
import java.util.List;

public record StrategyEvaluation(
        long instrumentId,
        String symbol,
        String exchange,
        String interval,
        int requiredCandles,
        int availableCandles,
        Instant latestCandleAt,
        StrategySignal signal,
        List<String> reasonCodes,
        Instant evaluatedAt,
        String dataSource) {}
