package in.aitradingsystem.api;

import in.aitradingsystem.paper.PaperQueryService;
import in.aitradingsystem.paper.PaperViews.PortfolioView;
import in.aitradingsystem.paper.PaperViews.PositionView;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class PaperPortfolioController {
    private final PaperQueryService queries;

    public PaperPortfolioController(PaperQueryService queries) {
        this.queries = queries;
    }

    @GetMapping("/paper-portfolio")
    public PortfolioView portfolio() {
        return queries.portfolio();
    }

    @GetMapping("/paper-positions")
    public List<PositionView> positions() {
        return queries.positions();
    }
}
