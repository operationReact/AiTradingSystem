package in.aitradingsystem.instrument;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operator-only POST under the existing Spring Security /api/** mutation policy.
 * The source URI is pinned inside the service; callers cannot make arbitrary HTTP requests.
 * Customer JWT roles have no access to this operation.
 */
@RestController
@RequestMapping("/api/instruments/bse-security-master")
public class BseSecurityMasterController {
    private final BseSecurityMasterService source;

    public BseSecurityMasterController(BseSecurityMasterService source) {
        this.source = source;
    }

    @GetMapping("/status")
    public ResponseEntity<BseSecurityMasterRepository.State> status() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(source.status());
    }

    @PostMapping("/refresh")
    public ResponseEntity<BseSecurityMasterService.Refresh> refresh() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(source.refresh());
    }
}
