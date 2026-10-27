package in.aitradingsystem.api;

import in.aitradingsystem.domain.NewsItem;
import in.aitradingsystem.news.NewsRepository;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/news")
public class NewsController {
    private final NewsRepository repository;

    public NewsController(NewsRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public NewsPage recent(@RequestParam(defaultValue = "20") int limit,
        @RequestParam(defaultValue = "0") int offset) {
        if (limit < 1 || limit > 100 || offset < 0 || offset > 10000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "News pagination must use a limit from 1 to 100 and an offset from 0 to 10000.");
        }
        List<NewsItem> results = repository.recent(limit + 1, offset);
        boolean hasMore = results.size() > limit;
        return new NewsPage(hasMore ? results.subList(0, limit) : results, limit, offset, hasMore);
    }

    public record NewsPage(List<NewsItem> items, int limit, int offset, boolean hasMore) {}
}
