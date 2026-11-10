package in.aitradingsystem.api;

import in.aitradingsystem.mind.MindProspectiveOutcomeService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Separate safe read surface from explicitly ADMIN-only outcome reconciliation.
 * GET has no mutation and both responses are no-store research evidence.
 */
@RestController
@Validated
public class MindProspectiveOutcomeController {
    private final MindProspectiveOutcomeService service;

    public MindProspectiveOutcomeController(MindProspectiveOutcomeService service) {
        this.service = service;
    }

    @PreAuthorize("hasRole('CUSTOMER')")
    @GetMapping("/api/customer/mind/v1/prospective-outcomes")
    public ResponseEntity<MindProspectiveOutcomeService.Status> inspect() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.inspect());
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/api/admin/model-governance/mind-prospective/reconcile-outcomes")
    public ResponseEntity<MindProspectiveOutcomeService.Reconciliation> reconcile(
            @RequestParam(defaultValue = "0") @Min(0) @Max(15000) int offset) {
        try {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.reconcile(offset));
        } catch (IllegalArgumentException | IllegalStateException invalid) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Outcome page or prospective research cohort is not eligible");
        }
    }
}
