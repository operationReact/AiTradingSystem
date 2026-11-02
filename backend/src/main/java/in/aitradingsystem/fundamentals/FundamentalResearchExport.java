package in.aitradingsystem.fundamentals;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record FundamentalResearchExport(
    String contractVersion,
    Instant generatedAt,
    Instant fromInclusive,
    Instant toExclusive,
    Instant dataCutoff,
    List<String> providerSources,
    List<ResearchSnapshot> snapshots
) {
    public record ResearchSnapshot(
        String sourceId,
        String symbol,
        String provider,
        String providerRecordId,
        Instant periodEnd,
        Instant availableAt,
        Map<String, Double> metrics,
        String contentHash
    ) {}
}
