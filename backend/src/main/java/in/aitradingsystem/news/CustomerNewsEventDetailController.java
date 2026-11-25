package in.aitradingsystem.news;

import in.aitradingsystem.news.NewsEventEvidenceDetailRepository.EventDetail;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * CUSTOMER-only evidence drilldown for a canonical market event.
 * No order rights or mutable article administration is exposed.
 */
@RestController
@RequestMapping("/api/customer/market-intelligence/events")
public class CustomerNewsEventDetailController {
    private final NewsEventEvidenceDetailRepository evidence;

    public CustomerNewsEventDetailController(NewsEventEvidenceDetailRepository evidence) {
        this.evidence = evidence;
    }

    @GetMapping("/{eventId}")
    public EventDetail detail(@PathVariable UUID eventId) {
        return evidence.detail(eventId);
    }
}
