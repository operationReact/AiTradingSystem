package in.aitradingsystem.news;

import java.time.Instant;
import java.util.List;

public record NewsResearchExport(
    String contractVersion,
    Instant generatedAt,
    Instant fromInclusive,
    Instant toExclusive,
    Instant dataCutoff,
    List<String> providerSources,
    List<ResearchEvent> events
) {
    public record ResearchEvent(
        String sourceId,
        String source,
        String publisher,
        String sourceUrl,
        Instant publishedAt,
        Instant availableAt,
        String contentHash,
        List<String> linkedSymbols
    ) {}
}
