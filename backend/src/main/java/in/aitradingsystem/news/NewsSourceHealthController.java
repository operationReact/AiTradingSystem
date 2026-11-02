package in.aitradingsystem.news;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/news/source-health")
public class NewsSourceHealthController {
    private final NewsSourceHealthRepository repository;

    public NewsSourceHealthController(NewsSourceHealthRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<SourceHealthView> health() {
        Instant now = Instant.now();
        var latestBySource = repository.latest().stream().collect(
            java.util.stream.Collectors.toMap(
                NewsSourceHealthRepository.SourceHealth::source,
                value -> value));
        return NewsSource.SUPPORTED.stream()
            .map(source -> {
                var latest = latestBySource.get(source.id());
                return new SourceHealthView(
                    source.id(),
                    source.publisher(),
                    source.feedUri().getHost(),
                    latest == null ? "NEVER_RUN" : latest.status(),
                    latest == null ? null : latest.startedAt(),
                    latest == null ? null : latest.finishedAt(),
                    latest == null ? null : Math.max(0, Duration.between(latest.finishedAt(), now).toSeconds()),
                    latest == null ? 0 : latest.fetchedItems(),
                    latest == null ? 0 : latest.insertedItems(),
                    latest == null ? 0 : latest.summarizedItems(),
                    latest == null ? null : latest.errorCode(),
                    latest == null ? null : latest.errorDetail());
            })
            .toList();
    }

    public record SourceHealthView(
        String source,
        String publisher,
        String feedHost,
        String status,
        Instant startedAt,
        Instant finishedAt,
        Long ageSeconds,
        int fetchedItems,
        int insertedItems,
        int summarizedItems,
        String errorCode,
        String errorDetail) {}
}
