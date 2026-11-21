package in.aitradingsystem.tradingrooms;

import in.aitradingsystem.marketdata.FnoVerifiedNiftyLongOptionSourceStore;
import in.aitradingsystem.marketdata.FnoVerifiedNiftyLongOptionSourceStore.VerifiedClose;
import in.aitradingsystem.marketdata.FnoVerifiedNiftyStrangleIvSourceStore;
import in.aitradingsystem.marketdata.FnoVerifiedNiftyStrangleIvSourceStore.Proof;
import in.aitradingsystem.marketdata.FnoVerifiedNiftyStrangleIvSourceStore.Leg;
import in.aitradingsystem.marketdata.MarketSessionCalendar;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * STR-193: distinct read-only evaluator invocation on original source-attested
 * two-leg option chain with two independently verified provider IV observations on separate genuine OTM strikes.
 * Existing STR-193 deterministic evaluator is reused, never duplicated.
 * Never routes to a broker/PAPER executor.
 */
@Service
public final class FnoStrangleResearchService {
    private static final String NIFTY=FnoLongOptionCrossoverV1.UNDERLYING;
    private static final ZoneId IST=ZoneId.of("Asia/Kolkata");
    private final FnoVerifiedNiftyLongOptionSourceStore daily;
    private final FnoVerifiedNiftyStrangleIvSourceStore strangles;
    private final MarketSessionCalendar calendar;
    private final boolean dailyRights,quotesRights,masterRights,ivRights;
    private final String callKey,putKey;

    @Autowired
    public FnoStrangleResearchService(
        FnoVerifiedNiftyLongOptionSourceStore daily,
        FnoVerifiedNiftyStrangleIvSourceStore strangles,
        MarketSessionCalendar calendar,
        @Value("$" + "{minttick.fno-research.nifty-daily-close-research-rights-verified:false}")
            boolean dailyRights,
        @Value("$" + "{minttick.fno-research.long-option-quote-research-rights-verified:false}")
            boolean quotesRights,
        @Value("$" + "{minttick.fno-research.long-option-contract-discovery-verified:false}")
            boolean masterRights,
        @Value("$" + "{minttick.fno-research.strangle-original-iv-research-rights-verified:false}")
            boolean ivRights,
        @Value("$" + "{minttick.fno-research.strangle-ce-key:}") String callKey,
        @Value("$" + "{minttick.fno-research.strangle-pe-key:}") String putKey) {
        this.daily=daily;this.strangles=strangles;this.calendar=calendar;
        this.dailyRights=dailyRights;this.quotesRights=quotesRights;
        this.masterRights=masterRights;this.ivRights=ivRights;
        this.callKey=callKey==null?"":callKey.trim();
        this.putKey=putKey==null?"":putKey.trim();
    }

    public FnoFuturesResearchService.StrategyStatus evaluate(Instant now) {
        if(now==null)throw new IllegalArgumentException("STRANGLE_AS_OF_REQUIRED");
        if(!dailyRights||!quotesRights||!masterRights||!ivRights)
            return blocked(now,"STRANGLE_SOURCE_OR_IV_RIGHTS_UNVERIFIED",true,0);
        if(daily==null||strangles==null||calendar==null)
            return blocked(now,"STRANGLE_SOURCE_UNAVAILABLE",false,0);
        if(!calendar.isInsideCashSession(now))
            return blocked(now,"STRANGLE_CASH_SESSION_CLOSED",false,0);
        if(!validKey(callKey)||!validKey(putKey)||callKey.equals(putKey))
            return blocked(now,"STRANGLE_CE_PE_CONTRACTS_NOT_CONFIGURED",false,0);
        final List<VerifiedClose> closes;
        try{closes=daily.verifiedRecentCloses(now);}
        catch(RuntimeException unavailable){
            return blocked(now,"STRANGLE_DAILY_CLOSE_READ_FAILED",false,0);
        }
        if(!FnoOptionsResearchService.validHistory(closes,calendar,now))
            return blocked(now,"STRANGLE_35_ORIGINAL_DAILY_CLOSES_MISSING",false,
                closes==null?0:closes.size());
        final Optional<Proof> source;
        try{source=strangles.oneCurrent(callKey,putKey,now);}
        catch(RuntimeException unavailable){
            return blocked(now,"STRANGLE_ORIGINAL_IV_SOURCE_READ_FAILED",false,closes.size());
        }
        if(source==null||source.isEmpty())
            return blocked(now,"STRANGLE_TWO_VERIFIED_IV_QUOTES_MISSING",false,closes.size());
        Proof proof=source.get();
        if(!valid(proof,closes.getLast(),now))
            return blocked(now,"STRANGLE_IV_CONTRACT_OR_TIMESTAMPS_INVALID",false,closes.size());
        var history=new ArrayList<FnoLongStrangleV1.CompletedClose>();
        for(var bar:closes)history.add(new FnoLongStrangleV1.CompletedClose(
            bar.session(),bar.provider(),bar.close(),bar.verifiedSessionCloseAt(),
            bar.firstKnownAt(),true));
        var call=new FnoLongStrangleV1.Contract(callKey,NIFTY,"CE",
            proof.callStrike(),proof.tick(),proof.listedFrom(),proof.expiry(),proof.lotSize(),true);
        var put=new FnoLongStrangleV1.Contract(putKey,NIFTY,"PE",
            proof.putStrike(),proof.tick(),proof.listedFrom(),proof.expiry(),proof.lotSize(),true);
        var spot=new FnoLongStrangleV1.Quote(proof.provider(),NIFTY,
            proof.index().bid(),proof.index().ask(),null,1,1,
            proof.index().exchangeAt(),proof.index().firstKnownAt());
        var cq=quote(proof.call(),proof.provider());
        var pq=quote(proof.put(),proof.provider());
        var b=proof.budget();
        var assumptions=new FnoLongStrangleV1.Assumptions(
            b.buySlippageBps(),b.maxSpreadBps(),b.minDepthLots(),
            b.minOiLots(),b.maxOtmDistanceBps(),b.maxIvPercent(),
            b.allInFeesInr(),b.premiumBudgetInr(),b.maxPremiumAllocationBps(),
            b.minExpectedMoveOverCostBps());
        var result=FnoLongStrangleV1.evaluate(call,put,List.copyOf(history),
            spot,cq,pq,new FnoLongStrangleV1.Rights(true,true,true,true),
            assumptions,now);
        return new FnoFuturesResearchService.StrategyStatus(
            result.strategyId(),result.status().name(),result.reasonCode(),
            now,proof.provider(),2,3,history.size(),
            "ORIGINAL_PER_LEG_IV_SOURCE_RESEARCH_ONLY_NO_ORDER");
    }

    private static FnoLongStrangleV1.Quote quote(Leg p,String provider) {
        return new FnoLongStrangleV1.Quote(provider,p.key(),p.bid(),p.ask(),
            p.ivPercent(),p.askDepthLots(),p.openInterestLots(),
            p.exchangeAt(),p.firstKnownAt());
    }
    private boolean valid(Proof p,VerifiedClose last,Instant at) {
        if(p==null || last==null || p.provider()==null
           || !p.provider().equals(last.provider()) || p.index()==null
           || p.call()==null || p.put()==null || p.budget()==null
           || !callKey.equals(p.call().key()) || !putKey.equals(p.put().key())
           || p.callStrike()==null || p.callStrike().signum()<=0
           || p.putStrike()==null || p.putStrike().signum()<=0
           || p.callStrike().compareTo(p.putStrike())<=0
           || p.tick()==null || p.tick().signum()<=0
           || p.callStrike().remainder(p.tick()).signum()!=0
           || p.putStrike().remainder(p.tick()).signum()!=0
           || p.lotSize()<=0 || p.listedFrom()==null || p.expiry()==null
           || p.listedFrom().isAfter(at.atZone(IST).toLocalDate())
           || !positive(p.index().bid()) || !positive(p.index().ask())
           || p.index().ask().compareTo(p.index().bid())<0
           || p.putStrike().compareTo(p.index().bid())>=0
           || p.callStrike().compareTo(p.index().bid())<=0
           || !positive(p.call().ivPercent()) || !positive(p.put().ivPercent())
           || !validTime(p.index().exchangeAt(),p.index().firstKnownAt(),last,at)
           || !validTime(p.call().exchangeAt(),p.call().firstKnownAt(),last,at)
           || !validTime(p.put().exchangeAt(),p.put().firstKnownAt(),last,at)
           || p.firstKnownAt()==null || p.firstKnownAt().isAfter(at)
           || p.firstKnownAt().isBefore(p.index().firstKnownAt())
           || p.firstKnownAt().isBefore(p.call().firstKnownAt())
           || p.firstKnownAt().isBefore(p.put().firstKnownAt()))
            return false;
        var minimum=p.index().exchangeAt();
        var maximum=minimum;
        for(var t:List.of(p.call().exchangeAt(),p.put().exchangeAt())){
            if(t.isBefore(minimum))minimum=t;
            if(t.isAfter(maximum))maximum=t;
        }
        return Duration.between(minimum,maximum).compareTo(Duration.ofSeconds(90))<=0;
    }
    private static boolean validTime(Instant exchange,Instant firstKnown,
                                     VerifiedClose last,Instant now) {
        return exchange!=null && firstKnown!=null && !exchange.isAfter(firstKnown)
            && !firstKnown.isAfter(now) && !exchange.isAfter(now)
            && !last.firstKnownAt().isAfter(exchange)
            && !Duration.between(exchange,now).isNegative()
            && Duration.between(exchange,now).compareTo(Duration.ofSeconds(180))<=0;
    }
    private static boolean positive(java.math.BigDecimal n){return n!=null&&n.signum()>0;}
    private static boolean validKey(String key){
        return key!=null&&key.matches("NSE_FO\\|[A-Za-z0-9._-]{1,64}");
    }
    private static FnoFuturesResearchService.StrategyStatus blocked(
        Instant now,String reason,boolean denied,int history){
        var d=FnoLongStrangleV1.evaluate(null,null,List.of(),null,null,null,
            new FnoLongStrangleV1.Rights(!denied,!denied,!denied,!denied),
            null,now);
        return new FnoFuturesResearchService.StrategyStatus(
            FnoLongStrangleV1.STRATEGY_ID,d.status().name(),reason,
            now,"NONE",0,0,history,"STRANGLE_SOURCE_BLOCKED_NO_ORDER");
    }
}
