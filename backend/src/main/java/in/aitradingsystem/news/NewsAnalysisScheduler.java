package in.aitradingsystem.news;

import in.aitradingsystem.news.analysis.AiErrorCode;
import in.aitradingsystem.news.analysis.AiProviderException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Schedules the existing NewsAnalysisService over articles that have no analysis yet, or whose latest
 * analysis failed and is eligible for a bounded retry. It adds no provider logic of its own.
 *
 * <p>Only articles from the explicit, empty-by-default approved-source list are eligible.
 * The scheduler never analyzes operator-ingested or legacy articles from unapproved sources.
 *
 * <p>Retry policy: a FAILED analysis is retried only after {@link #RETRY_BACKOFF}, and an article is
 * attempted at most {@link #MAX_FAILED_ATTEMPTS} times in total. With no server-side credential the
 * run stops at the first call and reports CONFIGURATION_BLOCKED; nothing is fabricated.
 */
@Component
@ConditionalOnProperty(name = "app.news.analysis-scheduler.enabled", havingValue = "true")
public class NewsAnalysisScheduler {
    private static final Logger log = LoggerFactory.getLogger(NewsAnalysisScheduler.class);
    static final Duration RETRY_BACKOFF = Duration.ofHours(1);
    static final int MAX_FAILED_ATTEMPTS = 3;
    static final Duration LOOKBACK = Duration.ofDays(30);
    static final int MAX_PER_RUN = 20;

    public enum Outcome { ANALYZED, FAILED, CONFIGURATION_BLOCKED, SOURCE_APPROVAL_BLOCKED, RIGHTS_BLOCKED, NOTHING_ELIGIBLE }

    private final JdbcClient jdbc;
    private final NewsAnalysisService analysis;
    private final Clock clock;
    private final int maxPerRun;
    private final Set<String> approvedSources;
    private final in.aitradingsystem.news.policy.NewsSourcePolicyService policy;

    public NewsAnalysisScheduler(JdbcClient jdbc, NewsAnalysisService analysis, Clock clock,
                                 @Value("${app.news.analysis-scheduler.max-per-run:20}") int maxPerRun,
                                 @Value("${app.news.analyzer-bridge.approved-sources:}") List<String> approvedSources,
                                 in.aitradingsystem.news.policy.NewsSourcePolicyService policy) {
        this.jdbc = jdbc;
        this.analysis = analysis;
        this.clock = clock;
        this.policy = policy;
        this.maxPerRun = Math.max(1, Math.min(maxPerRun, MAX_PER_RUN));
        this.approvedSources = approvedSources == null ? Set.of()
            : approvedSources.stream().filter(s -> s != null && !s.isBlank())
                .map(String::trim).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** Approved sources whose stored policy permits NLP transfer at this instant. Default-deny. */
    List<String> permittedSources(Instant now) {
        return approvedSources.stream()
            .filter(s -> policy.permitted(s, in.aitradingsystem.news.policy.NewsSourcePolicy.Use.NLP_TRANSFER, now))
            .sorted()
            .toList();
    }

    /** Articles needing analysis now, oldest-published first within the freshness window. */
    List<UUID> eligibleArticles(Instant now) {
        List<String> permitted = permittedSources(now);
        if (permitted.isEmpty()) return List.of();
        return jdbc.sql("""
                SELECT a.id
                  FROM news_articles a
                 WHERE a.source IN (:approvedSources)
                   AND a.published_at > :lookbackStart
                   AND (
                         NOT EXISTS (SELECT 1 FROM news_analyses x WHERE x.article_id = a.id)
                      OR (
                           (SELECT x.status FROM news_analyses x WHERE x.article_id = a.id
                             ORDER BY x.created_at DESC, x.id DESC LIMIT 1) = 'FAILED'
                       AND (SELECT max(x.created_at) FROM news_analyses x WHERE x.article_id = a.id) < :retryBefore
                       AND (SELECT count(*) FROM news_analyses x WHERE x.article_id = a.id AND x.status = 'FAILED') < :maxFailed
                      )
                   )
                 ORDER BY a.published_at DESC
                 LIMIT :limit
                """)
            .param("approvedSources", permitted)
            .param("lookbackStart", OffsetDateTime.ofInstant(now.minus(LOOKBACK), ZoneOffset.UTC))
            .param("retryBefore", OffsetDateTime.ofInstant(now.minus(RETRY_BACKOFF), ZoneOffset.UTC))
            .param("maxFailed", MAX_FAILED_ATTEMPTS)
            .param("limit", maxPerRun)
            .query(UUID.class)
            .list();
    }

    /** One bounded pass. Stops immediately when no provider is configured. */
    public Map<Outcome, Integer> runOnce() {
        Instant now = clock.instant();
        Map<Outcome, Integer> counts = new EnumMap<>(Outcome.class);
        if (approvedSources.isEmpty()) {
            counts.put(Outcome.SOURCE_APPROVAL_BLOCKED, 1);
            return counts;
        }
        // Rights gate (MI-PR02): article text may reach an external model only from a source whose stored policy
        // permits NLP_TRANSFER now. No permitted source means nothing is sent, and the run says so.
        if (permittedSources(now).isEmpty()) {
            counts.put(Outcome.RIGHTS_BLOCKED, 1);
            return counts;
        }
        List<UUID> ids = eligibleArticles(now);
        if (ids.isEmpty()) {
            counts.put(Outcome.NOTHING_ELIGIBLE, 1);
            return counts;
        }
        for (UUID id : ids) {
            try {
                NewsService.AnalysisView view = analysis.analyze(id, false);
                counts.merge("ANALYZED".equals(view.status()) ? Outcome.ANALYZED : Outcome.FAILED, 1, Integer::sum);
            } catch (AiProviderException e) {
                if (e.code() == AiErrorCode.UNCONFIGURED) {
                    counts.merge(Outcome.CONFIGURATION_BLOCKED, 1, Integer::sum);
                    log.warn("News analysis not configured; scheduled analysis stopped without calls");
                    break;
                }
                counts.merge(Outcome.FAILED, 1, Integer::sum);
            } catch (NewsAnalysisService.AnalysisInProgressException e) {
                // Another request is analyzing this article; skip without counting a failure.
            } catch (RuntimeException e) {
                counts.merge(Outcome.FAILED, 1, Integer::sum);
                log.warn("News analysis failed unexpectedly for one article");
            }
        }
        return counts;
    }

    @Scheduled(fixedDelayString = "#{T(java.lang.Math).min(86400000, T(java.lang.Math).max(60000, ${app.news.analysis-scheduler.interval-ms:900000}))}")
    public void scheduledRun() {
        try {
            runOnce();
        } catch (RuntimeException e) {
            log.warn("News analysis scheduler run failed");
        }
    }
}
