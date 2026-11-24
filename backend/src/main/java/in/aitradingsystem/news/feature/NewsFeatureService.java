package in.aitradingsystem.news.feature;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Read-only as-of lookup of analyzed news for one instrument. Reuses the existing analyzer tables
 * (news_articles, news_analyses); it does not ingest, scrape or call an AI provider.
 *
 * <p>Timestamp rule: only the latest analysis created at or before {@code asOf} is considered for
 * each article, and the article must have been published at or before {@code asOf}.
 */
@Service
public class NewsFeatureService {
    static final int MAX_ROWS = 200;
    static final int MAX_EVIDENCE = 20;
    private static final Pattern SAFE_BASE_SYMBOL = Pattern.compile("[A-Z0-9&]{1,20}");
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final boolean enabled;
    private final ObjectProvider<FnoUnderlyingCatalogueAdapter> fnoAdapter;

    public NewsFeatureService(JdbcClient jdbc, ObjectMapper mapper,
            @Value("${app.news.mind-context.enabled:false}") boolean enabled,
            ObjectProvider<FnoUnderlyingCatalogueAdapter> fnoAdapter) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.enabled = enabled;
        this.fnoAdapter = fnoAdapter;
    }

    /** Rollout flag. When false, scans do not attach news context at all. */
    public boolean enabled() {
        return enabled;
    }

    /** Stable JSON for persistence on a candidate. */
    public String toJson(NewsContext context) {
        try {
            return mapper.writeValueAsString(context);
        } catch (Exception e) {
            throw new IllegalStateException("News context could not be serialized", e);
        }
    }

    /**
     * As-of news context for one instrument, with the F&amp;O underlying resolution attached when the
     * catalogue adapter is available. The resolution never changes a news fact. Without the adapter
     * the F&amp;O fields stay null, which reads as "not resolved", never as "not listed".
     */
    public NewsContext contextFor(long instrumentId, String tradingSymbol, Instant asOf) {
        NewsContext context = contextFromNews(instrumentId, tradingSymbol, asOf);
        FnoUnderlyingCatalogueAdapter adapter = fnoAdapter.getIfAvailable();
        if (adapter == null) return context;
        return context.withFno(adapter.resolve(baseSymbol(tradingSymbol)));
    }

    private NewsContext contextFromNews(long instrumentId, String tradingSymbol, Instant asOf) {
        String base = baseSymbol(tradingSymbol);
        if (!SAFE_BASE_SYMBOL.matcher(base).matches()) {
            return new NewsContext(NewsFeaturePolicy.CONTRACT, instrumentId, tradingSymbol, asOf,
                NewsContext.STATUS_NO_COVERAGE, null, "UNKNOWN", "UNKNOWN", 0, 0, List.of(), NewsContext.LIMITATIONS,
                false, null, null);
        }
        List<Row> rows = jdbc.sql("""
                SELECT x.article_id, x.source, x.title, x.url, x.published_at,
                       x.analysis_status, x.provider, x.model, x.sentiment, x.direction_score,
                       x.confidence, x.materiality, x.horizon, x.contradiction, x.ambiguity,
                       x.impacted_symbols, x.evidence, x.analysis_created_at, x.category
                  FROM (
                      SELECT DISTINCT ON (a.id)
                             a.id AS article_id, a.source, a.title, a.url, a.published_at,
                             an.status AS analysis_status, an.provider, an.model, an.sentiment,
                             an.direction_score, an.confidence, an.materiality, an.horizon,
                             an.contradiction, an.ambiguity, an.impacted_symbols, an.evidence,
                             an.created_at AS analysis_created_at, an.category
                        FROM news_articles a
                        JOIN news_analyses an ON an.article_id = a.id
                       WHERE a.published_at <= :asOf
                         AND a.published_at > :lookbackStart
                         AND an.created_at <= :asOf
                         AND a.id IN (
                             SELECT m.article_id FROM news_analyses m
                              WHERE m.created_at <= :asOf
                                AND (m.impacted_symbols LIKE :plain OR m.impacted_symbols LIKE :eq)
                         )
                       ORDER BY a.id, an.created_at DESC, an.id DESC
                  ) x
                 ORDER BY x.published_at DESC, x.article_id
                 LIMIT :limit
                """)
            .param("asOf", offset(asOf))
            .param("lookbackStart", offset(asOf.minus(NewsFeaturePolicy.LOOKBACK)))
            .param("plain", "%\"" + base + "\"%")
            .param("eq", "%\"" + base + "-EQ\"%")
            .param("limit", MAX_ROWS)
            .query((rs, n) -> new Row(
                rs.getObject("article_id", java.util.UUID.class).toString(),
                rs.getString("source"),
                rs.getString("title"),
                rs.getString("url"),
                rs.getObject("published_at", OffsetDateTime.class).toInstant(),
                rs.getString("analysis_status"),
                rs.getString("provider"),
                rs.getString("model"),
                rs.getString("sentiment"),
                rs.getBigDecimal("direction_score"),
                rs.getBigDecimal("confidence"),
                rs.getBigDecimal("materiality"),
                rs.getString("horizon"),
                (Boolean) rs.getObject("contradiction"),
                (Boolean) rs.getObject("ambiguity"),
                rs.getString("impacted_symbols"),
                rs.getString("evidence"),
                rs.getObject("analysis_created_at", OffsetDateTime.class).toInstant(),
                rs.getString("category")))
            .list();

        List<NewsFeaturePolicy.Evaluated> evaluated = new ArrayList<>();
        List<NewsContext.Evidence> evidence = new ArrayList<>();
        boolean blackoutActive = false;
        for (Row row : rows) {
            boolean verified = symbols(row.impactedSymbols()).stream()
                .anyMatch(s -> baseSymbol(s).equals(base));
            // Unknown contradiction/ambiguity is treated as uncertain: it must never add direction.
            NewsFeaturePolicy.Candidate candidate = new NewsFeaturePolicy.Candidate(
                row.publishedAt(), row.analysisCreatedAt(), row.analysisStatus(), row.horizon(),
                row.directionScore(), row.confidence(), row.materiality(),
                !Boolean.FALSE.equals(row.contradiction()), !Boolean.FALSE.equals(row.ambiguity()),
                verified);
            NewsFeaturePolicy.Status status = NewsFeaturePolicy.classify(candidate, asOf);
            evaluated.add(new NewsFeaturePolicy.Evaluated(candidate, status));
            NewsEventClassifier.EventClass eventClass = NewsEventClassifier.classify(row.category());
            java.time.Instant validUntil = NewsFeaturePolicy.validUntil(row.publishedAt(), row.horizon());
            java.time.Instant blackoutUntil = NewsEventClassifier.blackoutUntil(eventClass, row.materiality(), row.publishedAt());
            boolean included = status == NewsFeaturePolicy.Status.INCLUDED;
            if (included && NewsEventClassifier.inBlackout(blackoutUntil, asOf)) blackoutActive = true;
            if (evidence.size() < MAX_EVIDENCE) {
                evidence.add(new NewsContext.Evidence(
                    row.articleId(), row.source(), row.title(), row.url(), row.publishedAt(),
                    row.analysisCreatedAt(), validUntil,
                    row.horizon(), row.sentiment(), row.directionScore(), row.confidence(), row.materiality(),
                    row.provider(), row.model(), truncate(row.evidence(), 300), status.name(),
                    included, eventClass.name(),
                    NewsFreshness.of(row.publishedAt(), validUntil, asOf).name(), blackoutUntil));
            }
        }

        NewsFeaturePolicy.Aggregate aggregate = NewsFeaturePolicy.aggregate(evaluated);
        String status = rows.isEmpty() ? NewsContext.STATUS_NO_COVERAGE
            : aggregate.includedCount() > 0 ? NewsContext.STATUS_INCLUDED_EVIDENCE
            : NewsContext.STATUS_NO_INCLUDED_EVIDENCE;
        return new NewsContext(NewsFeaturePolicy.CONTRACT, instrumentId, tradingSymbol, asOf, status,
            aggregate.netDirection(), aggregate.direction(), aggregate.uncertainty(),
            aggregate.includedCount(), aggregate.excludedCount(), List.copyOf(evidence),
            NewsContext.LIMITATIONS, blackoutActive, null, null);
    }

    /** Upper-case base symbol: NSE equity series suffix "-EQ" is dropped for comparison. */
    static String baseSymbol(String raw) {
        if (raw == null) return "";
        String s = raw.trim().toUpperCase(Locale.ROOT);
        return s.endsWith("-EQ") ? s.substring(0, s.length() - 3) : s;
    }

    private List<String> symbols(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return mapper.readValue(json, STRING_LIST);
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static OffsetDateTime offset(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private record Row(String articleId, String source, String title, String url, Instant publishedAt,
                       String analysisStatus, String provider, String model, String sentiment,
                       BigDecimal directionScore, BigDecimal confidence, BigDecimal materiality,
                       String horizon, Boolean contradiction, Boolean ambiguity, String impactedSymbols,
                       String evidence, Instant analysisCreatedAt, String category) {}
}
