package in.aitradingsystem.api;

import in.aitradingsystem.paper.PaperOrderAnalyticsService;
import in.aitradingsystem.paper.PaperOrderAnalyticsService.OrderAnalytics;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only PAPER order lifecycle analytics. */
@RestController
@RequestMapping("/api/paper-trades/analytics")
public class PaperOrderAnalyticsController {
    private final PaperOrderAnalyticsService analytics;

    public PaperOrderAnalyticsController(PaperOrderAnalyticsService analytics) {
        this.analytics = analytics;
    }

    @GetMapping
    public OrderAnalytics snapshot() {
        return analytics.snapshot();
    }
}
