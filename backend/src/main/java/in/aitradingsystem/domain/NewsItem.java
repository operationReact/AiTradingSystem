package in.aitradingsystem.domain;

import java.time.Instant;
import java.util.List;

public record NewsItem(
    String id,
    String source,
    String publisher,
    String sourceUrl,
    Instant publishedAt,
    Instant fetchedAt,
    String title,
    String normalizedContent,
    String contentHash,
    List<String> linkedSymbols,
    String ingestionStatus,
    String summary,
    String summaryProvider,
    String summaryModel,
    Instant summaryGeneratedAt
) {}
