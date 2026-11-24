package in.aitradingsystem.news;

import in.aitradingsystem.common.PageResponse;
import in.aitradingsystem.news.NewsAnalysisService.AnalysisInProgressException;
import in.aitradingsystem.news.NewsService.AnalysisView;
import in.aitradingsystem.news.NewsService.ArticleView;
import in.aitradingsystem.news.NewsService.IngestRequest;
import in.aitradingsystem.news.NewsService.IngestResult;
import in.aitradingsystem.news.NewsService.NewsStatusView;
import in.aitradingsystem.news.analysis.AiErrorCode;
import in.aitradingsystem.news.analysis.AiProviderException;
import in.aitradingsystem.news.policy.NewsSourcePolicy.Use;
import in.aitradingsystem.news.policy.NewsSourcePolicyService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/news")
@Validated
public class NewsController {
    private final NewsService news;
    private final NewsAnalysisService analysis;
    private final NewsSourcePolicyService policy;

    public NewsController(NewsService news, NewsAnalysisService analysis, NewsSourcePolicyService policy) {
        this.news = news;
        this.analysis = analysis;
        this.policy = policy;
    }

    @GetMapping("/articles")
    public PageResponse<ArticleView> articles(
        @RequestParam(defaultValue = "0") @Min(0) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size) {
        return news.articles(page, size);
    }

    @PostMapping("/articles")
    public IngestResult ingest(@Valid @RequestBody IngestRequest request) {
        // Validate every source before writing the first row. A caller-supplied source label does not
        // constitute publisher permission, even when the caller has OPERATOR authentication.
        Instant now = Instant.now();
        for (NewsService.ArticleInput article : request.articles()) {
            if (!policy.permitted(article.source(), Use.INGEST, now)
                || !policy.permitted(article.source(), Use.RETAIN, now)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "News source is not approved for ingestion and retention");
            }
        }
        return news.ingest(request);
    }

    @PostMapping("/articles/{id}/analyze")
    public AnalysisView analyze(@PathVariable UUID id,
        @RequestParam(defaultValue = "false") boolean force) {
        return analysis.analyze(id, force);
    }

    @GetMapping("/status")
    public NewsStatusView status() {
        return news.status();
    }

    @ExceptionHandler(AiProviderException.class)
    ProblemDetail aiFailure(AiProviderException ex) {
        HttpStatus status = ex.code() == AiErrorCode.UNCONFIGURED ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.BAD_GATEWAY;
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, ex.getMessage());
        pd.setType(URI.create("about:blank"));
        pd.setProperty("errorCode", ex.code().name());
        return pd;
    }

    @ExceptionHandler(AnalysisInProgressException.class)
    ProblemDetail inProgress(AnalysisInProgressException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    }
}
