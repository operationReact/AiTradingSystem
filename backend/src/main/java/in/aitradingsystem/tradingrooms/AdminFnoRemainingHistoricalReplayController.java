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
 * Protected read-only historical original-source diagnostic for STR-187..212.
 * No user/broker positions, source quotes, option strikes, account identifiers
 * or raw custody hashes leave the backend. No order routes.
 */
@RestController
@RequestMapping("/api/admin/research/fno-43")
public class AdminFnoRemainingHistoricalReplayController {
    private final Fno187212OriginalSourceReplayService original;
    private final Clock clock;

    @Autowired
    public AdminFnoRemainingHistoricalReplayController(
            Fno187212OriginalSourceReplayService original) {
        this(original, Clock.systemUTC());
    }
    AdminFnoRemainingHistoricalReplayController(
            Fno187212OriginalSourceReplayService original, Clock clock) {
        this.original=Objects.requireNonNull(original);
        this.clock=Objects.requireNonNull(clock);
    }
    public record Readiness(
        String contractVersion,String strategyId,Instant checkedAsOf,
        String originalSourceAndEvaluatorState,String blockingOrEvaluatorReason,
        int originalSessionCount,int sourceQuoteCount,
        boolean independentlyAcceptedHistoricalE2E,
        boolean paperLedgerE2E,boolean liveBrokerE2E,
        boolean brokerOrdersPlaced) {}

    @GetMapping("/remaining-original-replay")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    public ResponseEntity<Readiness> inspect(
            @RequestParam("strategyId") String strategyId,
            @RequestParam("asOf") @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME)
            Instant asOf) {
        if (!Fno187212OriginalSourceReplayService.supportedStrategies()
                .contains(strategyId) || asOf == null
                || asOf.isAfter(clock.instant())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "FNO_REMAINING_HISTORICAL_SCOPE_INVALID");
        }
        var r=original.inspect(strategyId,asOf);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            new Readiness(Fno187212OriginalSourceReplayService.CONTRACT,
                strategyId,asOf,r.state(),r.reasonCode(),
                r.originalCompletedSessions(),r.originalQuotesObserved(),
                false,false,false,false));
    }
}
