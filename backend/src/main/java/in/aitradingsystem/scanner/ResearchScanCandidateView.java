package in.aitradingsystem.scanner;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ResearchScanCandidateView(
        UUID scanRunId,
        long instrumentId,
        String tradingSymbol,
        String exchange,
        String interval,
        String strategy,
        double score,
        BigDecimal referencePrice,
        BigDecimal suggestedStop,
        BigDecimal suggestedTarget,
        Instant candleTime,
        List<String> reasons,
        String candidateFingerprint,
        Integer strategyConfigVersion,
        UUID catalogueVersion,
        Instant dataCutoffAt,
        long candidateId) {}
