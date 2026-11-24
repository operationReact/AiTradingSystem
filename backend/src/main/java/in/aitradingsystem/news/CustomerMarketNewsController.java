package in.aitradingsystem.news;

import in.aitradingsystem.common.PageResponse;
import in.aitradingsystem.news.NewsService.ArticleView;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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

    /** Returns a bounded publisher-attributed article page without internal analysis metadata. */
    @GetMapping("/articles")
    public PageResponse<ArticleSummary> articles(
        @RequestParam(defaultValue = "0") @Min(0) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size) {
        PageResponse<ArticleView> result = news.articles(page, size);
        return PageResponse.of(result.items().stream()
            .map(ArticleSummary::from)
            .toList(), result.page(), result.size(), result.totalItems());
    }

    public record ArticleSummary(
        UUID id, String source, String title, String url, String excerpt,
        Instant publishedAt, Instant ingestedAt) {
        static ArticleSummary from(ArticleView article) {
            return new ArticleSummary(article.id(), article.source(), article.title(),
                article.url(), article.excerpt(), article.publishedAt(), article.ingestedAt());
        }
    }
}
