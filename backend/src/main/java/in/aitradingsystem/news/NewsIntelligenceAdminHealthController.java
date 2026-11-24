package in.aitradingsystem.news;

import in.aitradingsystem.news.policy.NewsSourcePolicy.Use;
import in.aitradingsystem.news.policy.NewsSourcePolicyService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin-only source and processing-readiness dashboard data. This endpoint is
 * observational: no source calls, model calls, SSM reads or mutations. All
 * credentials, detailed errors and source article payloads stay server-side.
 */
@RestController
@RequestMapping("/api/admin/market-intelligence")
public class NewsIntelligenceAdminHealthController {
    private final NewsSourceHealthRepository sourceHealth;
    private final NewsSourcePolicyService policy;
    private final JdbcTemplate jdbc;
    private final Environment environment;

    public NewsIntelligenceAdminHealthController(
        NewsSourceHealthRepository sourceHealth, NewsSourcePolicyService policy,
        JdbcTemplate jdbc, Environment environment) {
        this.sourceHealth = sourceHealth;
        this.policy = policy;
        this.jdbc = jdbc;
        this.environment = environment;
    }

    @GetMapping("/health")
    @PreAuthorize("hasRole('ADMIN')")
    public Health health() {
        Instant now = Instant.now();
        Map<String, NewsSourceHealthRepository.SourceHealth> latest =
            sourceHealth.latest().stream().collect(Collectors.toMap(
                NewsSourceHealthRepository.SourceHealth::source, Function.identity(),
                (a, b) -> a.finishedAt().isAfter(b.finishedAt()) ? a : b));

        List<Source> sources = NewsSource.SUPPORTED.stream().map(source -> {
            var run = latest.get(source.id());
            var policyRow = policy.find(source.id());
            return new Source(
                source.id(), source.publisher(),
                policyRow.map(p -> p.state().name()).orElse("UNKNOWN"),
                policy.permitted(source.id(), Use.INGEST, now),
                policy.permitted(source.id(), Use.RETAIN, now),
                policy.permitted(source.id(), Use.DISPLAY, now),
                policy.permitted(source.id(), Use.NLP_TRANSFER, now),
                run == null ? "NEVER_RUN" : run.status(),
                run == null ? null : run.finishedAt(),
                run == null ? null
                    : Math.max(0L, Duration.between(run.finishedAt(), now).getSeconds()),
                run == null ? 0 : run.fetchedItems(),
                run == null ? 0 : run.insertedItems(),
                run == null ? null : run.errorCode());
        }).toList();

        String provider = environment.getProperty("app.news.provider", "NONE").trim().toUpperCase();
        String model = environment.getProperty("app.news.gemini-model", "");
        boolean credentialBound =
            environment.getProperty("app.news.gemini-api-key", "") != null
            && !environment.getProperty("app.news.gemini-api-key", "").isBlank();

        return new Health(
            now,
            bool("app.news.ingestion.enabled"),
            bool("app.news.event-ledger.enabled"),
            bool("app.news.analyzer-bridge.enabled"),
            bool("app.news.analysis-scheduler.enabled"),
            bool("app.news.mind-context.enabled"),
            new Model(provider, model, credentialBound, false),
            count("news_ingest_receipt"),
            count("news_canonical_event"),
            count("news_event_version"),
            sources);
    }

    private boolean bool(String key) {
        return Boolean.parseBoolean(environment.getProperty(key, "false"));
    }

    private long count(String knownTable) {
        // Table names are fixed constants supplied exclusively by this class.
        Long count = jdbc.queryForObject("SELECT count(*) FROM " + knownTable, Long.class);
        return count == null ? 0L : count;
    }

    public record Model(String selectedProvider, String configuredGeminiModel,
                        boolean geminiKeyConfigured, boolean realProviderCallVerified) {}

    public record Source(String sourceId, String publisher, String rightsState,
                         boolean canIngest, boolean canRetain, boolean canDisplay,
                         boolean canTransferToModel, String latestStatus,
                         Instant lastFinishedAt, Long secondsSinceLastRun,
                         int lastFetchedItems, int lastInsertedItems,
                         String lastErrorCode) {}

    public record Health(Instant observedAt, boolean ingestionEnabled,
                         boolean eventLedgerEnabled, boolean analyzerBridgeEnabled,
                         boolean analysisSchedulerEnabled, boolean mindContextEnabled,
                         Model model, long receiptCount, long eventCount,
                         long eventVersionCount, List<Source> sources) {}
}
