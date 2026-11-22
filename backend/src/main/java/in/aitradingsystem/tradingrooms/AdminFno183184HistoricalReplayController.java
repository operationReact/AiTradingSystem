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
 * Operator-only sanitized historical replay of ORIGINAL licensed NIFTY
 * debit-spread inputs for STR-183 / STR-184. No contract identifier, raw
 * source, market quote, broker order or account field leaves this route.
 */
@RestController
@RequestMapping("/api/admin/research/fno-43")
public class AdminFno183184HistoricalReplayController {
    private final Fno183184HistoricalDebitSpreadReplayService research;
    private final Clock clock;

    @Autowired
    public AdminFno183184HistoricalReplayController(
        Fno183184HistoricalDebitSpreadReplayService research){
        this(research,Clock.systemUTC());
    }
    AdminFno183184HistoricalReplayController(
        Fno183184HistoricalDebitSpreadReplayService research,Clock clock){
        this.research=Objects.requireNonNull(research);
        this.clock=Objects.requireNonNull(clock);
    }

    public record HistoricalResult(
        String version,String strategyId,Instant checkedAsOf,String state,
        String reason,int originalIndexSessions,int verifiedOptionContracts,
        int originalQuotesObserved,boolean independentHistoricalE2E,
        boolean paperLedgerE2E,boolean liveBrokerE2E,boolean brokerOrdersPlaced) {}

    @GetMapping("/debit-spread-replay")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    public ResponseEntity<HistoricalResult> inspect(
        @RequestParam("strategyId") String strategyId,
        @RequestParam("asOf") @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME)
        Instant asOf){
        if(!("strategy.STR-183".equals(strategyId)||
              "strategy.STR-184".equals(strategyId))||
            asOf==null||asOf.isAfter(clock.instant()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "DEBIT_SPREAD_HISTORICAL_READ_SCOPE_INVALID");
        var result=research.inspect(strategyId,asOf);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            new HistoricalResult("minttick.fno183184.original-debit-historical.v1",
                strategyId,asOf,result.state(),result.reason(),
                result.completedIndexSessions(),result.contractLegs(),
                result.originalQuoteLegs(),false,false,false,false));
    }
}
