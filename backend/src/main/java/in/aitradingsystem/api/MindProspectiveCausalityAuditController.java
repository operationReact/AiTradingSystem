package in.aitradingsystem.api;

import in.aitradingsystem.mind.MindProspectiveCausalityAuditService;
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

/** Customer read-only prospective timing audit: no trading or mutation. */
@RestController
@Validated
public class MindProspectiveCausalityAuditController {
    private final MindProspectiveCausalityAuditService audit;

    public MindProspectiveCausalityAuditController(
            MindProspectiveCausalityAuditService audit) {
        this.audit = audit;
    }

    @PreAuthorize("hasRole('CUSTOMER')")
    @GetMapping("/api/customer/mind/v1/prospective-entry-causality")
    public ResponseEntity<MindProspectiveCausalityAuditService.Audit> inspect(
            @RequestParam(defaultValue = "0") @Min(0) @Max(15000) int offset) {
        try {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(audit.inspect(offset));
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Requested research timing audit page is unavailable");
        }
    }
}
