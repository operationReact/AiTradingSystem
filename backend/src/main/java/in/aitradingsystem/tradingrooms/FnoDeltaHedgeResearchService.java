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

/** STR-204. Read-only typed existing deterministic evaluator, single original
 * source custody and 6 research-use receipts. No real market or account
 * rights are inferred from tests, and NO broker or PAPER/LIVE orders exist.
 */
@Service
public final class FnoDeltaHedgeResearchService {
    public record Evidence(List<FnoDeltaHedgeV1.OptionPosition> positions,
            FnoDeltaHedgeV1.FutureInstrument future,
            FnoDeltaHedgeV1.FuturesPosition futuresPosition,
            FnoDeltaHedgeV1.Snapshot snapshot,
            List<FnoDeltaHedgeV1.CompletedClose> history,
            FnoDeltaHedgeV1.Policy policy) {}
    private final FnoVerifiedStrategy203206SourceStore source;
    private final ObjectMapper mapper;
    private final boolean sourceRights;
    @Autowired
    public FnoDeltaHedgeResearchService(FnoVerifiedStrategy203206SourceStore source,ObjectMapper mapper,
        @Value("$" + "{minttick.fno-research.str-204-source-rights-verified:false}")
        boolean sourceRights){
        this.source=source;
        this.mapper=mapper.copy().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,true);
        this.sourceRights=sourceRights;
    }
    public FnoFuturesResearchService.StrategyStatus evaluate(Instant now){
        if(now==null)throw new IllegalArgumentException("STR_204_AS_OF_REQUIRED");
        if(!sourceRights)return denied(now,"STR_204_SOURCE_RIGHTS_UNVERIFIED",true);
        if(source==null)return denied(now,"STR_204_PROVENANCE_SOURCE_MISSING",false);
        final Optional<FnoVerifiedStrategy203206SourceStore.Proof> p;
        try {p=source.oneCurrent("strategy.STR-204",now);}
        catch(RuntimeException unavailable){
            return denied(now,"STR_204_ORIGINAL_SOURCE_READ_FAILED",false);
        }
        if(p==null||p.isEmpty())
            return denied(now,"STR_204_GENUINE_ORIGINAL_SOURCE_EVIDENCE_MISSING",false);
        var proof=p.get();
        if(proof==null||!"strategy.STR-204".equals(proof.strategyId())
                ||!FnoTypedResearchProvenanceGate.valid(
                    mapper,proof.json(),proof.provider(),proof.originalFirstKnownAt(),now))
            return denied(now,"STR_204_PROVIDER_ORIGINAL_TIME_PROVENANCE_INVALID",false);
        final Evidence v;
        try {v=mapper.readValue(proof.json(),Evidence.class);}
        catch(Exception malformed){
            return denied(now,"STR_204_TYPED_SOURCE_PAYLOAD_INVALID",false);
        }
        if(v==null||v.policy()==null)
            return denied(now,"STR_204_SOURCE_POLICY_MISSING",false);
        try {
            var result=FnoDeltaHedgeV1.evaluate(v.positions(),v.future(),v.futuresPosition(),v.snapshot(),v.history(),
                new FnoDeltaHedgeV1.Rights(true,true,true,true,true,true),v.policy(),now);
            if(result==null||!result.sourceBasedResearchOnly()&&!result.paperTradeAuthorized()||result.executable()
                ||result.orderPlaced()||result.liveTradeAuthorized())
                return denied(now,"STR_204_RESEARCH_NO_ORDER_INVARIANT_FAILED",false);
            return new FnoFuturesResearchService.StrategyStatus(
                result.strategyId(),result.status().name(),result.reasonCode(),now,
                proof.provider(),v.positions()==null?0:v.positions().size(),v.snapshot()==null?0:v.snapshot().optionQuotes()==null?0:v.snapshot().optionQuotes().size(),v.history()==null?0:v.history().size(),"BROKER_CONFIRMED_POSITIONS_ORIGINAL_DELTA_FUTURES_HYPOTHETICAL_NO_ORDER");
        }catch(RuntimeException invalid){
            return denied(now,"STR_204_TYPED_SOURCE_EVALUATOR_FAILED",false);
        }
    }
    private static FnoFuturesResearchService.StrategyStatus denied(
        Instant now,String reason,boolean noRights){
        var result=FnoDeltaHedgeV1.evaluate(List.of(),null,null,null,List.of(),
            new FnoDeltaHedgeV1.Rights(!noRights,!noRights,!noRights,!noRights,!noRights,!noRights),
            null,now);
        return new FnoFuturesResearchService.StrategyStatus(
            "strategy.STR-204",result.status().name(),reason,now,"NONE",
            0,0,0,"STR_204_ORIGINAL_SOURCE_BLOCKED_NO_ORDER");
    }
}
