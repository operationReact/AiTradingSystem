package in.aitradingsystem.marketdata;

import org.springframework.http.CacheControl;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Operator market-data diagnostics: counts only, no tokens or customer identities. */
@RestController
@RequestMapping("/api/market-data/candles/universe-coverage")
public class UniverseCandleCoverageController {
    private final UniverseCandleCoverageService service;

    public UniverseCandleCoverageController(UniverseCandleCoverageService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    public ResponseEntity<UniverseCandleCoverageRepository.Coverage> coverage() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.coverage());
    }
}
