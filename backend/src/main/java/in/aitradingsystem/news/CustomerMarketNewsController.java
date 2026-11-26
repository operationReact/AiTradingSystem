package in.aitradingsystem.news;

import in.aitradingsystem.common.PageResponse;
import in.aitradingsystem.news.NewsService.ArticleView;
import in.aitradingsystem.news.NewsService.AnalysisView;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.time.Duration;
import java.util.UUID;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only CUSTOMER projection for the Flutter Market Intelligence article feed.
 *
 * <p>This is an article feed, not a canonical event ledger or a trading signal.
 * NewsService.articles applies the current default-deny DISPLAY rights policy,
 * so unapproved, expired and revoked publisher content is never sent to clients.
 * The existing /api/customer/** security rule requires ROLE_CUSTOMER.
 */
@RestController
@RequestMapping("/api/customer/market-intelligence")
@Validated
public class CustomerMarketNewsController {
    private final NewsService news;
    private final NewsSourceHealthRepository sourceHealth;

    public CustomerMarketNewsController(NewsService news, NewsSourceHealthRepository sourceHealth) {
        this.news = news;
        this.sourceHealth = sourceHealth;
    }

    /** Returns a bounded publisher-attributed page with a small advisory impact projection. */
    @GetMapping("/articles")
    public PageResponse<ArticleSummary> articles(
        @RequestParam(defaultValue = "0") @Min(0) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size,
        @RequestParam(required = false)
        @Pattern(regexp = "[A-Za-z0-9&.-]{1,20}") String symbol) {
        String normalizedSymbol = symbol == null ? null : symbol.toUpperCase(java.util.Locale.ROOT);
        PageResponse<ArticleView> result = news.articles(page, size, normalizedSymbol);
        return PageResponse.of(result.items().stream()
            .map(ArticleSummary::from)
            .toList(), result.page(), result.size(), result.totalItems());
    }

    /** Sanitized last-fetch health for every supported publisher; no raw upstream diagnostics. */
    @GetMapping("/sources")
    public List<SourceSummary> sources() {
        Instant now = Instant.now();
        Map<String, NewsSourceHealthRepository.SourceHealth> latest = sourceHealth.latest().stream()
            .collect(Collectors.toMap(NewsSourceHealthRepository.SourceHealth::source, Function.identity(),
                (a, b) -> a.finishedAt().isAfter(b.finishedAt()) ? a : b));
        return NewsSource.SUPPORTED.stream().map(source -> {
            var run = latest.get(source.id());
            return new SourceSummary(source.id(), source.publisher(),
                run == null ? "NEVER_RUN" : run.status(),
                run == null ? null : run.startedAt(), run == null ? null : run.finishedAt(),
                run == null ? null : Math.max(0L, Duration.between(run.finishedAt(), now).getSeconds()),
                run == null ? 0 : run.fetchedItems(), run == null ? 0 : run.insertedItems(),
                run == null ? null : run.errorCode());
        }).toList();
    }

    public record SourceSummary(String sourceId, String publisher, String status,
        Instant startedAt, Instant finishedAt, Long ageSeconds, int fetchedItems,
        int insertedItems, String errorCode) {}

    /** Whitelisted advisory fields only; excludes provider request IDs and internal fingerprints. */
    public record StockImpact(
        String provider, String model, String sentiment, BigDecimal directionScore, String category,
        BigDecimal confidence, BigDecimal materiality, String horizon, Boolean contradiction,
        Boolean ambiguity, List<String> impactedSymbols, String evidence, List<String> risks,
        Instant analyzedAt) {
        static StockImpact from(AnalysisView analysis) {
            if (analysis == null || !"ANALYZED".equals(analysis.status())) return null;
            return new StockImpact(analysis.provider(), analysis.model(), analysis.sentiment(),
                analysis.directionScore(), analysis.category(), analysis.confidence(), analysis.materiality(),
                analysis.horizon(), analysis.contradiction(), analysis.ambiguity(),
                analysis.impactedSymbols(), analysis.evidence(), analysis.risks(), analysis.analyzedAt());
        }
    }

    public record ArticleSummary(
        UUID id, String source, String title, String url, String excerpt,
        Instant publishedAt, Instant ingestedAt, String analysisStatus, StockImpact stockImpact) {
        static ArticleSummary from(ArticleView article) {
            AnalysisView analysis = article.analysis();
            String status = analysis == null ? "NOT_ANALYZED" : analysis.status();
            return new ArticleSummary(article.id(), article.source(), article.title(),
                article.url(), article.excerpt(), article.publishedAt(), article.ingestedAt(),
                status, StockImpact.from(analysis));
        }
    }
}
