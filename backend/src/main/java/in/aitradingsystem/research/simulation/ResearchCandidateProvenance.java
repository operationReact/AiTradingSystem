package in.aitradingsystem.research.simulation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ResearchCandidateProvenance(
        long id,
        UUID scanRunId,
        long instrumentId,
        String tradingSymbol,
        String exchange,
        String interval,
        String strategy,
        Integer strategyConfigVersion,
        UUID catalogueVersion,
        String candidateFingerprint,
        Instant signalCandleTime,
        Instant dataCutoffAt,
        BigDecimal entryPrice,
        BigDecimal stopPrice,
        BigDecimal targetPrice) {

    public ResearchCandidateProvenance {
        if (id < 1 || instrumentId < 1) throw new IllegalArgumentException("candidate identifiers must be positive");
        if (scanRunId == null) throw new IllegalArgumentException("scanRunId is required");
        if (interval == null || interval.isBlank()) throw new IllegalArgumentException("interval is required");
        if (strategy == null || strategy.isBlank()) throw new IllegalArgumentException("strategy is required");
        if (signalCandleTime == null || dataCutoffAt == null) throw new IllegalArgumentException("candidate time provenance is required");
        if (signalCandleTime.isAfter(dataCutoffAt)) throw new IllegalArgumentException("candidate candle cannot be after data cutoff");
        if (entryPrice == null || stopPrice == null || targetPrice == null) throw new IllegalArgumentException("candidate prices are required");
    }
}
