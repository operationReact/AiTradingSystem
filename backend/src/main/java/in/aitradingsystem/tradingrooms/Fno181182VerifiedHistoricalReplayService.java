package in.aitradingsystem.tradingrooms;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Original-rights-joined V106 historical NIFTY CE/PE -> actual STR-181/182
 * Java evaluators. No mock market fallback or order path. Each quote, index
 * close, contract, fee and budget must be attested BEFORE decision as-of.
 * Neither positive evaluator output nor a digest is historical market E2E.
 */
@Service
public final class Fno181182VerifiedHistoricalReplayService {
    public static final String CONTRACT =
        "minttick.fno.option-long-historical-source-evaluator.v1";
    private static final int MAX_WINDOWS=64;
    private final FnoOptionsResearchService evaluator;
    private final Clock clock;
    private final String configuredCe,configuredPe;

    @Autowired
    public Fno181182VerifiedHistoricalReplayService(
            FnoOptionsResearchService evaluator,
            @Value("${minttick.fno-research.long-option-ce-instrument-key:}") String ce,
            @Value("${minttick.fno-research.long-option-pe-instrument-key:}") String pe) {
        this(evaluator,Clock.systemUTC(),ce,pe);
    }
    Fno181182VerifiedHistoricalReplayService(
            FnoOptionsResearchService evaluator,Clock clock,String ce,String pe) {
        this.evaluator=Objects.requireNonNull(evaluator);
        this.clock=Objects.requireNonNull(clock);
        this.configuredCe=ce==null?"":ce.trim();
        this.configuredPe=pe==null?"":pe.trim();
    }

    public record Evidence(
        String strategyId, Instant asOf, String state, String evaluatorStatus,
        String reason, int originalCompletedCloses, int originalQuotes,
        boolean independentHistoricalE2E, boolean paperLedgerE2E,
        boolean liveBrokerE2E, boolean brokerOrdersPlaced) {}
    public record Batch(
        String strategyId, String state, String reason,
        int decisions, int researchCandidates, int noSignals, int blocked,
        String deterministicDigest, boolean independentHistoricalE2E,
        boolean paperLedgerE2E, boolean liveBrokerE2E,
        boolean brokerOrdersPlaced, List<Evidence> windows) {}

    /**
     * Only exact operator-configured source-listed keys; arbitrary option
     * symbols cannot be selected by a caller to escape source restrictions.
     */
    public Evidence inspect(String strategyId,String instrumentKey,Instant asOf) {
        String configured=FnoLongCallV1.STRATEGY_ID.equals(strategyId)
            ? configuredCe : FnoLongPutV1.STRATEGY_ID.equals(strategyId)
            ? configuredPe : "";
        if(configured.isBlank() || !configured.matches("NSE_FO\\|[A-Za-z0-9._-]{1,80}")
                || !configured.equals(instrumentKey) || asOf==null
                || asOf.isAfter(clock.instant())
                || asOf.isBefore(clock.instant().minus(366,ChronoUnit.DAYS)))
            return denied(strategyId,asOf,"HISTORICAL_OPTION_SCOPE_OR_CONTRACT_INVALID");

        final FnoFuturesResearchService.StrategyStatus outcome;
        try {
            // Existing V106 SELECTs independently verify original rights
            // and first-available/captured <= asOf, then invoke the actual
            // FnoLongCallV1 or FnoLongPutV1 evaluator (one side only).
            outcome=evaluator.evaluateOne(strategyId,asOf);
        } catch(RuntimeException unavailable) {
            return denied(strategyId,asOf,"ORIGINAL_OPTION_SOURCE_UNAVAILABLE");
        }
        if(outcome==null || !strategyId.equals(outcome.strategyId())
                || !asOf.equals(outcome.evaluatedAt()))
            return denied(strategyId,asOf,"ORIGINAL_EVALUATOR_RESULT_INVALID");
        String expected=FnoLongCallV1.STRATEGY_ID.equals(strategyId)
            ? "VERIFIED_NIFTY_CE_SOURCE_RESEARCH_ONLY"
            : "VERIFIED_NIFTY_PE_SOURCE_RESEARCH_ONLY";
        boolean allowed=expected.equals(outcome.inputState())
            && outcome.historySessions()>=35 && outcome.historySessions()<=90
            && outcome.contractsResolved()==1 && outcome.freshQuotes()==2
            && ("RESEARCH_CANDIDATE".equals(outcome.status())
                || "NO_SIGNAL".equals(outcome.status()))
            && outcome.reasonCode()!=null
            && outcome.reasonCode().matches("[A-Z0-9_]{4,128}");
        if(!allowed) return denied(strategyId,asOf,
            "ORIGINAL_SOURCE_OR_EVALUATOR_GATES_UNMET");
        return new Evidence(strategyId,asOf,"SOURCE_V106_EVALUATOR_RESEARCH_ONLY",
            outcome.status(),outcome.reasonCode(),outcome.historySessions(),
            outcome.freshQuotes(),false,false,false,false);
    }

    /** Bounded, monotonically increasing, deterministic as-of source replay. */
    public Batch replay(String strategyId,String instrumentKey,List<Instant> asOfs) {
        if(asOfs==null||asOfs.isEmpty()||asOfs.size()>MAX_WINDOWS)
            return blockedBatch(strategyId,"REPLAY_WINDOW_SCOPE_INVALID");
        Instant previous=null;
        for(Instant at:asOfs) {
            if(at==null || (previous!=null&&!at.isAfter(previous))
                    || at.isAfter(clock.instant())
                    || at.isBefore(clock.instant().minus(366,ChronoUnit.DAYS)))
                return blockedBatch(strategyId,"REPLAY_WINDOW_CHRONOLOGY_INVALID");
            previous=at;
        }
        var rows=new ArrayList<Evidence>();
        int candidates=0,quiet=0,blocked=0;
        for(Instant at:asOfs) {
            var result=inspect(strategyId,instrumentKey,at);
            rows.add(result);
            if("RESEARCH_CANDIDATE".equals(result.evaluatorStatus())) candidates++;
            else if("NO_SIGNAL".equals(result.evaluatorStatus())) quiet++;
            else blocked++;
        }
        return new Batch(strategyId,blocked==0
                ? "EVALUATOR_REPLAY_RESEARCH_ONLY_NOT_E2E"
                : "INCOMPLETE_RIGHTS_ATTESTED_HISTORICAL_REPLAY",
            blocked==0?"AFTER_COST_OOS_AND_INDEPENDENT_SOURCE_SIGNOFF_MISSING"
                :"SOME_WINDOWS_MISSING_VERIFIED_SOURCE",
            rows.size(),candidates,quiet,blocked,shaOf(rows),
            false,false,false,false,List.copyOf(rows));
    }

    private static Evidence denied(String id,Instant at,String reason) {
        return new Evidence(id,at,"BLOCKED_NOT_HISTORICAL_E2E",
            "NOT_RUN",reason,0,0,false,false,false,false);
    }
    private static Batch blockedBatch(String id,String reason) {
        return new Batch(id,"BLOCKED_NOT_HISTORICAL_E2E",reason,
            0,0,0,0,null,false,false,false,false,List.of());
    }
    private static String shaOf(List<Evidence> rows) {
        try {
            var hash=MessageDigest.getInstance("SHA-256");
            for(var r:rows) {
                String value=r.strategyId()+"|"+r.asOf()+"|"+r.state()
                    +"|"+r.evaluatorStatus()+"|"+r.reason()
                    +"|"+r.originalCompletedCloses()+"|"+r.originalQuotes()+"\n";
                hash.update(value.getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(hash.digest());
        } catch(NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA256_UNAVAILABLE",impossible);
        }
    }
}
