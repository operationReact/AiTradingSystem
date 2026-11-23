package in.aitradingsystem.news;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import in.aitradingsystem.news.NewsService.ArticleInput;
import in.aitradingsystem.news.NewsService.IngestRequest;

/**
 * Bridges approved RSS ingestion (news_item, written by NewsIngestionJob) into the analyzer store
 * (news_articles). It reuses the existing NewsService.ingest path, so URL-hash deduplication and
 * source identity are unchanged. It never fetches over the network and never calls an AI provider.
 *
 * <p>Fail-closed: nothing is published unless its source id is in the explicit approved list, and
 * the feature is off by default. The approved list is the rights gate; this code does not assert any
 * licence or redistribution right for a source.
 */
@Component
@ConditionalOnProperty(name = "app.news.analyzer-bridge.enabled", havingValue = "true")
public class NewsArticleBridge {
    private static final Logger log = LoggerFactory.getLogger(NewsArticleBridge.class);
    static final Duration FRESHNESS_WINDOW = Duration.ofDays(30);
    static final Duration FETCH_LOOKBACK = Duration.ofDays(2);
    static final Duration FUTURE_TOLERANCE = Duration.ofMinutes(5);
    static final int MAX_ROWS_PER_RUN = 500;

    public enum Outcome { INGESTED, DUPLICATE, SKIPPED_UNAPPROVED_SOURCE, SKIPPED_INVALID_URL,
        SKIPPED_STALE, SKIPPED_FUTURE_DATED, SKIPPED_MISSING_FIELDS, FAILED }

    private final JdbcClient jdbc;
    private final NewsService news;
    private final NewsSourceHealthRepository sourceHealth;
    private final Set<String> approvedSources;
    private final Clock clock;

    public NewsArticleBridge(JdbcClient jdbc, NewsService news, NewsSourceHealthRepository sourceHealth,
                             @Value("${app.news.analyzer-bridge.approved-sources:}") List<String> approvedSources,
                             Clock clock) {
        this.jdbc = jdbc;
        this.news = news;
        this.sourceHealth = sourceHealth;
        this.approvedSources = approvedSources == null ? Set.of()
            : approvedSources.stream().map(s -> s.trim()).filter(s -> !s.isEmpty()).collect(java.util.stream.Collectors.toUnmodifiableSet());
        this.clock = clock;
    }

    /** Pure, side-effect-free gate for one candidate row. Public so it can be tested without a database. */
    public static Outcome gate(String source, String sourceUrl, String title, Instant publishedAt,
                               Set<String> approvedSources, Instant now) {
        if (source == null || sourceUrl == null || title == null || title.isBlank() || publishedAt == null) {
            return Outcome.SKIPPED_MISSING_FIELDS;
        }
        if (!approvedSources.contains(source)) return Outcome.SKIPPED_UNAPPROVED_SOURCE;
        if (!validHttpUrl(sourceUrl)) return Outcome.SKIPPED_INVALID_URL;
        if (publishedAt.isAfter(now.plus(FUTURE_TOLERANCE))) return Outcome.SKIPPED_FUTURE_DATED;
        if (publishedAt.isBefore(now.minus(FRESHNESS_WINDOW))) return Outcome.SKIPPED_STALE;
        return null; // eligible
    }

    static boolean validHttpUrl(String raw) {
        try {
            URI u = new URI(raw.trim());
            String scheme = u.getScheme();
            return ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme)) && u.getHost() != null;
        } catch (Exception e) {
            return false;
        }
    }

    /** Runs one bounded pass. Returns outcome counts; never throws to the scheduler. */
    public java.util.Map<Outcome, Integer> runOnce() {
        Instant now = clock.instant();
        java.util.Map<Outcome, Integer> counts = new java.util.EnumMap<>(Outcome.class);
        if (approvedSources.isEmpty()) return counts;
        List<Row> rows = jdbc.sql("""
                SELECT n.source, n.source_url, n.title, n.published_at
                  FROM news_item n
                 WHERE n.fetched_at >= :since
                   AND n.source IN (:sources)
                 ORDER BY n.fetched_at DESC
                 LIMIT :limit
                """)
            .param("since", java.sql.Timestamp.from(now.minus(FETCH_LOOKBACK)))
            .param("sources", approvedSources)
            .param("limit", MAX_ROWS_PER_RUN)
            .query((rs, n) -> new Row(rs.getString("source"), rs.getString("source_url"),
                rs.getString("title"), rs.getTimestamp("published_at") == null ? null
                    : rs.getTimestamp("published_at").toInstant()))
            .list();

        Instant startedAt = now;
        for (String source : approvedSources) {
            List<ArticleInput> eligible = new ArrayList<>();
            int fetched = 0;
            for (Row r : rows) {
                if (!source.equals(r.source())) continue;
                fetched++;
                Outcome gate = gate(r.source(), r.sourceUrl(), r.title(), r.publishedAt(), approvedSources, now);
                if (gate != null) {
                    counts.merge(gate, 1, Integer::sum);
                    continue;
                }
                // Provenance kept: source id, canonical source URL, publication time. Excerpt is not
                // copied from the RSS body here; the analyzer reads title and the stored excerpt only.
                eligible.add(new ArticleInput(r.source(), null, r.title().trim(), r.sourceUrl().trim(),
                    null, r.publishedAt()));
            }
            int inserted = 0;
            try {
                // One article per ingest call: a single bad row fails only itself, never the source batch.
                // NewsService.ingest deduplicates on the normalized URL hash.
                for (ArticleInput article : eligible) {
                    try {
                        var result = news.ingest(new IngestRequest(List.of(article)));
                        inserted += result.inserted();
                        counts.merge(Outcome.INGESTED, result.inserted(), Integer::sum);
                        counts.merge(Outcome.DUPLICATE, result.duplicates(), Integer::sum);
                    } catch (RuntimeException e) {
                        counts.merge(Outcome.FAILED, 1, Integer::sum);
                    }
                }
                sourceHealth.record(source, startedAt, clock.instant(), "SUCCESS", fetched, inserted, 0, null, null);
            } catch (RuntimeException e) {
                counts.merge(Outcome.FAILED, eligible.size(), Integer::sum);
                log.warn("News analyzer bridge failed for approved source {}", source);
                sourceHealth.record(source, startedAt, clock.instant(), "FAILED", fetched, 0, 0,
                    e.getClass().getSimpleName(), "Bridge publish failed");
            }
        }
        return counts;
    }

    @Scheduled(fixedDelayString = "#{T(java.lang.Math).min(86400000, T(java.lang.Math).max(60000, ${app.news.analyzer-bridge.interval-ms:900000}))}")
    public void scheduledRun() {
        try {
            runOnce();
        } catch (RuntimeException e) {
            log.warn("News analyzer bridge run failed");
        }
    }

    record Row(String source, String sourceUrl, String title, Instant publishedAt) {}
}
