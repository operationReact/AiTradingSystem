package in.aitradingsystem.news;

import in.aitradingsystem.common.PageResponse;
import in.aitradingsystem.news.NewsEventReadRepository.EventCard;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Customer-authorized, read-only canonical event timeline.
 * Existing /api/customer/** Spring Security requires ROLE_CUSTOMER.
 *
 * <p>Only first-party source-backed metadata with current DISPLAY permission
 * is returned. News events are advisory, never an order/strategy instruction.
 */
@RestController
@RequestMapping("/api/customer/market-intelligence")
@Validated
public class CustomerNewsEventsController {
    private final NewsEventReadRepository events;

    public CustomerNewsEventsController(NewsEventReadRepository events) {
        this.events = events;
    }

    @GetMapping("/events")
    public PageResponse<EventCard> events(
        @RequestParam(defaultValue = "0") @Min(0) @Max(200) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size,
        @RequestParam(required = false) @Size(max = 80) String sourceId) {
        return events.events(page, size, sourceId);
    }
}
