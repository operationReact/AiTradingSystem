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

/** STR-209, real source-envelope rights and separate original typed
 * deterministic research evaluation. No synthetic fills, margin, PnL, or orders.
 * This adapter is NOT independent live-market acceptance evidence.
 */
@Service
public final class FnoExpiryRiskNeutralizationResearchService {
    public record Evidence(FnoExpiryRiskNeutralizationV1.BrokerBook book,
            FnoExpiryRiskNeutralizationV1.Snapshot snapshot,
            FnoExpiryRiskNeutralizationV1.ExpiryEvidence expiry,
            List<FnoExpiryRiskNeutralizationV1.CompletedClose> history,
            FnoExpiryRiskNeutralizationV1.Policy policy) {}
    private final FnoVerifiedStrategy207212SourceStore source;
    private final ObjectMapper mapper;
    private final boolean researchRights;

    @Autowired
    public FnoExpiryRiskNeutralizationResearchService(FnoVerifiedStrategy207212SourceStore source,ObjectMapper mapper,
        @Value("$" + "{minttick.fno-research.str-209-source-rights-verified:false}")
        boolean researchRights){
        this.source=source;
        this.mapper=mapper.copy().findAndRegisterModules()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,true);
        this.researchRights=researchRights;
    }

    public FnoFuturesResearchService.StrategyStatus evaluate(Instant now){
        if(now==null)throw new IllegalArgumentException("STR_209_AS_OF_REQUIRED");
        if(!researchRights)return denied(now,"STR_209_SOURCE_RIGHTS_UNVERIFIED",true);
        if(source==null)return denied(now,"STR_209_SOURCE_UNAVAILABLE",false);
        final Optional<FnoVerifiedStrategy207212SourceStore.Proof> original;
        try{
            original=source.oneCurrent("strategy.STR-209",now);
        }catch(RuntimeException unavailable){
            return denied(now,"STR_209_ORIGINAL_SOURCE_READ_FAILED",false);
        }
        if(original==null||original.isEmpty())
            return denied(now,"STR_209_AUTHENTIC_ORIGINAL_SOURCE_MISSING",false);
        var proof=original.get();
        if(proof==null||!"strategy.STR-209".equals(proof.strategyId())
            ||proof.provider()==null||proof.json()==null)
            return denied(now,"STR_209_WRONG_PROVENANCE_IDENTITY",false);
        if(!FnoTypedResearchProvenanceGate.valid(
                mapper,proof.json(),proof.provider(),proof.originalFirstKnownAt(),now))
            return denied(now,"STR_209_PROVIDER_ORIGINAL_TIME_PROVENANCE_INVALID",false);
        final Evidence v;
        try{v=mapper.readValue(proof.json(),Evidence.class);}
        catch(Exception malformed){
            return denied(now,"STR_209_ORIGINAL_TYPED_INPUT_INVALID",false);
        }
        if(v==null||v.policy()==null)
            return denied(now,"STR_209_RESEARCH_POLICY_MISSING",false);
        try{
            var result=FnoExpiryRiskNeutralizationV1.evaluate(v.book(),v.snapshot(),v.expiry(),v.history(),
                new FnoExpiryRiskNeutralizationV1.Rights(true,true,true,true,true,true),v.policy(),now);
            if(result==null||!result.fullExpiryBookOnly()||result.executable()
                ||result.orderPlaced()||result.paperTradeAuthorized()
                ||result.liveTradeAuthorized())
                return denied(now,"STR_209_NO_BROKER_ORDER_INVARIANT_FAILED",false);
            return new FnoFuturesResearchService.StrategyStatus(
                result.strategyId(),result.status().name(),result.reasonCode(),now,
                proof.provider(),v.book()==null||v.book().positions()==null?0:v.book().positions().size(),v.snapshot()==null||v.snapshot().optionQuotes()==null?0:v.snapshot().optionQuotes().size(),v.history()==null?0:v.history().size(),"OFFICIAL_EXPIRY_CUTOFF_COMPLETE_BROKER_BOOK_HYPOTHETICAL_CLOSE_NO_ORDER");
        }catch(RuntimeException invalid){
            return denied(now,"STR_209_TYPED_EVALUATOR_FAILED",false);
        }
    }

    private static FnoFuturesResearchService.StrategyStatus denied(
        Instant now,String reason,boolean noRights){
        var d=FnoExpiryRiskNeutralizationV1.evaluate(null,null,null,List.of(),
            new FnoExpiryRiskNeutralizationV1.Rights(!noRights,!noRights,!noRights,!noRights,!noRights,!noRights),
            null,now);
        return new FnoFuturesResearchService.StrategyStatus(
            "strategy.STR-209",d.status().name(),reason,now,
            "NONE",0,0,0,"STR_209_SOURCE_DENIED_NO_ORDER");
    }
}
