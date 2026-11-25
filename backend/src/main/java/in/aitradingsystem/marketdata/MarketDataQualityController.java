package in.aitradingsystem.marketdata;

import java.time.Instant;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read quality snapshots and explicitly trigger bounded historical quality evaluation. */
@RestController
@RequestMapping("/api/market-data/quality")
public class MarketDataQualityController {
    private final MarketDataQualityService service;

    public MarketDataQualityController(MarketDataQualityService service) {
        this.service = service;
    }

    @PostMapping("/evaluate")
    public MarketDataQualityResult evaluate(@RequestParam long instrumentId,
            @RequestParam HistoricalCandleInterval interval,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return service.evaluate(instrumentId, interval, from, to);
    }

    @GetMapping("/latest")
    public QualityStatus latest(@RequestParam long instrumentId,
            @RequestParam HistoricalCandleInterval interval) {
        if (instrumentId <= 0) throw new IllegalArgumentException("instrumentId must be positive");
        String status = service.latest(instrumentId, interval);
        return new QualityStatus(instrumentId, interval, status == null ? "NOT_EVALUATED" : status,
                "HEALTHY".equals(status));
    }

    public record QualityStatus(long instrumentId, HistoricalCandleInterval interval,
            String status, boolean researchEligible) {}
}
