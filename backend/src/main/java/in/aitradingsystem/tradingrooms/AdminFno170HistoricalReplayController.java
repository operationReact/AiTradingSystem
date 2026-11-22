package in.aitradingsystem.tradingrooms;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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

/** STR-170 read-only original intraday source eligibility, no prices or orders. */
@RestController
@RequestMapping("/api/admin/research/fno-43")
public class AdminFno170HistoricalReplayController {
    private static final ZoneId IST=ZoneId.of("Asia/Kolkata");
    private final Fno170VerifiedHistoricalReplayService replay;
    private final Clock clock;
    @Autowired
    public AdminFno170HistoricalReplayController(
            Fno170VerifiedHistoricalReplayService replay){
        this(replay,Clock.systemUTC());
    }
    AdminFno170HistoricalReplayController(
            Fno170VerifiedHistoricalReplayService replay,Clock clock){
        this.replay=Objects.requireNonNull(replay);
        this.clock=Objects.requireNonNull(clock);
    }

    public record Readiness(String version,String strategyId,Instant decisionAsOf,
        String state,String reasonCode,int originalPairedObservations,
        boolean independentHistoricalE2E,boolean paperLedgerE2E,
        boolean liveBrokerE2E,boolean ordersPlaced) {}

    @GetMapping("/historical-carry-replay")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    public ResponseEntity<Readiness> inspect(
            @RequestParam("stockKey") String stock,
            @RequestParam("futuresKey") String future,
            @RequestParam("expiry") @DateTimeFormat(iso=DateTimeFormat.ISO.DATE)
            LocalDate expiry,
            @RequestParam("lotSize") int lot,
            @RequestParam("asOf") @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME)
            Instant asOf) {
        if(stock==null||!stock.matches("NSE_EQ\\|[A-Za-z0-9._-]{1,64}")
                ||future==null||!future.matches("NSE_FO\\|[A-Za-z0-9._-]{1,64}")
                ||expiry==null||lot<1||lot>100000||asOf==null
                ||asOf.isAfter(clock.instant())
                ||expiry.isBefore(asOf.atZone(IST).toLocalDate()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "FNO170_HISTORICAL_REPLAY_SCOPE_INVALID");
        var result=replay.inspect(stock,future,expiry,lot,asOf);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            new Readiness("minttick.fno170.original-intraday-carry-replay.v1",
                FnoFuturesBasisCarryV1.STRATEGY_ID,asOf,result.state(),
                result.reasonCode(),result.originalPairedObservations(),
                false,false,false,false));
    }
}
