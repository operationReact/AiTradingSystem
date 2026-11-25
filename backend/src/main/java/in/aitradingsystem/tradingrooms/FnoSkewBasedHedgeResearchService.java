package in.aitradingsystem.tradingrooms;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.aitradingsystem.marketdata.FnoVerifiedStrategy207212SourceStore;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** STR-212, real source-envelope rights and separate original typed
 * deterministic research evaluation. No synthetic fills, margin, PnL, or orders.
 * This adapter is NOT independent live-market acceptance evidence.
 */
@Service
public final class FnoSkewBasedHedgeResearchService {
    public record Evidence(FnoSkewBasedHedgeV1.BrokerLongFuture broker,
            FnoSkewBasedHedgeV1.FutureContract future,
            FnoSkewBasedHedgeV1.OptionContract higherPut,
            FnoSkewBasedHedgeV1.OptionContract lowerPut,
            FnoSkewBasedHedgeV1.OptionContract referenceCall,
            FnoSkewBasedHedgeV1.Snapshot snapshot,
            List<FnoSkewBasedHedgeV1.CompletedClose> history,
            FnoSkewBasedHedgeV1.Policy policy) {}
    private final FnoVerifiedStrategy207212SourceStore source;
    private final ObjectMapper mapper;
    private final boolean researchRights;

    @Autowired
    public FnoSkewBasedHedgeResearchService(FnoVerifiedStrategy207212SourceStore source,ObjectMapper mapper,
        @Value("$" + "{minttick.fno-research.str-212-source-rights-verified:false}")
        boolean researchRights){
        this.source=source;
        this.mapper=mapper.copy().findAndRegisterModules()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,true);
        this.researchRights=researchRights;
    }

    public FnoFuturesResearchService.StrategyStatus evaluate(Instant now){
        if(now==null)throw new IllegalArgumentException("STR_212_AS_OF_REQUIRED");
        if(!researchRights)return denied(now,"STR_212_SOURCE_RIGHTS_UNVERIFIED",true);
        if(source==null)return denied(now,"STR_212_SOURCE_UNAVAILABLE",false);
        final Optional<FnoVerifiedStrategy207212SourceStore.Proof> original;
        try{
            original=source.oneCurrent("strategy.STR-212",now);
        }catch(RuntimeException unavailable){
            return denied(now,"STR_212_ORIGINAL_SOURCE_READ_FAILED",false);
        }
        if(original==null||original.isEmpty())
            return denied(now,"STR_212_AUTHENTIC_ORIGINAL_SOURCE_MISSING",false);
        var proof=original.get();
        if(proof==null||!"strategy.STR-212".equals(proof.strategyId())
            ||proof.provider()==null||proof.json()==null)
            return denied(now,"STR_212_WRONG_PROVENANCE_IDENTITY",false);
        if(!FnoTypedResearchProvenanceGate.valid(
                mapper,proof.json(),proof.provider(),proof.originalFirstKnownAt(),now))
            return denied(now,"STR_212_PROVIDER_ORIGINAL_TIME_PROVENANCE_INVALID",false);
        final Evidence v;
        try{v=mapper.readValue(proof.json(),Evidence.class);}
        catch(Exception malformed){
            return denied(now,"STR_212_ORIGINAL_TYPED_INPUT_INVALID",false);
        }
        if(v==null||v.policy()==null)
            return denied(now,"STR_212_RESEARCH_POLICY_MISSING",false);
        try{
            var result=FnoSkewBasedHedgeV1.evaluate(v.broker(),v.future(),v.higherPut(),v.lowerPut(),v.referenceCall(),v.snapshot(),v.history(),
                new FnoSkewBasedHedgeV1.Rights(true,true,true,true,true,true),v.policy(),now);
            if(result==null||!result.originalIndexShockPersistsToFutureExpiryAssumption()||result.executable()
                ||result.orderPlaced()||result.paperTradeAuthorized()
                ||result.liveTradeAuthorized())
                return denied(now,"STR_212_NO_BROKER_ORDER_INVARIANT_FAILED",false);
            return new FnoFuturesResearchService.StrategyStatus(
                result.strategyId(),result.status().name(),result.reasonCode(),now,
                proof.provider(),4,4,v.history()==null?0:v.history().size(),"SOURCE_ORIGINAL_DELTA_MATCHED_PUT_CALL_SKEW_UNCAPPED_DOWNSIDE_NO_ORDER");
        }catch(RuntimeException invalid){
            return denied(now,"STR_212_TYPED_EVALUATOR_FAILED",false);
        }
    }

    private static FnoFuturesResearchService.StrategyStatus denied(
        Instant now,String reason,boolean noRights){
        var d=FnoSkewBasedHedgeV1.evaluate(null,null,null,null,null,null,List.of(),
            new FnoSkewBasedHedgeV1.Rights(!noRights,!noRights,!noRights,!noRights,!noRights,!noRights),
            null,now);
        return new FnoFuturesResearchService.StrategyStatus(
            "strategy.STR-212",d.status().name(),reason,now,
            "NONE",0,0,0,"STR_212_SOURCE_DENIED_NO_ORDER");
    }
}
