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
 * Source-joined, point-in-time structural replay for STR-183..186.
 *
 * <p>Original source stores require model-inference research rights for the
 * exact two-leg provider source, underlying index close, exchange-time
 * bid/ask/depth/OI, contract, fee and account-margin receipts. The EXISTING
 * four typed Java evaluators run via FnoDebit/CreditSpreadResearchService.
 * No order, provider download, real-data export, permission mutation, or
 * independent after-cost historical E2E acceptance can occur here.
 */
@Service
public final class Fno183186VerifiedHistoricalReplayService {
    public static final String CONTRACT="minttick.fno.debit-credit-original-replay.v1";
    private static final int MAX_WINDOWS=64;
    private final FnoDebitSpreadResearchService debit;
    private final FnoCreditSpreadResearchService credit;
    private final Clock clock;
    private final String[] keys;

    @Autowired
    public Fno183186VerifiedHistoricalReplayService(
            FnoDebitSpreadResearchService debit,
            FnoCreditSpreadResearchService credit,
            @Value("${minttick.fno-research.debit-bull-lower-ce-key:}") String dBullLower,
            @Value("${minttick.fno-research.debit-bull-upper-ce-key:}") String dBullUpper,
            @Value("${minttick.fno-research.debit-bear-lower-pe-key:}") String dBearLower,
            @Value("${minttick.fno-research.debit-bear-upper-pe-key:}") String dBearUpper,
            @Value("${minttick.fno-research.credit-bull-lower-pe-key:}") String cBullLower,
            @Value("${minttick.fno-research.credit-bull-upper-pe-key:}") String cBullUpper,
            @Value("${minttick.fno-research.credit-bear-lower-ce-key:}") String cBearLower,
            @Value("${minttick.fno-research.credit-bear-upper-ce-key:}") String cBearUpper) {
        this(debit,credit,Clock.systemUTC(),dBullLower,dBullUpper,
            dBearLower,dBearUpper,cBullLower,cBullUpper,cBearLower,cBearUpper);
    }
    Fno183186VerifiedHistoricalReplayService(
            FnoDebitSpreadResearchService debit,
            FnoCreditSpreadResearchService credit, Clock clock,
            String dBullLower,String dBullUpper,String dBearLower,String dBearUpper,
            String cBullLower,String cBullUpper,String cBearLower,String cBearUpper) {
        this.debit=Objects.requireNonNull(debit);
        this.credit=Objects.requireNonNull(credit);
        this.clock=Objects.requireNonNull(clock);
        this.keys=new String[]{safe(dBullLower),safe(dBullUpper),
            safe(dBearLower),safe(dBearUpper),safe(cBullLower),safe(cBullUpper),
            safe(cBearLower),safe(cBearUpper)};
    }
    private static String safe(String v) {return v==null?"":v.trim();}

    /** No contract IDs, private quotes, margin account or raw source hashes. */
    public record Evidence(String strategyId,Instant asOf,String state,
        String evaluatorStatus,String reason,int verifiedCloses,int originalLegQuotes,
        boolean independentHistoricalE2E,boolean paperLedgerE2E,
        boolean liveBrokerE2E,boolean brokerOrdersPlaced) {}
    public record Batch(String strategyId,String state,String reason,
        int windows,int candidates,int noSignals,int blocked,String runSha256,
        boolean independentHistoricalE2E,boolean paperLedgerE2E,
        boolean liveBrokerE2E,boolean brokerOrdersPlaced,List<Evidence> results) {}

    public Evidence inspect(String id,String selectedLegKey,Instant at) {
        int n;
        if(FnoBullCallDebitSpreadV1.STRATEGY_ID.equals(id))n=0;
        else if(FnoBearPutDebitSpreadV1.STRATEGY_ID.equals(id))n=1;
        else if(FnoBullPutCreditSpreadV1.STRATEGY_ID.equals(id))n=2;
        else if(FnoBearCallCreditSpreadV1.STRATEGY_ID.equals(id))n=3;
        else return denied(id,at,"HISTORICAL_SPREAD_STRATEGY_UNSUPPORTED");

        // Primary instrument is long lower CE, long higher PE, short higher
        // PE, short lower CE respectively; both exact other-leg keys must
        // be configured. No arbitrary option spread supplied by a caller.
        int lo=n*2,hi=lo+1;
        String lower=keys[lo],upper=keys[hi];
        String primary=switch(n){case 0,3->lower;case 1,2->upper;
            default->throw new IllegalStateException("UNREACHABLE");};
        if(!validKey(lower)||!validKey(upper)||lower.equals(upper)
                || !primary.equals(selectedLegKey) || at==null
                || at.isAfter(clock.instant())
                || at.isBefore(clock.instant().minus(366,ChronoUnit.DAYS)))
            return denied(id,at,"HISTORICAL_SPREAD_SOURCE_SCOPE_INVALID");

        final FnoFuturesResearchService.StrategyStatus source;
        try {
            source=n<2?debit.evaluateOne(id,at):credit.evaluateOne(id,at);
        } catch(RuntimeException unavailable) {
            // Never expose SQL, registered broker account or order history.
            return denied(id,at,"ORIGINAL_TWO_LEG_SOURCE_UNAVAILABLE");
        }
        String expected=switch(n){
            case 0->"VERIFIED_TWO_LEG_CE_DEBIT_SPREAD_RESEARCH_NO_ORDER";
            case 1->"VERIFIED_TWO_LEG_PE_DEBIT_SPREAD_RESEARCH_NO_ORDER";
            case 2->"VERIFIED_TWO_LEG_PE_CREDIT_SPREAD_RESEARCH_NO_ORDER";
            case 3->"VERIFIED_TWO_LEG_CE_CREDIT_SPREAD_RESEARCH_NO_ORDER";
            default->throw new IllegalStateException("UNREACHABLE");};
        if(source==null||!id.equals(source.strategyId())||!at.equals(source.evaluatedAt())
                || !expected.equals(source.inputState())
                || source.historySessions()<35||source.historySessions()>90
                || source.contractsResolved()!=2||source.freshQuotes()!=3
                || !("RESEARCH_CANDIDATE".equals(source.status())
                    ||"NO_SIGNAL".equals(source.status()))
                || source.reasonCode()==null
                || !source.reasonCode().matches("[A-Z0-9_]{4,128}"))
            return denied(id,at,"ORIGINAL_TWO_LEG_SOURCE_OR_RISK_GATES_UNMET");
        return new Evidence(id,at,"ORIGINAL_TWO_LEG_EVALUATOR_RESEARCH_ONLY",
            source.status(),source.reasonCode(),source.historySessions(),
            source.freshQuotes(),false,false,false,false);
    }

    public Batch replay(String id,String selectedLegKey,List<Instant> decisions) {
        if(decisions==null||decisions.isEmpty()||decisions.size()>MAX_WINDOWS)
            return blockedBatch(id,"REPLAY_WINDOW_SCOPE_INVALID");
        Instant prev=null;
        for(Instant at:decisions) {
            if(at==null||(prev!=null&&!at.isAfter(prev))
                    || at.isAfter(clock.instant())
                    || at.isBefore(clock.instant().minus(366,ChronoUnit.DAYS)))
                return blockedBatch(id,"REPLAY_WINDOW_CHRONOLOGY_INVALID");
            prev=at;
        }
        List<Evidence> results=new ArrayList<>();
        int candidates=0,quiet=0,blocked=0;
        for(Instant at:decisions) {
            var r=inspect(id,selectedLegKey,at);
            results.add(r);
            if("RESEARCH_CANDIDATE".equals(r.evaluatorStatus()))candidates++;
            else if("NO_SIGNAL".equals(r.evaluatorStatus()))quiet++;
            else blocked++;
        }
        return new Batch(id,blocked==0
                ?"EVALUATORS_RAN_RESEARCH_ONLY_NOT_E2E"
                :"INCOMPLETE_ORIGINAL_SPREAD_HISTORY",
            blocked==0?"AFTER_COST_OUT_OF_SAMPLE_EXECUTION_NOT_PROVEN"
                :"ORIGINAL_RIGHTS_OR_SOURCE_WINDOWS_MISSING",
            results.size(),candidates,quiet,blocked,sha(results),
            false,false,false,false,List.copyOf(results));
    }
    private static boolean validKey(String v) {
        return v!=null&&v.matches("NSE_FO\\|[A-Za-z0-9._-]{1,80}");
    }
    private static Evidence denied(String id,Instant at,String reason) {
        return new Evidence(id,at,"BLOCKED_NOT_HISTORICAL_E2E",
            "NOT_RUN",reason,0,0,false,false,false,false);
    }
    private static Batch blockedBatch(String id,String reason) {
        return new Batch(id,"BLOCKED_NOT_HISTORICAL_E2E",reason,
            0,0,0,0,null,false,false,false,false,List.of());
    }
    private static String sha(List<Evidence> rows) {
        try {
            MessageDigest hash=MessageDigest.getInstance("SHA-256");
            for(var r:rows){
                String summary=r.strategyId()+"|"+r.asOf()+"|"+r.state()
                    +"|"+r.evaluatorStatus()+"|"+r.reason()
                    +"|"+r.verifiedCloses()+"|"+r.originalLegQuotes()+"\n";
                hash.update(summary.getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(hash.digest());
        } catch(NoSuchAlgorithmException impossible){
            throw new IllegalStateException("SHA256_UNAVAILABLE",impossible);
        }
    }
}
