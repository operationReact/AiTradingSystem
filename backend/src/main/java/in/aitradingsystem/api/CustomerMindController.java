package in.aitradingsystem.api;

import in.aitradingsystem.mind.MindReadinessService;
import in.aitradingsystem.mind.MindEvidenceLedgerService;
import in.aitradingsystem.mind.MindStrategyReplayService;
import in.aitradingsystem.mind.MindMaWalkForwardService;
import in.aitradingsystem.mind.MindProspectiveCohortService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestParam;
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
@Validated
@RequestMapping("/api/customer/mind/v1")
public class CustomerMindController {
    private final MindReadinessService service;
    private final MindEvidenceLedgerService evidence;
    private final MindStrategyReplayService replay;
    private final MindMaWalkForwardService walkForward;
    private final MindProspectiveCohortService prospective;

    public CustomerMindController(MindReadinessService service,
                                  MindEvidenceLedgerService evidence,
                                  MindStrategyReplayService replay,
                                  MindMaWalkForwardService walkForward,
                                  MindProspectiveCohortService prospective) {
        this.service = service;
        this.evidence = evidence;
        this.replay = replay;
        this.walkForward = walkForward;
        this.prospective = prospective;
    }

    @GetMapping("/evidence")
    public ResponseEntity<MindEvidenceLedgerService.Ledger> evidence(
            @RequestParam(defaultValue = "20") @Min(1) @Max(30) int limit) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(evidence.inspect(limit));
    }

    /** Historical research-only reconstruction; never submits a PAPER or LIVE order. */
    @GetMapping("/strategy-replay")
    public ResponseEntity<MindStrategyReplayService.Replay> strategyReplay() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(replay.inspect());
    }

    /** Research-only, after-assumed-cost walk-forward with explicit bias gates. */
    @GetMapping("/ma-walkforward")
    public ResponseEntity<MindMaWalkForwardService.Report> maWalkForward() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(walkForward.inspect());
    }

    /** Forward-only pre-registered multi-stock research, with no trade action. */
    @GetMapping("/prospective-cohort")
    public ResponseEntity<MindProspectiveCohortService.Status> prospectiveCohort() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(prospective.inspect());
    }

    @GetMapping("/overview")
    public ResponseEntity<MindReadinessService.Overview> overview() {
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(service.inspect());
    }
}
