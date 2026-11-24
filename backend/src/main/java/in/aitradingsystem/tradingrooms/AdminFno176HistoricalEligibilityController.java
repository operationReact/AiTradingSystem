package in.aitradingsystem.tradingrooms;

import in.aitradingsystem.marketdata.FnoVerifiedCompletedOhlcvOiStore;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Owner/operator-only visibility into actual V102 historical-source eligibility.
 * Seven source sessions are only an input prerequisite, never an accepted
 * historical strategy result. No symbols, quotes, source hashes or orders escape.
 */
@RestController
@RequestMapping("/api/admin/research/fno-43")
public class AdminFno176HistoricalEligibilityController {
    public static final String CONTRACT="minttick.fno.str176.source-eligibility.v1";
    private final FnoVerifiedCompletedOhlcvOiStore source;
    private final Clock clock;

    @Autowired
    public AdminFno176HistoricalEligibilityController(
            FnoVerifiedCompletedOhlcvOiStore source) {
        this(source, Clock.systemUTC());
    }

    AdminFno176HistoricalEligibilityController(
            FnoVerifiedCompletedOhlcvOiStore source, Clock clock) {
        this.source=Objects.requireNonNull(source);
        this.clock=Objects.requireNonNull(clock);
    }

    public record EligibilitySnapshot(
        String contractVersion, String strategyId, Instant asOf,
        String state, long originalRows, long rightsJoinedAsOfRows,
        long possibleSevenSessionWindows,
        boolean independentHistoricalE2EValidated, boolean ordersPlaced) {}

    @GetMapping("/str176-original-source")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    public ResponseEntity<EligibilitySnapshot> inspect() {
        Instant asOf=clock.instant();
        String state="SOURCE_STORE_UNAVAILABLE_OR_UNVERIFIED";
        long stored=-1, eligible=-1, candidates=-1;
        try {
            var report=source.inspectHistoricalEligibility(asOf);
            if (report!=null) {
                stored=report.totalOriginalRows();
                eligible=report.rightsJoinedAsOfRows();
                candidates=report.possibleSevenSessionWindows();
                if (stored<0 || eligible<0 || candidates<0
                        || eligible>stored || candidates>eligible) {
                    stored=-1;
                    eligible=-1;
                    candidates=-1;
                } else if (stored==0) {
                    state="NO_ORIGINAL_V102_SOURCE_ROWS";
                } else if (eligible==0) {
                    state="NO_RIGHTS_JOINED_ASOF_SOURCE_ROWS";
                } else if (candidates==0) {
                    state="NO_SEVEN_SESSION_CANDIDATE_WINDOW";
                } else {
                    state="POTENTIAL_SOURCE_INPUT_ONLY_NOT_E2E";
                }
            }
        } catch (RuntimeException unavailable) {
            // Never serialize DB, broker, provider or entitlement details.
            stored=-1;
            eligible=-1;
            candidates=-1;
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new EligibilitySnapshot(CONTRACT,"strategy.STR-176",asOf,
                state,stored,eligible,candidates,false,false));
    }
}
