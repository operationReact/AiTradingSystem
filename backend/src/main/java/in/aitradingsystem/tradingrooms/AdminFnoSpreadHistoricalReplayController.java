package in.aitradingsystem.tradingrooms;

import java.time.Clock;
import java.time.Instant;
import java.util.Set;
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
 * Exact original V107/V108 debit/credit two-leg historical source diagnostics.
 * Admin/operator only. No customer market data or broker balances are returned.
 * Neither a research-only evaluator match nor this route proves real backtest
 * E2E, paper ledger, or authority to place live orders.
 */
@RestController
@RequestMapping("/api/admin/research/fno-43")
public class AdminFnoSpreadHistoricalReplayController {
    private static final Set<String> STRATEGIES=Set.of(
        FnoBullCallDebitSpreadV1.STRATEGY_ID,
        FnoBearPutDebitSpreadV1.STRATEGY_ID,
        FnoBullPutCreditSpreadV1.STRATEGY_ID,
        FnoBearCallCreditSpreadV1.STRATEGY_ID);
    private final Fno183186VerifiedHistoricalReplayService source;
    private final Clock clock;

    @Autowired
    public AdminFnoSpreadHistoricalReplayController(
            Fno183186VerifiedHistoricalReplayService source){
        this(source,Clock.systemUTC());
    }
    AdminFnoSpreadHistoricalReplayController(
            Fno183186VerifiedHistoricalReplayService source,Clock clock){
        this.source=Objects.requireNonNull(source);
        this.clock=Objects.requireNonNull(clock);
    }
    public record Readiness(String contractVersion,String strategyId,
        Instant checkedAsOf,String sourceAndEvaluatorState,
        String blockingOrEvaluatorReason,int originalDailySessions,
        int originalQuoteLegs,boolean independentHistoricalE2EAccepted,
        boolean paperLedgerE2EValidated,boolean liveBrokerE2EValidated,
        boolean brokerOrdersPlaced) {}

    @GetMapping("/spread-historical-replay")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    public ResponseEntity<Readiness> inspect(
            @RequestParam("strategyId") String strategyId,
            @RequestParam("contractKey") String exactSelectedLegKey,
            @RequestParam("asOf") @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME)
            Instant asOf){
        if(!STRATEGIES.contains(strategyId)
                || exactSelectedLegKey==null
                || !exactSelectedLegKey.matches("NSE_FO\\|[A-Za-z0-9._-]{1,80}")
                || asOf==null || asOf.isAfter(clock.instant()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "FNO_SPREAD_HISTORICAL_READ_SCOPE_INVALID");
        var r=source.inspect(strategyId,exactSelectedLegKey,asOf);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            new Readiness(Fno183186VerifiedHistoricalReplayService.CONTRACT,
                strategyId,asOf,r.state(),r.reason(),r.verifiedCloses(),
                r.originalLegQuotes(),false,false,false,false));
    }
}
