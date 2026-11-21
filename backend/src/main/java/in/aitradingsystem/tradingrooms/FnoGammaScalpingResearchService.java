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

/** STR-203. Read-only typed existing deterministic evaluator, single original
 * source custody and 6 research-use receipts. No real market or account
 * rights are inferred from tests, and NO broker or PAPER/LIVE orders exist.
 */
@Service
public final class FnoGammaScalpingResearchService {
    public record Evidence(FnoGammaScalpingV1.OptionContract call,
            FnoGammaScalpingV1.OptionContract put,
            FnoGammaScalpingV1.FutureContract future,
            List<FnoGammaScalpingV1.CompletedClose> history,
            List<FnoGammaScalpingV1.Sample> samples,
            FnoGammaScalpingV1.Assumptions policy) {}
    private final FnoVerifiedStrategy203206SourceStore source;
    private final ObjectMapper mapper;
    private final boolean sourceRights;
    @Autowired
    public FnoGammaScalpingResearchService(FnoVerifiedStrategy203206SourceStore source,ObjectMapper mapper,
        @Value("$" + "{minttick.fno-research.str-203-source-rights-verified:false}")
        boolean sourceRights){
        this.source=source;
        this.mapper=mapper.copy().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,true);
        this.sourceRights=sourceRights;
    }
    public FnoFuturesResearchService.StrategyStatus evaluate(Instant now){
        if(now==null)throw new IllegalArgumentException("STR_203_AS_OF_REQUIRED");
        if(!sourceRights)return denied(now,"STR_203_SOURCE_RIGHTS_UNVERIFIED",true);
        if(source==null)return denied(now,"STR_203_PROVENANCE_SOURCE_MISSING",false);
        final Optional<FnoVerifiedStrategy203206SourceStore.Proof> p;
        try {p=source.oneCurrent("strategy.STR-203",now);}
        catch(RuntimeException unavailable){
            return denied(now,"STR_203_ORIGINAL_SOURCE_READ_FAILED",false);
        }
        if(p==null||p.isEmpty())
            return denied(now,"STR_203_GENUINE_ORIGINAL_SOURCE_EVIDENCE_MISSING",false);
        var proof=p.get();
        if(proof==null||!"strategy.STR-203".equals(proof.strategyId())
                ||!FnoTypedResearchProvenanceGate.valid(
                    mapper,proof.json(),proof.provider(),proof.originalFirstKnownAt(),now))
            return denied(now,"STR_203_PROVIDER_ORIGINAL_TIME_PROVENANCE_INVALID",false);
        final Evidence v;
        try {v=mapper.readValue(proof.json(),Evidence.class);}
        catch(Exception malformed){
            return denied(now,"STR_203_TYPED_SOURCE_PAYLOAD_INVALID",false);
        }
        if(v==null||v.policy()==null)
            return denied(now,"STR_203_SOURCE_POLICY_MISSING",false);
        try {
            var result=FnoGammaScalpingV1.evaluate(v.call(),v.put(),v.future(),v.history(),v.samples(),
                new FnoGammaScalpingV1.Rights(true,true,true,true,true),v.policy(),now);
            if(result==null||(!result.retrospectiveOnly()||result.actualOptionOrHedgeFillsObserved())||result.executable()
                ||result.orderPlaced()||result.liveTradeAuthorized())
                return denied(now,"STR_203_RESEARCH_NO_ORDER_INVARIANT_FAILED",false);
            return new FnoFuturesResearchService.StrategyStatus(
                result.strategyId(),result.status().name(),result.reasonCode(),now,
                proof.provider(),3,v.samples()==null?0:v.samples().size(),v.history()==null?0:v.history().size(),"ORIGINAL_GREEKS_FUTURES_GAMMA_PATH_RETROSPECTIVE_NO_FILLS");
        }catch(RuntimeException invalid){
            return denied(now,"STR_203_TYPED_SOURCE_EVALUATOR_FAILED",false);
        }
    }
    private static FnoFuturesResearchService.StrategyStatus denied(
        Instant now,String reason,boolean noRights){
        var result=FnoGammaScalpingV1.evaluate(null,null,null,List.of(),List.of(),
            new FnoGammaScalpingV1.Rights(!noRights,!noRights,!noRights,!noRights,!noRights),
            null,now);
        return new FnoFuturesResearchService.StrategyStatus(
            "strategy.STR-203",result.status().name(),reason,now,"NONE",
            0,0,0,"STR_203_ORIGINAL_SOURCE_BLOCKED_NO_ORDER");
    }
}
