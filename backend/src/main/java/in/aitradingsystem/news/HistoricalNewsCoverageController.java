package in.aitradingsystem.news;

import in.aitradingsystem.instrument.InstrumentMasterException;
import in.aitradingsystem.news.feature.HistoricalNewsCoverageService;
import java.time.Instant;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Admin-only, bounded read of historical coverage. No ingestion, model or execution calls. */
@RestController
@RequestMapping("/api/admin/market-intelligence")
public class HistoricalNewsCoverageController {
    private final HistoricalNewsCoverageService coverage;

    public HistoricalNewsCoverageController(HistoricalNewsCoverageService coverage) {
        this.coverage = coverage;
    }

    @GetMapping("/historical-coverage")
    public HistoricalNewsCoverageService.CoveragePage measure(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant asOf,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "25") int limit,
            @RequestParam(required = false) UUID expectedVersion,
            @RequestParam(required = false) String expectedFingerprint) {
        if (expectedFingerprint != null && !expectedFingerprint.matches("[a-f0-9]{64}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid catalogue fingerprint");
        }
        try {
            return coverage.measure(asOf, offset, limit, expectedVersion, expectedFingerprint);
        } catch (InstrumentMasterException unavailable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Historical catalogue evidence unavailable");
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid historical coverage request");
        } catch (IllegalStateException unsafe) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Historical coverage could not be verified");
        }
    }
}
