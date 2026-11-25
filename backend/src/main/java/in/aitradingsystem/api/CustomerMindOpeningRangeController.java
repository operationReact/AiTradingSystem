package in.aitradingsystem.api;

import in.aitradingsystem.mind.MindOpeningRangeReplayService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Customer-authenticated, read-only historical replay for the opening-range strategy. */
@RestController
@RequestMapping("/api/customer/mind/v1")
public final class CustomerMindOpeningRangeController {
    private final MindOpeningRangeReplayService replay;

    public CustomerMindOpeningRangeController(MindOpeningRangeReplayService replay) {
        this.replay = replay;
    }

    /** Reconstructs the captured candidate only; never submits a PAPER or LIVE order. */
    @GetMapping("/opening-range-replay")
    public ResponseEntity<MindOpeningRangeReplayService.Replay> openingRangeReplay() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(replay.inspect());
    }
}
