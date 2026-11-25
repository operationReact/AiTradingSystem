package in.aitradingsystem.tradingrooms;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.aitradingsystem.marketdata.FnoVerifiedStrategy203206SourceStore;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** STR-206. Read-only typed existing deterministic evaluator, single original
 * source custody and 6 research-use receipts. No real market or account
 * rights are inferred from tests, and NO broker or PAPER/LIVE orders exist.
 */
@Service
public final class FnoTailRiskPutHedgeResearchService {
    public record Evidence(List<FnoTailRiskPutHedgeV1.Holding> holdings,
            FnoTailRiskPutHedgeV1.PutContract put,
            FnoTailRiskPutHedgeV1.Snapshot snapshot,
            List<FnoTailRiskPutHedgeV1.CompletedSession> sessions,
            FnoTailRiskPutHedgeV1.Policy policy) {}
    private final FnoVerifiedStrategy203206SourceStore source;
    private final ObjectMapper mapper;
    private final boolean sourceRights;
    @Autowired
    public FnoTailRiskPutHedgeResearchService(FnoVerifiedStrategy203206SourceStore source,ObjectMapper mapper,
        @Value("$" + "{minttick.fno-research.str-206-source-rights-verified:false}")
        boolean sourceRights){
        this.source=source;
        this.mapper=mapper.copy().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,true);
        this.sourceRights=sourceRights;
    }
    public FnoFuturesResearchService.StrategyStatus evaluate(Instant now){
        if(now==null)throw new IllegalArgumentException("STR_206_AS_OF_REQUIRED");
        if(!sourceRights)return denied(now,"STR_206_SOURCE_RIGHTS_UNVERIFIED",true);
        if(source==null)return denied(now,"STR_206_PROVENANCE_SOURCE_MISSING",false);
        final Optional<FnoVerifiedStrategy203206SourceStore.Proof> p;
        try {p=source.oneCurrent("strategy.STR-206",now);}
        catch(RuntimeException unavailable){
            return denied(now,"STR_206_ORIGINAL_SOURCE_READ_FAILED",false);
        }
        if(p==null||p.isEmpty())
            return denied(now,"STR_206_GENUINE_ORIGINAL_SOURCE_EVIDENCE_MISSING",false);
        var proof=p.get();
        if(proof==null||!"strategy.STR-206".equals(proof.strategyId())
                ||!FnoTypedResearchProvenanceGate.valid(
                    mapper,proof.json(),proof.provider(),proof.originalFirstKnownAt(),now))
            return denied(now,"STR_206_PROVIDER_ORIGINAL_TIME_PROVENANCE_INVALID",false);
        final Evidence v;
        try {v=mapper.readValue(proof.json(),Evidence.class);}
        catch(Exception malformed){
            return denied(now,"STR_206_TYPED_SOURCE_PAYLOAD_INVALID",false);
        }
        if(v==null||v.policy()==null)
            return denied(now,"STR_206_SOURCE_POLICY_MISSING",false);
        try {
            var result=FnoTailRiskPutHedgeV1.evaluate(v.holdings(),v.put(),v.snapshot(),v.sessions(),
                new FnoTailRiskPutHedgeV1.Rights(true,true,true,true,true,true),v.policy(),now);
            if(result==null||!result.hindsightReplayOnly()&&!result.paperTradeAuthorized()||result.executable()
                ||result.orderPlaced()||result.liveTradeAuthorized())
                return denied(now,"STR_206_RESEARCH_NO_ORDER_INVARIANT_FAILED",false);
            return new FnoFuturesResearchService.StrategyStatus(
                result.strategyId(),result.status().name(),result.reasonCode(),now,
                proof.provider(),1,1,v.sessions()==null?0:v.sessions().size(),"BROKER_VERIFIED_HOLDINGS_ORIGINAL_TAIL_STRESS_HINDSIGHT_ONLY_NO_ORDER");
        }catch(RuntimeException invalid){
            return denied(now,"STR_206_TYPED_SOURCE_EVALUATOR_FAILED",false);
        }
    }
    private static FnoFuturesResearchService.StrategyStatus denied(
        Instant now,String reason,boolean noRights){
        var result=FnoTailRiskPutHedgeV1.evaluate(List.of(),null,null,List.of(),
            new FnoTailRiskPutHedgeV1.Rights(!noRights,!noRights,!noRights,!noRights,!noRights,!noRights),
            null,now);
        return new FnoFuturesResearchService.StrategyStatus(
            "strategy.STR-206",result.status().name(),reason,now,"NONE",
            0,0,0,"STR_206_ORIGINAL_SOURCE_BLOCKED_NO_ORDER");
    }
}
