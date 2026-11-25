package in.aitradingsystem.api;

import in.aitradingsystem.mind.MindOrbWalkForwardService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only ORB historical walk-forward report with data and safety gates. */
@RestController
@RequestMapping("/api/customer/mind/v1")
public final class CustomerMindOrbWalkForwardController {
    private final MindOrbWalkForwardService service;

    public CustomerMindOrbWalkForwardController(MindOrbWalkForwardService service) {
        this.service = service;
    }

    /** Research only. The report never places a paper or live order. */
    @GetMapping("/orb-walkforward")
    public ResponseEntity<MindOrbWalkForwardService.Report> walkForward() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.inspect());
    }
}
