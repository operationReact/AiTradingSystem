package in.aitradingsystem.news;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record NewsAiEvidenceExport(
    String contractVersion,
    Instant generatedAt,
    Instant fromInclusive,
    Instant toExclusive,
    Instant dataCutoff,
    List<String> providerSources,
    List<ResearchAiEvidence> evidence
) {
    public record ResearchAiEvidence(
        String analysisId,
        String sourceId,
        String provider,
        String model,
        String providerRequestId,
        Instant sourceAvailableAt,
        Instant evidenceAvailableAt,
        String promptHash,
        String requestFingerprint,
        String responseFingerprint,
        String sentiment,
        BigDecimal directionScore,
        String category,
        BigDecimal confidence,
        BigDecimal materiality,
        String horizon,
        BigDecimal novelty,
        boolean contradiction,
        boolean ambiguity,
        List<String> impactedSymbols,
        Integer inputTokens,
        Integer outputTokens,
        Integer totalTokens
    ) {}
}
