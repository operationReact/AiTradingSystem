package in.aitradingsystem.news;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record NewsAiEvidence(
    String id,
    String newsId,
    String provider,
    String model,
    String providerRequestId,
    Instant sourceAvailableAt,
    Instant generatedAt,
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
    String evidence,
    List<String> risks,
    Integer inputTokens,
    Integer outputTokens,
    Integer totalTokens,
    int latencyMs
) {}
