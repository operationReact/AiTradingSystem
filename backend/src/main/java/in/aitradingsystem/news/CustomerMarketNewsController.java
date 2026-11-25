package in.aitradingsystem.news;

import in.aitradingsystem.common.PageResponse;
import in.aitradingsystem.news.NewsService.ArticleView;
import in.aitradingsystem.news.NewsService.AnalysisView;
import java.math.BigDecimal;
import java.util.List;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
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

    public CustomerMarketNewsController(NewsService news) {
        this.news = news;
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
