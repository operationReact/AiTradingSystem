package in.aitradingsystem.news;

import in.aitradingsystem.domain.NewsItem;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class NewsResearchExportService {
    static final String CONTRACT_VERSION = "news-events.v1";
    private static final int MAX_ROWS = 20_000;

    private final NewsRepository repository;

    public NewsResearchExportService(NewsRepository repository) {
        this.repository = repository;
    }

    public NewsResearchExport export(Instant fromInclusive, Instant toExclusive, int limit) {
        if (fromInclusive == null || toExclusive == null || !fromInclusive.isBefore(toExclusive)) {
            throw new IllegalArgumentException("fromInclusive must be before toExclusive");
        }
        if (limit < 1 || limit > MAX_ROWS) {
            throw new IllegalArgumentException("Research event limit must be between 1 and " + MAX_ROWS);
        }

        List<NewsItem> rows = repository.researchEvents(fromInclusive, toExclusive, limit);
        List<NewsResearchExport.ResearchEvent> events = rows.stream()
            .map(item -> new NewsResearchExport.ResearchEvent(
                item.id(),
                item.source(),
                item.publisher(),
                item.sourceUrl(),
                item.publishedAt(),
                item.fetchedAt(),
                item.contentHash(),
                item.linkedSymbols().stream().sorted().toList()))
            .toList();
        Instant dataCutoff = events.stream()
            .map(NewsResearchExport.ResearchEvent::availableAt)
            .max(Comparator.naturalOrder())
            .orElse(null);
        List<String> sources = events.stream()
            .map(NewsResearchExport.ResearchEvent::source)
            .filter(Objects::nonNull)
            .distinct()
            .sorted()
            .toList();

        return new NewsResearchExport(
            CONTRACT_VERSION,
            Instant.now(),
            fromInclusive,
            toExclusive,
            dataCutoff,
            sources,
            events);
    }
}
