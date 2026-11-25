package in.aitradingsystem.api;

import in.aitradingsystem.mind.MindProspectiveCohortService;
import in.aitradingsystem.mind.MindProspectiveSignalService;
import org.springframework.http.CacheControl;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Explicit ADMIN-only cohort registration. The security filter requires
 * ROLE_ADMIN for /api/admin/model-governance/**; no customer or broker role
 * can create a cohort. This never submits a PAPER or LIVE order.
 */
@RestController
@RequestMapping("/api/admin/model-governance/mind-prospective")
public class MindProspectiveCohortAdminController {
    private final MindProspectiveCohortService service;
    private final MindProspectiveSignalService signals;

    public MindProspectiveCohortAdminController(
            MindProspectiveCohortService service,
            MindProspectiveSignalService signals) {
        this.service = service;
        this.signals = signals;
    }

    /** Manual, ADMIN-gated collection inside a current completed-bar window. */
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/capture-signals")
    public ResponseEntity<MindProspectiveSignalService.Capture> captureSignals() {
        try {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .body(signals.capture());
        } catch (IllegalStateException | IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "No eligible current-session signal window or verified cohort input");
        }
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/register")
    public ResponseEntity<MindProspectiveCohortService.Status> register() {
        try {
            return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore()).body(service.register());
        } catch (IllegalStateException conflict) {
            // No internal catalogue details, SQL or broker metadata in HTTP.
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Prospective cohort cannot be registered with current catalogue and session readiness");
        }
    }
}
