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
 * STR-179 private, operator-only original source proof. No market quotes,
 * hashes, identities, or account state returned: only sanitized result counts.
 */
@RestController
@RequestMapping("/api/admin/research/fno-43")
public class AdminFno179HistoricalReplayController {
    private static final ZoneId IST=ZoneId.of("Asia/Kolkata");
    private final Fno179VerifiedHistoricalReplayService source;
    private final Clock clock;

    @Autowired
    public AdminFno179HistoricalReplayController(
            Fno179VerifiedHistoricalReplayService source) {
        this(source,Clock.systemUTC());
    }
    AdminFno179HistoricalReplayController(
            Fno179VerifiedHistoricalReplayService source,Clock clock) {
        this.source=Objects.requireNonNull(source);
        this.clock=Objects.requireNonNull(clock);
    }

    public record Readiness(String contractVersion,String strategyId,
        Instant decisionAsOf,String state,String reasonCode,
        int originalQuoteObservations,boolean independentHistoricalE2E,
        boolean paperLedgerE2E,boolean liveBrokerE2E,
        boolean ordersPlaced) {}

    @GetMapping("/historical-liquidity-replay")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    public ResponseEntity<Readiness> inspect(
            @RequestParam("contractKey") String key,
            @RequestParam("expiry") @DateTimeFormat(iso=DateTimeFormat.ISO.DATE)
            LocalDate expiry,
            @RequestParam("lotSize") int lotSize,
            @RequestParam("asOf") @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME)
            Instant asOf) {
        if (key==null || !key.matches("NSE_FO\\|[A-Za-z0-9._-]{1,64}")
                || expiry==null || lotSize<1 || lotSize>100000
                || asOf==null || asOf.isAfter(clock.instant())
                || expiry.isBefore(asOf.atZone(IST).toLocalDate())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "FNO179_HISTORICAL_REPLAY_SCOPE_INVALID");
        }
        var status=source.inspect(key,expiry,lotSize,asOf);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            new Readiness("minttick.fno179.original-liquidity-replay.v1",
                FnoContractLiquidityGateV1.STRATEGY_ID,asOf,
                status.state(),status.reasonCode(),status.originalQuoteObservations(),
                false,false,false,false));
    }
}
