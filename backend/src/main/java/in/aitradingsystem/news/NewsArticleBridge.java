package in.aitradingsystem.news;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import in.aitradingsystem.news.policy.NewsSourcePolicy.Use;
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
    static final int MAX_EXCERPT_CHARS = 2000; // NewsService.ArticleInput contract.

    public enum Outcome { INGESTED, DUPLICATE, SKIPPED_UNAPPROVED_SOURCE, SKIPPED_INVALID_URL,
        SKIPPED_STALE, SKIPPED_FUTURE_DATED, SKIPPED_MISSING_FIELDS, SKIPPED_RIGHTS_NOT_PERMITTED, FAILED }

    private final JdbcClient jdbc;
    private final NewsService news;
    private final NewsSourceHealthRepository sourceHealth;
    private final in.aitradingsystem.news.policy.NewsSourcePolicyService policy;
    private final Set<String> approvedSources;
    private final Clock clock;

    public NewsArticleBridge(JdbcClient jdbc, NewsService news, NewsSourceHealthRepository sourceHealth,
                             in.aitradingsystem.news.policy.NewsSourcePolicyService policy,
                             @Value("${app.news.analyzer-bridge.approved-sources:}") List<String> approvedSources,
                             Clock clock) {
        this.jdbc = jdbc;
        this.news = news;
        this.sourceHealth = sourceHealth;
        this.policy = policy;
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
                SELECT n.source, n.source_url, n.title, n.published_at, n.normalized_content
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
                    : rs.getTimestamp("published_at").toInstant(), rs.getString("normalized_content"))
            .list();

        Instant startedAt = now;
        for (String source : approvedSources) {
            // Rights gate (MI-PR02): default-deny unless the stored policy permits ingest for this source now.
            var rights = policy.decide(source, Use.INGEST, now);
            var retentionRights = policy.decide(source, Use.RETAIN, now);
            if (!rights.permitted() || !retentionRights.permitted()) {
                long denied = rows.stream().filter(r -> source.equals(r.source())).count();
                if (denied > 0) counts.merge(Outcome.SKIPPED_RIGHTS_NOT_PERMITTED, (int) denied, Integer::sum);
                sourceHealth.record(source, startedAt, clock.instant(), "FAILED", (int) denied, 0, 0,
                    "RIGHTS_NOT_PERMITTED", !rights.permitted() ? rights.name() : retentionRights.name());
                continue;
            }
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
                // Preserve only the authorized, bounded RSS description. No article URL is fetched
                // here. NewsAnalysisScheduler separately requires NLP_TRANSFER before provider calls.
                eligible.add(new ArticleInput(r.source(), null, r.title().trim(), r.sourceUrl().trim(),
                    excerpt(r.normalizedContent()), r.publishedAt()));
            }
            int inserted = 0;
            int rowFailures = 0;
            boolean sourceFailed = false;
            try {
                // One article per ingest call: a single bad row fails only itself, never the source batch.
                // NewsService.ingest deduplicates on the normalized URL hash.
                for (ArticleInput article : eligible) {
                    try {
                        // A source can be revoked while the batch is being processed.
                        Instant at = clock.instant();
                        if (!policy.permitted(source, Use.INGEST, at)
                            || !policy.permitted(source, Use.RETAIN, at)) {
                            rowFailures++;
                            counts.merge(Outcome.SKIPPED_RIGHTS_NOT_PERMITTED, 1, Integer::sum);
                            continue;
                        }
                        var result = news.ingest(new IngestRequest(List.of(article)));
                        inserted += result.inserted();
                        counts.merge(Outcome.INGESTED, result.inserted(), Integer::sum);
                        counts.merge(Outcome.DUPLICATE, result.duplicates(), Integer::sum);
                    } catch (RuntimeException e) {
                        rowFailures++;
                        counts.merge(Outcome.FAILED, 1, Integer::sum);
                    }
                }
            } catch (RuntimeException e) {
                sourceFailed = true;
                counts.merge(Outcome.FAILED, eligible.size() - rowFailures, Integer::sum);
                log.warn("News analyzer bridge failed for approved source {}", source);
            }
            // Health is truthful: SUCCESS only when every eligible row published, PARTIAL when some rows
            // failed, FAILED only for a source-level error. Recorded outside the row loop, so a recording
            // error is never counted as a row failure.
            if (sourceFailed) {
                sourceHealth.record(source, startedAt, clock.instant(), "FAILED", fetched, inserted, 0,
                    "BRIDGE_SOURCE_ERROR", "Bridge publish failed before every row was attempted");
            } else if (rowFailures > 0) {
                sourceHealth.record(source, startedAt, clock.instant(), "PARTIAL", fetched, inserted, 0,
                    "ROW_INGEST_FAILED", rowFailures + " of " + eligible.size() + " eligible rows failed to publish");
            } else {
                sourceHealth.record(source, startedAt, clock.instant(), "SUCCESS", fetched, inserted, 0, null, null);
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

    /** Description provided by the licensed feed: no full-text scraping or unbounded model inputs. */
    static String excerpt(String value) {
        String normalized = NewsText.normalize(value, MAX_EXCERPT_CHARS);
        return normalized.isBlank() ? null : normalized;
    }

    record Row(String source, String sourceUrl, String title, Instant publishedAt, String normalizedContent) {}
}
