package in.aitradingsystem.news;

import in.aitradingsystem.news.policy.NewsSourcePolicy.Use;
import in.aitradingsystem.config.NewsProperties;
import in.aitradingsystem.news.analysis.NewsAnalysisProvider;
import in.aitradingsystem.news.analysis.GeminiNewsAnalysisProvider;
import in.aitradingsystem.news.policy.NewsSourcePolicyService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
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
    private final NewsProperties newsProperties;
    private final List<NewsAnalysisProvider> providers;

    public NewsIntelligenceAdminHealthController(
        NewsSourceHealthRepository sourceHealth, NewsSourcePolicyService policy,
        JdbcTemplate jdbc, Environment environment, NewsProperties newsProperties,
        List<NewsAnalysisProvider> providers) {
        this.sourceHealth = sourceHealth;
        this.policy = policy;
        this.jdbc = jdbc;
        this.environment = environment;
        this.newsProperties = newsProperties;
        this.providers = List.copyOf(providers);
    }

    @GetMapping("/health")
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

        // Use the same bound properties and provider implementations as NewsAnalysisService.
        String provider = newsProperties.provider();
        String model = newsProperties.geminiModel();
        var gemini = providers.stream().filter(p -> "GEMINI".equals(p.name())).findFirst();
        boolean credentialBound = gemini.map(NewsAnalysisProvider::configured).orElse(false);
        var selected = providers.stream().filter(p -> provider.equals(p.name())).findFirst();
        boolean selectedReady = selected.map(NewsAnalysisProvider::configured).orElse(false);

        // A persisted, schema-validated analysis receipt is evidence of recorded processing,
        // not independent proof that the remote service was contacted in this deployment.
        Long geminiReceipts = jdbc.queryForObject("""
            SELECT count(*) FROM news_analyses
             WHERE provider = 'GEMINI' AND model = ? AND status = 'ANALYZED'
               AND provider_request_id IS NOT NULL AND btrim(provider_request_id) <> ''
               AND response_fingerprint ~ '^[a-f0-9]{64}$'
               AND request_fingerprint ~ '^[a-f0-9]{64}$'
               AND prompt_hash ~ '^[a-f0-9]{64}$'
               AND source_available_at <= created_at AND created_at <= CURRENT_TIMESTAMP
            """, Long.class, model);
        String readiness = !"GEMINI".equals(provider) ? "GEMINI_NOT_SELECTED"
            : !selectedReady ? "GEMINI_NOT_CONFIGURED"
            : geminiReceipts != null && geminiReceipts > 0
                ? "RECORDED_ANALYSIS_RECEIPTS_PRESENT" : "CONFIGURED_NO_RECORDED_RECEIPT";

        Map<String, Instant> latestSuccessfulAnalysisBySource = jdbc.query("""
            SELECT n.source, max(a.created_at) AS analyzed_at
              FROM news_analyses a
              JOIN news_articles n ON n.id = a.article_id
             WHERE a.provider = 'GEMINI' AND a.model = ? AND a.status = 'ANALYZED'
             GROUP BY n.source
            """, (rs, row) -> Map.entry(rs.getString("source"),
                rs.getObject("analyzed_at", java.time.OffsetDateTime.class).toInstant()), model)
            .stream().collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));

        // Process-local transport observations are separate from persisted analysis
        // receipts and independent deployment certification. No probe is triggered here.
        var observation = gemini.filter(GeminiNewsAnalysisProvider.class::isInstance)
            .map(GeminiNewsAnalysisProvider.class::cast)
            .map(GeminiNewsAnalysisProvider::latestObservation)
            .filter(o -> !o.finishedAt().isAfter(now))
            .orElse(null);

        return new Health(
            now,
            bool("app.news.ingestion.enabled"),
            bool("app.news.event-ledger.enabled"),
            bool("app.news.analyzer-bridge.enabled"),
            bool("app.news.analysis-scheduler.enabled"),
            bool("app.news.mind-context.enabled"),
            new Model(provider, model, credentialBound, false, selectedReady,
                geminiReceipts == null ? 0L : geminiReceipts, readiness,
                observation == null ? null : observation.finishedAt(),
                observation == null ? "NO_OBSERVATION" : observation.outcome(),
                latestSuccessfulAnalysisBySource),
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
                        boolean geminiKeyConfigured, boolean realProviderCallVerified,
                        boolean selectedProviderConfigured, long recordedGeminiAnalysisReceipts,
                        String geminiReadiness, Instant lastGeminiAttemptAt,
                        String lastGeminiAttemptOutcome,
                        Map<String, Instant> lastSuccessfulAnalysisAtBySource) {}

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
