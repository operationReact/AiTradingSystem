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
 * Restricted original-source diagnostics for STR-179 (V104 genuine L1/market
 * depth, OI and cumulative session volume) and STR-180 (V105 original
 * cash/futures matched near-cash-close pairs and carry/delivery receipts).
 *
 * These are research source-to-evaluator status probes, NOT a source market
 * feed, user endpoint, approved backtest, PAPER trade or live broker route.
 */
@RestController
@RequestMapping("/api/admin/research/fno-43")
public class AdminFno43AdditionalHistoricalReplayController {
    private final Fno179VerifiedLiquidityReplayService liquidity;
    private final Fno180VerifiedExpiryBasisReplayService basis;
    private final Clock clock;

    @Autowired
    public AdminFno43AdditionalHistoricalReplayController(
            Fno179VerifiedLiquidityReplayService liquidity,
            Fno180VerifiedExpiryBasisReplayService basis){
        this(liquidity,basis,Clock.systemUTC());
    }
    AdminFno43AdditionalHistoricalReplayController(
            Fno179VerifiedLiquidityReplayService liquidity,
            Fno180VerifiedExpiryBasisReplayService basis, Clock clock){
        this.liquidity=Objects.requireNonNull(liquidity);
        this.basis=Objects.requireNonNull(basis);
        this.clock=Objects.requireNonNull(clock);
    }

    /**
     * No raw quote, original SHA, contract key, account credential or holdings
     * is serialized back to an operator. Source may be absent and will return
     * explicit BLOCKED_NOT_HISTORICAL_E2E without a fallback.
     */
    public record SourceReadiness(
        String contractVersion, String strategyId, Instant checkedAsOf,
        String state, String reasonCode, String evaluatorStatus,
        int originalSourceObservations, int originalSessionsEvaluated,
        boolean independentMarketE2EValidated,
        boolean paperLedgerE2EValidated,
        boolean liveBrokerE2EValidated, boolean ordersPlaced) {}

    @GetMapping("/liquidity-replay")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    public ResponseEntity<SourceReadiness> liquidity(
            @RequestParam("contractKey") String contractKey,
            @RequestParam("expiry") @DateTimeFormat(iso=DateTimeFormat.ISO.DATE)
            LocalDate expiry,
            @RequestParam("lotSize") int lotSize,
            @RequestParam("asOf") @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME)
            Instant asOf) {
        scope(contractKey,expiry,lotSize,asOf);
        var result=liquidity.inspect(contractKey,expiry,lotSize,asOf);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new SourceReadiness(
                "minttick.fno43.original-source-readiness.v1",
                FnoContractLiquidityGateV1.STRATEGY_ID,asOf,
                result.state(),result.reasonCode(),result.evaluatorStatus(),
                result.originalObservations(),result.evaluatedObservations(),
                false,false,false,false));
    }

    @GetMapping("/basis-replay")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    public ResponseEntity<SourceReadiness> basis(
            @RequestParam("spotKey") String spotKey,
            @RequestParam("futuresKey") String futuresKey,
            @RequestParam("expiry") @DateTimeFormat(iso=DateTimeFormat.ISO.DATE)
            LocalDate expiry,
            @RequestParam("lotSize") int lotSize,
            @RequestParam("asOf") @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME)
            Instant asOf) {
        scope(futuresKey,expiry,lotSize,asOf);
        if(spotKey==null||!spotKey.matches("NSE_EQ\\|[A-Za-z0-9._-]{1,64}"))
            throw badScope();
        var result=basis.inspect(spotKey,futuresKey,expiry,lotSize,asOf);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new SourceReadiness(
                "minttick.fno43.original-source-readiness.v1",
                FnoExpiryBasisReversionV1.STRATEGY_ID,asOf,
                result.state(),result.reasonCode(),result.evaluatorStatus(),
                result.originalPairedSessions(),result.completedSessions(),
                false,false,false,false));
    }

    private void scope(String key,LocalDate expiry,int lotSize,Instant asOf){
        if(key==null||!key.matches("NSE_FO\\|[A-Za-z0-9._-]{1,64}")
                || expiry==null||lotSize<1||lotSize>100000
                || asOf==null||asOf.isAfter(clock.instant()))
            throw badScope();
    }
    private static ResponseStatusException badScope(){
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "FNO_ORIGINAL_REPLAY_SCOPE_INVALID");
    }
}
