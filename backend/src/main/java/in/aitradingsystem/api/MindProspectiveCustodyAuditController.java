package in.aitradingsystem.api;

import in.aitradingsystem.mind.MindProspectiveCustodyAuditService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * ADMIN-only, immutable source-custody reproducibility audit.
 * This read-only route does not authorize trading, update results,
 * register cohorts, capture signals, or trigger a market-data import.
 */
@RestController
@Validated
public class MindProspectiveCustodyAuditController {
    private final MindProspectiveCustodyAuditService audit;

    public MindProspectiveCustodyAuditController(
            MindProspectiveCustodyAuditService audit) {
        this.audit = audit;
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/api/admin/model-governance/mind-prospective/audit-outcome-custody")
    public ResponseEntity<MindProspectiveCustodyAuditService.Audit> inspect(
            @RequestParam(defaultValue = "0") @Min(0) @Max(15000) int offset) {
        try {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(audit.audit(offset));
        } catch (IllegalArgumentException | IllegalStateException invalid) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Requested custody audit page or prospective cohort is unavailable");
        }
    }
}
