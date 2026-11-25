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

/** STR-208, real source-envelope rights and separate original typed
 * deterministic research evaluation. No synthetic fills, margin, PnL, or orders.
 * This adapter is NOT independent live-market acceptance evidence.
 */
@Service
public final class FnoDefinedRiskSpreadRollResearchService {
    public record Evidence(FnoDefinedRiskSpreadRollV1.BrokerSpread brokerSpread,
            FnoDefinedRiskSpreadRollV1.Contract oldLong,
            FnoDefinedRiskSpreadRollV1.Contract oldShort,
            FnoDefinedRiskSpreadRollV1.Contract newLong,
            FnoDefinedRiskSpreadRollV1.Contract newShort,
            FnoDefinedRiskSpreadRollV1.Snapshot snapshot,
            List<FnoDefinedRiskSpreadRollV1.CompletedClose> history,
            FnoDefinedRiskSpreadRollV1.Policy policy) {}
    private final FnoVerifiedStrategy207212SourceStore source;
    private final ObjectMapper mapper;
    private final boolean researchRights;

    @Autowired
    public FnoDefinedRiskSpreadRollResearchService(FnoVerifiedStrategy207212SourceStore source,ObjectMapper mapper,
        @Value("$" + "{minttick.fno-research.str-208-source-rights-verified:false}")
        boolean researchRights){
        this.source=source;
        this.mapper=mapper.copy().findAndRegisterModules()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,true);
        this.researchRights=researchRights;
    }

    public FnoFuturesResearchService.StrategyStatus evaluate(Instant now){
        if(now==null)throw new IllegalArgumentException("STR_208_AS_OF_REQUIRED");
        if(!researchRights)return denied(now,"STR_208_SOURCE_RIGHTS_UNVERIFIED",true);
        if(source==null)return denied(now,"STR_208_SOURCE_UNAVAILABLE",false);
        final Optional<FnoVerifiedStrategy207212SourceStore.Proof> original;
        try{
            original=source.oneCurrent("strategy.STR-208",now);
        }catch(RuntimeException unavailable){
            return denied(now,"STR_208_ORIGINAL_SOURCE_READ_FAILED",false);
        }
        if(original==null||original.isEmpty())
            return denied(now,"STR_208_AUTHENTIC_ORIGINAL_SOURCE_MISSING",false);
        var proof=original.get();
        if(proof==null||!"strategy.STR-208".equals(proof.strategyId())
            ||proof.provider()==null||proof.json()==null)
            return denied(now,"STR_208_WRONG_PROVENANCE_IDENTITY",false);
        if(!FnoTypedResearchProvenanceGate.valid(
                mapper,proof.json(),proof.provider(),proof.originalFirstKnownAt(),now))
            return denied(now,"STR_208_PROVIDER_ORIGINAL_TIME_PROVENANCE_INVALID",false);
        final Evidence v;
        try{v=mapper.readValue(proof.json(),Evidence.class);}
        catch(Exception malformed){
            return denied(now,"STR_208_ORIGINAL_TYPED_INPUT_INVALID",false);
        }
        if(v==null||v.policy()==null)
            return denied(now,"STR_208_RESEARCH_POLICY_MISSING",false);
        try{
            var result=FnoDefinedRiskSpreadRollV1.evaluate(v.brokerSpread(),v.oldLong(),v.oldShort(),v.newLong(),v.newShort(),v.snapshot(),v.history(),
                new FnoDefinedRiskSpreadRollV1.Rights(true,true,true,true,true,true),v.policy(),now);
            if(result==null||!result.expiryScenarioOnly()||result.executable()
                ||result.orderPlaced()||result.paperTradeAuthorized()
                ||result.liveTradeAuthorized())
                return denied(now,"STR_208_NO_BROKER_ORDER_INVARIANT_FAILED",false);
            return new FnoFuturesResearchService.StrategyStatus(
                result.strategyId(),result.status().name(),result.reasonCode(),now,
                proof.provider(),4,4,v.history()==null?0:v.history().size(),"BROKER_EXISTING_VERTICAL_FOUR_LEG_ROLL_RESEARCH_NO_ACTUAL_FILLS");
        }catch(RuntimeException invalid){
            return denied(now,"STR_208_TYPED_EVALUATOR_FAILED",false);
        }
    }

    private static FnoFuturesResearchService.StrategyStatus denied(
        Instant now,String reason,boolean noRights){
        var d=FnoDefinedRiskSpreadRollV1.evaluate(null,null,null,null,null,null,List.of(),
            new FnoDefinedRiskSpreadRollV1.Rights(!noRights,!noRights,!noRights,!noRights,!noRights,!noRights),
            null,now);
        return new FnoFuturesResearchService.StrategyStatus(
            "strategy.STR-208",d.status().name(),reason,now,
            "NONE",0,0,0,"STR_208_SOURCE_DENIED_NO_ORDER");
    }
}
