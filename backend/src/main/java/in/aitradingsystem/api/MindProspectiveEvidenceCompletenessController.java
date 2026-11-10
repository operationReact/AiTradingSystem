package in.aitradingsystem.api;

import in.aitradingsystem.mind.MindProspectiveEvidenceCompletenessService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Customer-visible prospective research accounting only. GET never mutates
 * the evidence ledger, captures signals, or authorizes PAPER/LIVE trading.
 */
@RestController
public class MindProspectiveEvidenceCompletenessController {
    private final MindProspectiveEvidenceCompletenessService service;

    public MindProspectiveEvidenceCompletenessController(
            MindProspectiveEvidenceCompletenessService service) {
        this.service = service;
    }

    @PreAuthorize("hasRole('CUSTOMER')")
    @GetMapping("/api/customer/mind/v1/prospective-evidence-completeness")
    public ResponseEntity<MindProspectiveEvidenceCompletenessService.Snapshot> inspect() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.inspect());
    }
}
