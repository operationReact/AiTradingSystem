package in.aitradingsystem.tradingrooms;

import in.aitradingsystem.marketdata.FnoVerifiedStrategy203206SourceStore;
import in.aitradingsystem.marketdata.FnoVerifiedStrategy207212SourceStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * LIVE, read-only diagnostic: distinguish 43 typed evaluator callsites from
 * individually rights-attested original market source envelopes and verified
 * real NSE historical/PAPER/LIVE execution end-to-end. Not a trade path.
 *
 * In V123/V124 there are ten explicitly auditable source-envelope strategies.
 * The other 33 have separate source stores and must NOT be reported missing
 * from this custody table. No fixture, SHA or research signal proves a market
 * E2E or broker fill. Never sends raw source JSON or broker credentials.
 */
@Service
public final class Fno43RuntimeSourceAuditService {
    public static final String CONTRACT="minttick.fno43.runtime-source-audit.v1";
    public record SourceRow(
        String strategyId, String integrationState, String evaluatorStatus,
        String evaluatorReason, int reportedFreshQuotes,
        int reportedCompletedHistorySessions, String sourceEvidenceGate,
        String rightsAttestedProvider, Instant sourceEnvelopeFirstKnownAt,
        boolean independentlyVerifiedMarketE2E,
        boolean paperLedgerE2E, boolean liveBrokerE2E) {}
    public record AuditReport(
        String contractVersion, Instant asOf, int expectedStrategies,
        int distinctResearchAdaptersObserved, int strategiesWithoutAdapters,
        int originalEnvelopeSourceFamiliesAudited, int eligibleRightsAttestedEnvelopes,
        int missingOrUnlicensedOriginalEnvelopes, int independentMarketE2EValidated,
        int paperLedgerE2EValidated, int liveBrokerE2EValidated,
        boolean brokerOrdersSubmitted, boolean productionDeploymentVerified,
        List<SourceRow> strategies) {}
    private record ProofInfo(String state,String provider,Instant firstKnown) {}
    private final Fno43StrategyReadinessService readiness;
    private final FnoVerifiedStrategy203206SourceStore source203;
    private final FnoVerifiedStrategy207212SourceStore source207;

    public Fno43RuntimeSourceAuditService(
        Fno43StrategyReadinessService readiness,
        FnoVerifiedStrategy203206SourceStore source203,
        FnoVerifiedStrategy207212SourceStore source207) {
        this.readiness=Objects.requireNonNull(readiness);
        this.source203=Objects.requireNonNull(source203);
        this.source207=Objects.requireNonNull(source207);
    }

    public AuditReport inspect(Instant asOf){
        Objects.requireNonNull(asOf,"asOf");
        Fno43StrategyReadinessService.Coverage coverage;
        try{coverage=readiness.inspect(asOf);}
        catch(RuntimeException denied){
            coverage=null; // Do NOT expose secrets/log raw broker errors.
        }
        Map<String,Fno43StrategyReadinessService.StrategyRow> observed=new HashMap<>();
        // Once an ID is duplicated it remains untrusted even if a third copy
        // appears; alternating remove/reinsert must never fabricate coverage.
        Set<String> duplicateIds=new HashSet<>();
        if(coverage!=null && coverage.strategies()!=null)
            for(var r:coverage.strategies()){
                if(r==null || r.strategyId()==null)continue;
                String id=r.strategyId();
                if(duplicateIds.contains(id))continue;
                if(observed.putIfAbsent(id,r)!=null){
                    observed.remove(id);
                    duplicateIds.add(id);
                }
            }
        List<SourceRow> rows=new ArrayList<>(43);
        int eligible=0,missing=0, audited=0, integrated=0;
        for(int i=170;i<=212;i++){
            String id="strategy.STR-"+i;
            var r=observed.get(id);
            if(r!=null && r.distinctEvaluatorObserved())integrated++;
            ProofInfo proof;
            if(i>=203){
                audited++;
                proof=checkIndividualEnvelope(id,i,asOf);
                if("RIGHTS_ATTESTED_CURRENT_ENVELOPE_ONLY".equals(proof.state()))eligible++;
                else missing++;
            }else{
                proof=new ProofInfo("NOT_AUDITED_BY_THIS_SOURCE_FAMILY",null,null);
            }
            rows.add(new SourceRow(id,
                r==null?"READINESS_NOT_OBSERVED":r.integrationState(),
                r==null?"NOT_OBSERVED":r.evaluatorStatus(),
                r==null?"READINESS_UNAVAILABLE":r.reasonCode(),
                r==null?0:r.freshQuotes(),
                r==null?0:r.completedHistorySessions(),
                proof.state(),proof.provider(),proof.firstKnown(),
                false,false,false));
        }
        // Never promote a rights receipt or fixture to independently audited
        // exchange prices, paper ledger/fill/fees, or live broker execution.
        return new AuditReport(CONTRACT,asOf,43,integrated,43-integrated,
            audited,eligible,missing,0,0,0,false,false,List.copyOf(rows));
    }

    private ProofInfo checkIndividualEnvelope(String id,int numeric,Instant asOf){
        try{
            if(numeric<=206){
                Optional<FnoVerifiedStrategy203206SourceStore.Proof> p=source203.oneCurrent(id,asOf);
                if(p==null||p.isEmpty())return missing();
                var proof=p.get();
                if(proof==null||!id.equals(proof.strategyId())||
                    proof.originalFirstKnownAt()==null||
                    proof.originalFirstKnownAt().isAfter(asOf))
                    return missing();
                return new ProofInfo("RIGHTS_ATTESTED_CURRENT_ENVELOPE_ONLY",
                    proof.provider(),proof.originalFirstKnownAt());
            }
            Optional<FnoVerifiedStrategy207212SourceStore.Proof> p=source207.oneCurrent(id,asOf);
            if(p==null||p.isEmpty())return missing();
            var proof=p.get();
            if(proof==null||!id.equals(proof.strategyId())||
                proof.originalFirstKnownAt()==null||
                proof.originalFirstKnownAt().isAfter(asOf))
                return missing();
            return new ProofInfo("RIGHTS_ATTESTED_CURRENT_ENVELOPE_ONLY",
                proof.provider(),proof.originalFirstKnownAt());
        }catch(RuntimeException inaccessible){
            return new ProofInfo("SOURCE_QUERY_FAILED_NO_ATTESTATION",null,null);
        }
    }
    private static ProofInfo missing(){
        return new ProofInfo("NO_CURRENT_RIGHTS_ATTESTED_SOURCE_ENVELOPE",null,null);
    }
}
