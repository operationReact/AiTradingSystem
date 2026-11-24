package in.aitradingsystem.tradingrooms;

import java.time.LocalDate;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin/Operator only STR-176 genuine as-of historical walk-forward checkpoint.
 * Read-only: source rights and evaluator invoked per actual decision day.
 * Never exports contract keys, raw bar values, source hashes, broker identities
 * or positions. No paper/live order endpoint and no certification shortcut.
 */
@RestController
@RequestMapping("/api/admin/research/fno-43")
public class AdminFno176WalkForwardController {
    private final Fno176SourceLinkedWalkForwardV1 walkForward;

    @Autowired
    public AdminFno176WalkForwardController(
            Fno176SourceLinkedWalkForwardV1 walkForward) {
        this.walkForward = Objects.requireNonNull(walkForward);
    }

    public record Summary(
        String contractVersion, String strategyId, String state,
        String reasonCode, int sessionsReplayed, int trainingDecisions,
        int outOfSampleDecisions, int researchCandidateDecisions,
        boolean independentlyHistoricalE2ECertified,
        boolean paperOrdersPlaced, boolean liveOrdersPlaced) {}

    @GetMapping("/str176-walkforward")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    public ResponseEntity<Summary> inspect(
            @RequestParam("contractKey") String contractKey,
            @RequestParam("firstDecisionDay")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate firstDecisionDay,
            @RequestParam("lastDecisionDay")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate lastDecisionDay,
            @RequestParam("finalTrainingDay")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate finalTrainingDay) {
        // No caller-entered trades, PnL, source digests or approval flags.
        // Actual fills must arrive via separately reviewed authenticated custody.
        var result = walkForward.inspect(
            contractKey, firstDecisionDay, lastDecisionDay, finalTrainingDay, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new Summary(result.contractVersion(), result.strategyId(),
                result.state(), result.reasonCode(), result.historicalDecisions(),
                result.trainingDecisions(), result.outOfSampleDecisions(),
                result.researchCandidateDecisions(),false,false,false));
    }
}
