package in.aitradingsystem.news;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.aitradingsystem.common.PageResponse;
import in.aitradingsystem.config.NewsProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** Agent 1 (News Ingestion): normalizes, deduplicates and stores articles. Ingestion never calls an AI model. */
@Service
public class NewsService {
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final NewsProperties props;

    public NewsService(JdbcClient jdbc, ObjectMapper mapper, NewsProperties props) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.props = props;
    }

    public record ArticleInput(
        @NotBlank @Size(max = 80) String source,
        @Size(max = 200) String externalId,
        @NotBlank @Size(max = 500) String title,
        @NotBlank @Size(max = 2000) String url,
        @Size(max = 2000) String excerpt,
        @NotNull Instant publishedAt) {}

    public record IngestRequest(@NotNull @Valid @Size(min = 1, max = 50) List<ArticleInput> articles) {}

    public record IngestResult(int received, int inserted, int duplicates) {}

    public record AnalysisView(UUID id, String status, String provider, String model, String sentiment, String category,
        BigDecimal confidence, List<String> impactedSymbols, String evidence, List<String> risks, String errorCode,
        int latencyMs, Instant analyzedAt) {}

    public record ArticleView(UUID id, String source, String title, String url, String excerpt, Instant publishedAt,
        Instant ingestedAt, AnalysisView analysis) {}

    public record NewsStatusView(String provider, boolean configured, String model, long articleCount,
        Instant lastIngestedAt, Instant lastSuccessfulAnalysisAt, String lastErrorCode, Instant lastErrorAt) {}

    @org.springframework.transaction.annotation.Transactional
    public IngestResult ingest(IngestRequest request) {
        int inserted = 0;
        for (ArticleInput a : request.articles()) {
            String url = normalizeUrl(a.url());
            int rows = jdbc.sql("""
                    INSERT INTO news_articles (id, url_hash, source, external_id, title, url, excerpt, published_at)
                    VALUES (:id, :hash, :source, :externalId, :title, :url, :excerpt, :publishedAt)
                    ON CONFLICT (url_hash) DO NOTHING
                    """)
                .param("id", UUID.randomUUID())
                .param("hash", sha256(url))
                .param("source", a.source().trim())
                .param("externalId", blankToNull(a.externalId()))
                .param("title", a.title().trim())
                .param("url", url)
                .param("excerpt", blankToNull(a.excerpt()))
                .param("publishedAt", java.time.OffsetDateTime.ofInstant(a.publishedAt(), java.time.ZoneOffset.UTC))
                .update();
            inserted += rows;
        }
        return new IngestResult(request.articles().size(), inserted, request.articles().size() - inserted);
    }

    public PageResponse<ArticleView> articles(int page, int size) {
        long total = jdbc.sql("SELECT count(*) FROM news_articles").query(Long.class).single();
        List<ArticleView> items = jdbc.sql(ARTICLE_SELECT + """
                ORDER BY a.published_at DESC
                LIMIT :limit OFFSET :offset
                """)
            .param("limit", size)
            .param("offset", (long) page * size)
            .query(this::mapArticle)
            .list();
        return PageResponse.of(items, page, size, total);
    }

    public java.util.Optional<ArticleView> findArticle(UUID id) {
        return jdbc.sql(ARTICLE_SELECT + "WHERE a.id = :id")
            .param("id", id)
            .query(this::mapArticle)
            .optional();
    }

    private static final String ARTICLE_SELECT = """
        SELECT a.id, a.source, a.title, a.url, a.excerpt, a.published_at, a.ingested_at,
               an.id AS an_id, an.status, an.provider, an.model, an.sentiment, an.category, an.confidence,
               an.impacted_symbols, an.evidence, an.risks, an.error_code, an.latency_ms, an.created_at AS analyzed_at
        FROM news_articles a
        LEFT JOIN LATERAL (
            SELECT x.* FROM news_analyses x WHERE x.article_id = a.id ORDER BY x.created_at DESC LIMIT 1
        ) an ON true
        """;

    private ArticleView mapArticle(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        AnalysisView analysis = null;
        if (rs.getObject("an_id") != null) {
            analysis = new AnalysisView(
                rs.getObject("an_id", UUID.class),
                rs.getString("status"),
                rs.getString("provider"),
                rs.getString("model"),
                rs.getString("sentiment"),
                rs.getString("category"),
                rs.getBigDecimal("confidence"),
                readList(rs.getString("impacted_symbols")),
                rs.getString("evidence"),
                readList(rs.getString("risks")),
                rs.getString("error_code"),
                rs.getInt("latency_ms"),
                rs.getTimestamp("analyzed_at").toInstant());
        }
        return new ArticleView(
            rs.getObject("id", UUID.class),
            rs.getString("source"),
            rs.getString("title"),
            rs.getString("url"),
            rs.getString("excerpt"),
            rs.getTimestamp("published_at").toInstant(),
            rs.getTimestamp("ingested_at").toInstant(),
            analysis);
    }

    public NewsStatusView status() {
        String provider = props.provider();
        boolean configured = switch (provider) {
            case "GEMINI" -> notBlank(props.geminiApiKey());
            case "CLAUDE" -> notBlank(props.claudeApiKey());
            default -> false;
        };
        String model = switch (provider) {
            case "GEMINI" -> props.geminiModel();
            case "CLAUDE" -> props.claudeModel();
            default -> null;
        };
        long count = jdbc.sql("SELECT count(*) FROM news_articles").query(Long.class).single();
        Instant lastIngested = instant(jdbc.sql("SELECT max(ingested_at) FROM news_articles")
            .query(java.time.OffsetDateTime.class).optional().orElse(null));
        Instant lastSuccess = instant(jdbc.sql("SELECT max(created_at) FROM news_analyses WHERE status = 'ANALYZED'")
            .query(java.time.OffsetDateTime.class).optional().orElse(null));
        LastError lastError = jdbc.sql("""
                SELECT error_code, created_at FROM news_analyses WHERE status = 'FAILED'
                ORDER BY created_at DESC LIMIT 1
                """)
            .query((rs, n) -> new LastError(rs.getString("error_code"), rs.getTimestamp("created_at").toInstant()))
            .optional().orElse(null);
        return new NewsStatusView(
            provider,
            configured,
            model,
            count,
            lastIngested,
            lastSuccess,
            lastError == null ? null : lastError.code(),
            lastError == null ? null : lastError.at());
    }

    private record LastError(String code, Instant at) {}

    private static Instant instant(java.time.OffsetDateTime ts) {
        return ts == null ? null : ts.toInstant();
    }

    /** Stable key for deduplication: lower-cased scheme and host, no fragment, no trailing slash. */
    static String normalizeUrl(String raw) {
        try {
            URI u = new URI(raw.trim());
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) throw new IllegalArgumentException("url must be http or https");
            String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
            String path = u.getRawPath() == null ? "" : u.getRawPath().replaceAll("/+$", "");
            String query = u.getRawQuery() == null ? "" : "?" + u.getRawQuery();
            return scheme + "://" + host + path + query;
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("url is not a valid URI");
        }
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private List<String> readList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return mapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
