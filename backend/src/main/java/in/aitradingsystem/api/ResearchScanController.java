package in.aitradingsystem.api;

import in.aitradingsystem.marketdata.HistoricalCandleInterval;
import in.aitradingsystem.scanner.ResearchScanCandidateView;
import in.aitradingsystem.scanner.ResearchScanRepository;
import in.aitradingsystem.scanner.ResearchScanService;
import in.aitradingsystem.scanner.ResearchScanSummary;
import java.util.List;
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
    private final ResearchScanRepository repository;

    public ResearchScanController(ResearchScanService service, ResearchScanRepository repository) {
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

    @GetMapping("/{id}/candidates")
    public ResponseEntity<List<ResearchScanCandidateView>> candidates(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(repository.rankedCandidates(id, limit));
    }
}
