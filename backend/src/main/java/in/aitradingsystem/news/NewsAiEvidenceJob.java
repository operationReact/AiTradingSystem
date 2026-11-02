package in.aitradingsystem.news;

import in.aitradingsystem.domain.NewsItem;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Bounded scheduled AI-evidence generation. Disabled by default and never performs trading actions. */
@Component
@ConditionalOnProperty(name = "app.news.evidence.enabled", havingValue = "true")
public class NewsAiEvidenceJob {
    private static final Logger log = LoggerFactory.getLogger(NewsAiEvidenceJob.class);

    private final NewsRepository newsRepository;
    private final NewsResearchAiEvidenceService evidenceService;
    private final int maxAnalysesPerRun;

    public NewsAiEvidenceJob(
        NewsRepository newsRepository,
        NewsResearchAiEvidenceService evidenceService,
        @Value("${app.news.evidence.max-analyses-per-run:10}") int maxAnalysesPerRun) {
        this.newsRepository = newsRepository;
        this.evidenceService = evidenceService;
        this.maxAnalysesPerRun = Math.max(0, Math.min(maxAnalysesPerRun, 25));
    }

    @Scheduled(fixedDelayString = "#{T(java.lang.Math).min(86400000, T(java.lang.Math).max(60000, ${app.news.evidence.interval-ms:900000}))}")
    public void generate() {
        if (maxAnalysesPerRun == 0) return;
        int generated = 0;
        int candidateLimit = Math.min(200, Math.max(20, maxAnalysesPerRun * 10));
        List<NewsItem> candidates = newsRepository.recent(candidateLimit, 0);
        for (NewsItem item : candidates) {
            if (generated >= maxAnalysesPerRun) break;
            if (item.linkedSymbols() == null || item.linkedSymbols().isEmpty()) continue;
            try {
                if (evidenceService.analyzeIfNeeded(item)) generated++;
            } catch (Exception e) {
                log.warn("Research AI evidence generation failed for news item {}", item.id());
            }
        }
    }
}
