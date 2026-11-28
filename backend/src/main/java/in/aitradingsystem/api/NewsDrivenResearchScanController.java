package in.aitradingsystem.api;

import in.aitradingsystem.marketdata.HistoricalCandleInterval;
import in.aitradingsystem.scanner.NewsDrivenResearchScanService;
import in.aitradingsystem.scanner.ResearchScanSummary;
import org.springframework.http.ResponseEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Explicit user-triggered, research-only scan whose candidate source is analyzed news. */
@RestController
@RequestMapping("/api/research/news-driven-scans")
public class NewsDrivenResearchScanController {
    private final NewsDrivenResearchScanService service;

    public NewsDrivenResearchScanController(NewsDrivenResearchScanService service) {
        this.service = service;
    }

    @GetMapping("/{id}/candidates")
    public ResponseEntity<List<NewsDrivenResearchScanService.NewsDrivenCandidateView>> candidates(
            @PathVariable UUID id) {
        return ResponseEntity.ok(service.candidates(id));
    }

    @PostMapping
    public ResponseEntity<ResearchScanSummary> run(
            @RequestParam(defaultValue = "FIVE_MINUTE") HistoricalCandleInterval interval,
            @RequestParam(defaultValue = "100") int limit) {
        return ResponseEntity.ok(service.run(interval, limit));
    }
}
