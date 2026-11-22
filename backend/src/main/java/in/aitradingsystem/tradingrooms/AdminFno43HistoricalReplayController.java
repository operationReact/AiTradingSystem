package in.aitradingsystem.tradingrooms;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
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
 * Protected, read-only original rights-joined futures historical replay
 * diagnostics. Invokes actual Java evaluators for STR-174/176/177 only where
 * existing V100/V102 source entitlements and historical custody permit it.
 *
 * The response is counts and fixed reason codes only: no private prices,
 * contract keys, original custody hashes, broker positions or account data.
 * This route cannot submit/cancel broker orders or change source permissions.
 */
@RestController
@RequestMapping("/api/admin/research/fno-43")
public class AdminFno43HistoricalReplayController {
    private static final List<String> ENABLED = List.of(
        FnoFuturesPriceOiDivergenceV1.STRATEGY_ID,
        FnoFuturesMomentumV1.STRATEGY_ID,
        FnoFuturesMeanReversionV1.STRATEGY_ID
    );
    private final Fno174VerifiedHistoricalReplayService str174;
    private final Fno176VerifiedHistoricalReplayService str176;
    private final Fno177VerifiedHistoricalReplayService str177;
    private final Fno179VerifiedHistoricalReplayService str179;
    private final Fno180VerifiedHistoricalReplayService str180;
    private final Clock clock;

    @Autowired
    public AdminFno43HistoricalReplayController(
            Fno174VerifiedHistoricalReplayService str174,
            Fno176VerifiedHistoricalReplayService str176,
            Fno177VerifiedHistoricalReplayService str177,
            Fno179VerifiedHistoricalReplayService str179,
            Fno180VerifiedHistoricalReplayService str180) {
        this(str174,str176,str177,str179,str180,Clock.systemUTC());
    }
    AdminFno43HistoricalReplayController(
            Fno174VerifiedHistoricalReplayService str174,
            Fno176VerifiedHistoricalReplayService str176,
            Fno177VerifiedHistoricalReplayService str177,
            Fno179VerifiedHistoricalReplayService str179,
            Fno180VerifiedHistoricalReplayService str180, Clock clock) {
        this.str174=Objects.requireNonNull(str174);
        this.str176=Objects.requireNonNull(str176);
        this.str177=Objects.requireNonNull(str177);
        this.str179=Objects.requireNonNull(str179);
        this.str180=Objects.requireNonNull(str180);
        this.clock=Objects.requireNonNull(clock);
    }

    public record ReplayReadiness(
        String contractVersion, String strategyId, Instant checkedAsOf,
        String sourceAndEvaluatorState, String blockingOrEvaluatorReason,
        int originalSourceRowsObserved, int originalSessionsEvaluated,
        boolean independentHistoricalE2EAccepted,
        boolean paperLedgerE2EValidated, boolean liveBrokerE2EValidated,
        boolean brokerOrdersPlaced) {}

    @GetMapping("/historical-replay")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    public ResponseEntity<ReplayReadiness> inspect(
            @RequestParam("strategyId") String strategyId,
            @RequestParam("contractKey") String contractKey,
            @RequestParam("asOf") @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME)
            Instant asOf) {
        if (!ENABLED.contains(strategyId)
                || contractKey == null
                || !contractKey.matches("NSE_FO\\|[A-Za-z0-9._-]{1,64}")
                || asOf == null || asOf.isAfter(clock.instant())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "FNO_HISTORICAL_REPLAY_READ_SCOPE_INVALID");
        }
        String state, reason;
        int rows, sessions;
        switch (strategyId) {
            case FnoFuturesPriceOiDivergenceV1.STRATEGY_ID -> {
                var result = str174.inspect(contractKey,asOf);
                state=result.state();
                reason=result.reason();
                rows=result.originalSourceRows();
                sessions=result.completedSessions();
            }
            case FnoFuturesMomentumV1.STRATEGY_ID -> {
                var result = str176.inspect(contractKey,asOf);
                state=result.state();
                reason=result.reasonCode();
                rows=result.originalRows();
                sessions=result.sessionWindow();
            }
            case FnoFuturesMeanReversionV1.STRATEGY_ID -> {
                var result = str177.inspect(contractKey,asOf);
                state=result.state();
                reason=result.evaluatorReasonCode();
                rows=result.originalRowsObserved();
                sessions=result.verifiedCompletedSessions();
            }
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "FNO_HISTORICAL_REPLAY_STRATEGY_UNSUPPORTED");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            new ReplayReadiness("minttick.fno43.original-historical-replay-readiness.v1",
                strategyId,asOf,state,reason,rows,sessions,
                false,false,false,false));
    }

    /**
     * STR-179 needs the original instrument expiry and lot size to query
     * precisely one contract's three genuine rights-joined depth snapshots.
     * No market quotes, account fields, instrument key, or digest in output.
     */
    @GetMapping("/historical-liquidity-replay")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    public ResponseEntity<ReplayReadiness> inspectLiquidity(
        @RequestParam("contractKey") String contractKey,
        @RequestParam("expiry") @DateTimeFormat(iso=DateTimeFormat.ISO.DATE)
        java.time.LocalDate expiry,
        @RequestParam("lotSize") int lotSize,
        @RequestParam("asOf") @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME)
        Instant asOf) {
        if(contractKey==null||
            !contractKey.matches("NSE_FO\\|[A-Za-z0-9._-]{1,64}")||
            expiry==null||lotSize<1||lotSize>100000||
            asOf==null||asOf.isAfter(clock.instant())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "FNO_ORIGINAL_LIQUIDITY_SCOPE_INVALID");
        }
        var result=str179.inspect(contractKey,expiry,lotSize,asOf);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            new ReplayReadiness("minttick.fno43.original-historical-replay-readiness.v1",
                result.strategyId(),asOf,result.state(),result.reason(),
                result.originalSourceSnapshots(),result.evaluatorSnapshots(),
                false,false,false,false));
    }

    /**
     * STR-180 uses distinct original NSE_EQ and NSE_FO contracts with
     * separately verified cash/futures calendars, source and carry cost rights.
     * This does not model settlement/fills and cannot activate LIVE trading.
     */
    @GetMapping("/historical-basis-replay")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    public ResponseEntity<ReplayReadiness> inspectBasis(
        @RequestParam("spotKey") String spotKey,
        @RequestParam("contractKey") String contractKey,
        @RequestParam("expiry") @DateTimeFormat(iso=DateTimeFormat.ISO.DATE)
        java.time.LocalDate expiry,
        @RequestParam("lotSize") int lotSize,
        @RequestParam("asOf") @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME)
        Instant asOf) {
        if(spotKey==null||!spotKey.matches("NSE_EQ\\|[A-Za-z0-9._-]{1,64}")||
            contractKey==null||
            !contractKey.matches("NSE_FO\\|[A-Za-z0-9._-]{1,64}")||
            expiry==null||lotSize<1||lotSize>100000||
            asOf==null||asOf.isAfter(clock.instant())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "FNO_ORIGINAL_BASIS_REPLAY_SCOPE_INVALID");
        }
        var result=str180.inspect(spotKey,contractKey,expiry,lotSize,asOf);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            new ReplayReadiness("minttick.fno43.original-historical-replay-readiness.v1",
                result.strategyId(),asOf,result.state(),result.reason(),
                result.sourcePairs(),result.evaluatedPairs(),
                false,false,false,false));
    }
}
