package in.aitradingsystem.tradingrooms;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
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
 * Exact STR-180 source-joined NSE_EQ/NSE_FO historical basis diagnostics.
 * Separate route because the existing futures/long-option route accepts
 * just a single contractKey and cannot prove deliverable matched NSE_EQ.
 * Never returns original quotes, rights digests or broker information.
 */
@RestController
@RequestMapping("/api/admin/research/fno-43")
public class AdminFno180HistoricalReplayController {
    private final Fno180VerifiedHistoricalReplayService service;
    private final Clock clock;

    @Autowired
    public AdminFno180HistoricalReplayController(
        Fno180VerifiedHistoricalReplayService service) {
        this(service,Clock.systemUTC());
    }

    AdminFno180HistoricalReplayController(
        Fno180VerifiedHistoricalReplayService service, Clock clock) {
        this.service=Objects.requireNonNull(service);
        this.clock=Objects.requireNonNull(clock);
    }

    public record ReadOnlyResult(
        String version,String strategyId,Instant checkedAsOf,
        String state,String evaluatorReason,int originalSourcePairs,
        int completedSessionsEvaluated,
        boolean independentlyVerifiedHistoricalMarketE2E,
        boolean paperLedgerE2E,
        boolean liveBrokerE2E,
        boolean ordersPlaced) {}

    @GetMapping("/expiry-basis-replay")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    public ResponseEntity<ReadOnlyResult> inspect(
        @RequestParam("spotKey") String spotKey,
        @RequestParam("contractKey") String futureKey,
        @RequestParam("expiry") @DateTimeFormat(iso=DateTimeFormat.ISO.DATE)
        LocalDate expiry,
        @RequestParam("lotSize") int lotSize,
        @RequestParam("asOf") @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME)
        Instant asOf) {
        if(spotKey==null||!spotKey.matches("NSE_EQ\\|[A-Za-z0-9._-]{1,64}")||
           futureKey==null||!futureKey.matches("NSE_FO\\|[A-Za-z0-9._-]{1,64}")||
           expiry==null||lotSize<1||lotSize>100000||
           asOf==null||asOf.isAfter(clock.instant()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "FNO180_ORIGINAL_REPLAY_SCOPE_INVALID");

        var result=service.inspect(spotKey,futureKey,expiry,lotSize,asOf);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            new ReadOnlyResult("minttick.fno180.historical-replay-readonly.v1",
                FnoExpiryBasisReversionV1.STRATEGY_ID,asOf,
                result.state(),result.reason(),result.sourcePairs(),
                result.evaluatedPairs(),false,false,false,false));
    }
}
