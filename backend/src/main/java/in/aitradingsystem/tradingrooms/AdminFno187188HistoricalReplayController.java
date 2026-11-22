package in.aitradingsystem.tradingrooms;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Owner-private original broker-settled stock historical research diagnostics.
 * The broker account scope, stock and CE/PE keys are chosen by the already
 * configured protected source, NEVER by a request or response body.
 * No customer market-data or broker account/holding details are exposed.
 */
@RestController
@RequestMapping("/api/admin/research/fno-43")
public class AdminFno187188HistoricalReplayController {
    private final Fno187188VerifiedHistoricalReplayService replay;
    private final Clock clock;

    @Autowired
    public AdminFno187188HistoricalReplayController(
            Fno187188VerifiedHistoricalReplayService replay) {
        this(replay,Clock.systemUTC());
    }
    AdminFno187188HistoricalReplayController(
            Fno187188VerifiedHistoricalReplayService replay,Clock clock) {
        this.replay=Objects.requireNonNull(replay);
        this.clock=Objects.requireNonNull(clock);
    }

    public record Readiness(
        String contractVersion,String strategyId,Instant checkedAsOf,
        String state,String blockingOrEvaluatorReason,
        int originalEquitySessions,int verifiedSettledOwnedOptionContracts,
        boolean independentlyAcceptedHistoricalE2E,
        boolean paperLedgerE2E,boolean liveBrokerE2E,boolean ordersPlaced) {}

    @GetMapping("/owned-equity-historical-replay")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    public ResponseEntity<Readiness> inspect(
            @RequestParam("strategyId") String strategyId,
            @RequestParam("asOf") @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME)
            Instant asOf) {
        if (!(FnoProtectivePutV1.STRATEGY_ID.equals(strategyId)
                || FnoCoveredCallV1.STRATEGY_ID.equals(strategyId))
                || asOf==null || asOf.isAfter(clock.instant())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "FNO_OWNED_OPTION_HISTORICAL_READ_SCOPE_INVALID");
        }
        var evidence=replay.inspect(strategyId,asOf);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            new Readiness(Fno187188VerifiedHistoricalReplayService.CONTRACT,
                strategyId,asOf,evidence.state(),evidence.reasonCode(),
                evidence.verifiedOriginalEquitySessions(),
                evidence.settledBrokerOwnedContracts(),
                false,false,false,false));
    }
}
