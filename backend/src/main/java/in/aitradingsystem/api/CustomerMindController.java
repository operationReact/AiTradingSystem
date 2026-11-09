package in.aitradingsystem.api;

import in.aitradingsystem.mind.MindReadinessService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Customer-authenticated, read-only Mintick Mind evidence.
 * /api/customer/** is protected by the CUSTOMER authorization rule.
 * No research/demo data may be mistaken for broker order authority.
 */
@RestController
@RequestMapping("/api/customer/mind/v1")
public class CustomerMindController {
    private final MindReadinessService service;

    public CustomerMindController(MindReadinessService service) {
        this.service = service;
    }

    @GetMapping("/overview")
    public ResponseEntity<MindReadinessService.Overview> overview() {
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(service.inspect());
    }
}
