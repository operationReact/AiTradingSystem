package in.aitradingsystem.news;

import java.sql.Timestamp;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.news.ingestion.enabled", havingValue = "true")
public class NewsIngestionJob {
    private static final Logger log = LoggerFactory.getLogger(NewsIngestionJob.class);
    private final RssFeedClient feedClient;
    private final RssFeedParser parser;
    private final NewsRepository repository;
    private final GeminiNewsSummarizer summarizer;
    private final List<String> sourceIds;
    private final List<String> watchlist;
    private final int maxPayloadBytes;
    private final int maxItemsPerSource;
    private final int maxArticlesPerRun;
    private final int maxSummariesPerRun;
    private final int retentionDays;

    public NewsIngestionJob(RssFeedClient feedClient, RssFeedParser parser, NewsRepository repository,
        GeminiNewsSummarizer summarizer,
        @Value("${app.news.ingestion.sources:}") List<String> sourceIds,
        @Value("${app.news.ingestion.watchlist:}") List<String> watchlist,
        @Value("${app.news.ingestion.max-payload-bytes:1048576}") int maxPayloadBytes,
        @Value("${app.news.ingestion.max-items-per-source:50}") int maxItemsPerSource,
        @Value("${app.news.ingestion.max-articles-per-run:100}") int maxArticlesPerRun,
        @Value("${app.news.ingestion.max-summaries-per-run:20}") int maxSummariesPerRun,
        @Value("${app.news.retention-days:90}") int retentionDays) {
        this.feedClient = feedClient;
        this.parser = parser;
        this.repository = repository;
        this.summarizer = summarizer;
        this.sourceIds = sourceIds;
        this.watchlist = watchlist;
        this.maxPayloadBytes = Math.max(1024, Math.min(maxPayloadBytes, 2_097_152));
        this.maxItemsPerSource = Math.max(1, Math.min(maxItemsPerSource, 100));
        this.maxArticlesPerRun = Math.max(1, Math.min(maxArticlesPerRun, 200));
        this.maxSummariesPerRun = Math.max(0, Math.min(maxSummariesPerRun, 50));
        this.retentionDays = Math.max(1, Math.min(retentionDays, 365));
    }

    @Scheduled(fixedDelayString = "#{T(java.lang.Math).min(86400000, T(java.lang.Math).max(60000, ${app.news.ingestion.interval-ms:900000}))}")
    public void ingest() {
        try {
            repository.deleteExpired(retentionDays);
        } catch (Exception e) {
            log.warn("News retention cleanup failed; continuing ingestion");
        }
        int inserted = 0;
        int summaries = 0;
        for (String id : sourceIds.stream().limit(NewsSource.SUPPORTED.size()).toList()) {
            if (inserted >= maxArticlesPerRun) break;
            try {
                NewsSource source = NewsSource.byId(id);
                byte[] body = feedClient.fetch(source, maxPayloadBytes);
                List<NewsArticle> articles = parser.parse(body, source, watchlist,
                    Math.min(maxItemsPerSource, maxArticlesPerRun - inserted));
                for (NewsArticle article : articles) {
                    var newId = repository.insert(article);
                    if (newId.isEmpty()) continue;
                    inserted++;
                    if (summaries < maxSummariesPerRun) {
                        try {
                            var summary = summarizer.summarize(article);
                            if (summary.isPresent()) {
                                var generated = summary.get();
                                repository.saveSummary(newId.get(), generated.provider(), generated.model(),
                                    generated.text(), Timestamp.from(generated.generatedAt()));
                                summaries++;
                            }
                        } catch (Exception e) {
                            log.warn("Optional news summarization failed for source {}", source.id());
                        }
                    }
                    if (inserted >= maxArticlesPerRun) break;
                }
            } catch (Exception e) {
                log.warn("News ingestion failed for source {}", id);
            }
        }
    }
}
