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

/**
 * Protected, strictly read-only five-original-pair STR-180 source proof.
 * Only fixed status and counts leave the backend; never quote/carry values,
 * instrument identity, original SHA hashes or broker account state.
 */
@RestController
@RequestMapping("/api/admin/research/fno-43")
public class AdminFno180HistoricalReplayController {
    private static final ZoneId IST=ZoneId.of("Asia/Kolkata");
    private final Fno180VerifiedHistoricalReplayService source;
    private final Clock clock;

    @Autowired
    public AdminFno180HistoricalReplayController(
            Fno180VerifiedHistoricalReplayService source) {
        this(source,Clock.systemUTC());
    }
    AdminFno180HistoricalReplayController(
            Fno180VerifiedHistoricalReplayService source,Clock clock) {
        this.source=Objects.requireNonNull(source);
        this.clock=Objects.requireNonNull(clock);
    }

    public record Readiness(String contractVersion,String strategyId,
        Instant historicalDecisionAsOf,String state,String evaluatorReasonCode,
        int originalPairedSessionsObserved,boolean independentHistoricalE2E,
        boolean paperLedgerE2E,boolean liveBrokerE2E,
        boolean ordersPlaced) {}

    @GetMapping("/historical-basis-replay")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    public ResponseEntity<Readiness> inspect(
            @RequestParam("equityKey") String equityKey,
            @RequestParam("futuresKey") String futuresKey,
            @RequestParam("expiry") @DateTimeFormat(iso=DateTimeFormat.ISO.DATE)
            LocalDate expiry,
            @RequestParam("lotSize") int lotSize,
            @RequestParam("asOf") @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME)
            Instant asOf) {
        if (equityKey==null ||
                !equityKey.matches("NSE_EQ\\|[A-Za-z0-9._-]{1,64}")
                || futuresKey==null ||
                !futuresKey.matches("NSE_FO\\|[A-Za-z0-9._-]{1,64}")
                || expiry==null || lotSize<1 || lotSize>100000
                || asOf==null || asOf.isAfter(clock.instant())
                || expiry.isBefore(asOf.atZone(IST).toLocalDate()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "FNO180_HISTORICAL_REPLAY_SCOPE_INVALID");
        var proof=source.inspect(equityKey,futuresKey,expiry,lotSize,asOf);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            new Readiness("minttick.fno180.original-expiry-basis-replay.v1",
                FnoExpiryBasisReversionV1.STRATEGY_ID,asOf,
                proof.state(),proof.evaluatorReasonCode(),
                proof.verifiedPairedSessions(),
                false,false,false,false));
    }
}
