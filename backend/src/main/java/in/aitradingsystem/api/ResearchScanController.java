package in.aitradingsystem.api;

import in.aitradingsystem.marketdata.HistoricalCandleInterval;
import in.aitradingsystem.scanner.ResearchScanService;
import in.aitradingsystem.scanner.ResearchScanSummary;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/research/scans")
public class ResearchScanController {
    private final ResearchScanService service;
    private final in.aitradingsystem.scanner.ResearchScanRepository repository;

    public ResearchScanController(ResearchScanService service,
                                  in.aitradingsystem.scanner.ResearchScanRepository repository) {
        this.service = service;
        this.repository = repository;
    }

    @PostMapping
    public ResponseEntity<ResearchScanSummary> run(
            @RequestParam(defaultValue = "FIVE_MINUTE") HistoricalCandleInterval interval,
            @RequestParam(defaultValue = "100") int limit) {
        return ResponseEntity.ok(service.run(interval, limit));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ResearchScanSummary> get(@PathVariable UUID id) {
        return ResponseEntity.ok(repository.requireRun(id));
    }
}
