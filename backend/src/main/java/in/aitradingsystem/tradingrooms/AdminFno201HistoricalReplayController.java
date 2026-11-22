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
 * Sanitized, operator-only read of original first-known 200+ NIFTY 30-day
 * licensed IV historical research; cannot return source prices or orders.
 */
@RestController
@RequestMapping("/api/admin/research/fno-43")
public class AdminFno201HistoricalReplayController {
    private final Fno201VerifiedHistoricalReplayService source;
    private final Clock clock;

    @Autowired
    public AdminFno201HistoricalReplayController(Fno201VerifiedHistoricalReplayService source){
        this(source,Clock.systemUTC());
    }
    AdminFno201HistoricalReplayController(
        Fno201VerifiedHistoricalReplayService source,Clock clock){
        this.source=Objects.requireNonNull(source);
        this.clock=Objects.requireNonNull(clock);
    }
    public record ReadOnlyResult(
        String contractVersion,String strategyId,Instant historicalAsOf,
        String state,String reason,int originalSessions,
        int originalCurrentIvObservations,
        boolean historicalMarketE2E,boolean paperLedgerE2E,
        boolean liveBrokerE2E,boolean brokerOrdersPlaced) {}

    @GetMapping("/iv-rank-replay")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    public ResponseEntity<ReadOnlyResult> inspect(
        @RequestParam("asOf") @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME)
        Instant asOf){
        if(asOf==null||asOf.isAfter(clock.instant()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "IV_RANK_HISTORICAL_ASOF_INVALID");
        var result=source.inspect(asOf);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            new ReadOnlyResult("minttick.fno201.original-asof-iv-rank-research.v1",
                Fno201VerifiedHistoricalReplayService.STRATEGY,asOf,
                result.state(),result.reasonCode(),
                result.verifiedOriginalSessions(),result.originalCurrentIvSnapshots(),
                false,false,false,false));
    }
}
