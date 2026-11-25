package in.aitradingsystem.news;

import java.time.Instant;
import java.util.List;

public record NewsArticle(String source, String publisher, String sourceUrl, Instant publishedAt,
                          String title, String normalizedContent, String contentHash,
                          List<String> linkedSymbols) {}
