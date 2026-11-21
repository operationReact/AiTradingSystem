package in.aitradingsystem.tradingrooms;

import in.aitradingsystem.marketdata.FnoVerifiedNiftyIronCondorSourceStore;
import in.aitradingsystem.marketdata.FnoVerifiedNiftyIronCondorSourceStore.Proof;
import in.aitradingsystem.marketdata.FnoVerifiedNiftyIronCondorSourceStore.Leg;
import in.aitradingsystem.marketdata.FnoVerifiedNiftyLongOptionSourceStore;
import in.aitradingsystem.marketdata.FnoVerifiedNiftyLongOptionSourceStore.VerifiedClose;
import in.aitradingsystem.marketdata.MarketSessionCalendar;
import java.math.BigDecimal;
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
 * STR-196: separate direct research evaluator invocation for original four-leg iron condor.
 * Real filled execution/margin is NOT proved by defined expiry payoff.
 * Four option contracts are verified independently with original provider IV and
 * authentic BID side for two shorts and ASK side for two protective longs.
 * This is READ ONLY: no broker, paper, or live order executor is called.
 */
@Service
public final class FnoIronCondorResearchService {
    private static final String INDEX=FnoLongOptionCrossoverV1.UNDERLYING;
    private static final ZoneId IST=ZoneId.of("Asia/Kolkata");
    private final FnoVerifiedNiftyLongOptionSourceStore daily;
    private final FnoVerifiedNiftyIronCondorSourceStore condors;
    private final MarketSessionCalendar calendar;
    private final boolean dailyRights,chainRights,masterRights,originalIvRights,feesRights;
    private final String longPutKey,shortPutKey,shortCallKey,longCallKey;

    @Autowired
    public FnoIronCondorResearchService(
        FnoVerifiedNiftyLongOptionSourceStore daily,
        FnoVerifiedNiftyIronCondorSourceStore condors,
        MarketSessionCalendar calendar,
        @Value("$" + "{minttick.fno-research.nifty-daily-close-research-rights-verified:false}") boolean dailyRights,
        @Value("$" + "{minttick.fno-research.long-option-quote-research-rights-verified:false}") boolean chainRights,
        @Value("$" + "{minttick.fno-research.long-option-contract-discovery-verified:false}") boolean masterRights,
        @Value("$" + "{minttick.fno-research.iron-condor-four-original-iv-research-rights-verified:false}") boolean originalIvRights,
        @Value("$" + "{minttick.fno-research.iron-condor-four-leg-fees-research-rights-verified:false}") boolean feesRights,
        @Value("$" + "{minttick.fno-research.iron-condor-long-put-key:}") String longPutKey,
        @Value("$" + "{minttick.fno-research.iron-condor-short-put-key:}") String shortPutKey,
        @Value("$" + "{minttick.fno-research.iron-condor-short-call-key:}") String shortCallKey,
        @Value("$" + "{minttick.fno-research.iron-condor-long-call-key:}") String longCallKey) {
        this.daily=daily;this.condors=condors;this.calendar=calendar;
        this.dailyRights=dailyRights;this.chainRights=chainRights;
        this.masterRights=masterRights;this.originalIvRights=originalIvRights;
        this.feesRights=feesRights;
        this.longPutKey=clean(longPutKey);this.shortPutKey=clean(shortPutKey);
        this.shortCallKey=clean(shortCallKey);this.longCallKey=clean(longCallKey);
    }
    private static String clean(String s){return s==null?"":s.trim();}

    public FnoFuturesResearchService.StrategyStatus evaluate(Instant now) {
        if(now==null)throw new IllegalArgumentException("IRON_CONDOR_AS_OF_REQUIRED");
        if(!dailyRights||!chainRights||!masterRights||!originalIvRights||!feesRights)
            return blocked(now,"IRON_CONDOR_SOURCE_RIGHTS_UNVERIFIED",true,0);
        if(daily==null||condors==null||calendar==null)
            return blocked(now,"IRON_CONDOR_SOURCE_UNAVAILABLE",false,0);
        if(!calendar.isInsideCashSession(now))
            return blocked(now,"IRON_CONDOR_NSE_SESSION_CLOSED",false,0);
        if(!validKey(longPutKey)||!validKey(shortPutKey)||!validKey(shortCallKey)
                ||!validKey(longCallKey)
                ||java.util.Set.of(longPutKey,shortPutKey,shortCallKey,longCallKey).size()!=4)
            return blocked(now,"IRON_CONDOR_FOUR_CONTRACT_KEYS_UNVERIFIED",false,0);
        final List<VerifiedClose> closes;
        try{closes=daily.verifiedRecentCloses(now);}
        catch(RuntimeException unavailable){
            return blocked(now,"IRON_CONDOR_DAILY_HISTORY_READ_FAILED",false,0);
        }
        if(!FnoOptionsResearchService.validHistory(closes,calendar,now))
            return blocked(now,"IRON_CONDOR_35_VERIFIED_COMPLETED_CLOSES_MISSING",
                false,closes==null?0:closes.size());
        final Optional<Proof> source;
        try{source=condors.oneCurrent(longPutKey,shortPutKey,shortCallKey,longCallKey,now);}
        catch(RuntimeException unavailable){
            return blocked(now,"IRON_CONDOR_ORIGINAL_FOUR_LEG_SOURCE_READ_FAILED",
                false,closes.size());
        }
        if(source==null||source.isEmpty())
            return blocked(now,"IRON_CONDOR_FOUR_INDEPENDENT_IV_QUOTES_MISSING",false,closes.size());
        var p=source.get();
        if(!valid(p,closes.getLast(),now))
            return blocked(now,"IRON_CONDOR_FOUR_LEG_CONTRACT_QUOTE_OR_PROVENANCE_INVALID",
                false,closes.size());
        var history=new ArrayList<FnoIronCondorV1.CompletedClose>();
        for(var bar:closes)history.add(new FnoIronCondorV1.CompletedClose(
            bar.session(),bar.provider(),bar.close(),bar.verifiedSessionCloseAt(),
            bar.firstKnownAt(),true));
        var lp=contract(p.longPut(),"PE",p);
        var sp=contract(p.shortPut(),"PE",p);
        var sc=contract(p.shortCall(),"CE",p);
        var lc=contract(p.longCall(),"CE",p);
        var spot=new FnoIronCondorV1.Quote(p.provider(),INDEX,
            p.index().bid(),p.index().ask(),null,1,1,1,
            p.index().exchangeAt(),p.index().firstKnownAt());
        var b=p.budget();
        var a=new FnoIronCondorV1.Assumptions(
            b.buySlippageBps(),b.sellSlippageBps(),b.maxSpreadBps(),
            b.minDepthLots(),b.minOpenInterestLots(),b.maxWingDistanceBps(),
            b.maxIvPercent(),b.fourLegFeesInr(),b.researchCapitalInr(),
            b.maxWorstExpiryLossAllocationBps(),b.minCreditToWorstLossBps(),
            b.maxOneSigmaMoveToNearestShortStrikeBps());
        var result=FnoIronCondorV1.evaluate(lp,sp,sc,lc,List.copyOf(history),
            spot,quote(p.longPut(),p),quote(p.shortPut(),p),quote(p.shortCall(),p),
            quote(p.longCall(),p),new FnoIronCondorV1.Rights(true,true,true,true),a,now);
        if(!result.expiryPayoffDefinedRisk()||result.executable()
                ||result.orderPlaced()||result.liveTradeAuthorized())
            return blocked(now,"IRON_CONDOR_READ_ONLY_DEFINED_EXPIRY_RISK_INVARIANT_FAILED",
                false,closes.size());
        return new FnoFuturesResearchService.StrategyStatus(
            result.strategyId(),result.status().name(),result.reasonCode(),
            now,p.provider(),4,5,history.size(),
            "ORIGINAL_FOUR_LEG_SOURCE_DEFINED_EXPIRY_RISK_ONLY_NO_ORDER");
    }
    private static FnoIronCondorV1.Contract contract(Leg l,String type,Proof p){
        return new FnoIronCondorV1.Contract(l.key(),INDEX,type,l.strike(),
            p.tick(),l.listedFrom(),p.expiry(),p.lotSize(),true);
    }
    private static FnoIronCondorV1.Quote quote(Leg l,Proof p){
        return new FnoIronCondorV1.Quote(p.provider(),l.key(),l.bid(),l.ask(),
            l.ivPercent(),l.bidDepthLots(),l.askDepthLots(),l.openInterestLots(),
            l.exchangeAt(),l.firstKnownAt());
    }
    private boolean valid(Proof p,VerifiedClose last,Instant now) {
        if(p==null||last==null||p.provider()==null||p.index()==null
            ||p.longPut()==null||p.shortPut()==null||p.shortCall()==null
            ||p.longCall()==null||p.budget()==null
            ||!p.provider().equals(last.provider())
            ||p.lotSize()<=0||p.tick()==null||p.tick().signum()<=0
            ||p.expiry()==null||p.firstKnownAt()==null||p.firstKnownAt().isAfter(now)
            ||!positive(p.index().bid())||!positive(p.index().ask())
            ||p.index().ask().compareTo(p.index().bid())<0
            ||!validTime(p.index().exchangeAt(),p.index().firstKnownAt(),last,now)
            ||p.firstKnownAt().isBefore(p.index().firstKnownAt()))
            return false;
        List<Leg> legs=List.of(p.longPut(),p.shortPut(),p.shortCall(),p.longCall());
        List<String> keys=List.of(longPutKey,shortPutKey,shortCallKey,longCallKey);
        Instant min=p.index().exchangeAt(),max=p.index().exchangeAt();
        for(int i=0;i<legs.size();i++){
            var l=legs.get(i);
            if(!keys.get(i).equals(l.key())||!positive(l.strike())
                ||l.strike().remainder(p.tick()).signum()!=0
                ||l.listedFrom()==null||l.listedFrom().isAfter(now.atZone(IST).toLocalDate())
                ||!positive(l.bid())||!positive(l.ask())||l.ask().compareTo(l.bid())<0
                ||!positive(l.ivPercent())||l.bidDepthLots()<1
                ||l.askDepthLots()<1||l.openInterestLots()<1
                ||!validTime(l.exchangeAt(),l.firstKnownAt(),last,now)
                ||p.firstKnownAt().isBefore(l.firstKnownAt()))return false;
            if(l.exchangeAt().isBefore(min))min=l.exchangeAt();
            if(l.exchangeAt().isAfter(max))max=l.exchangeAt();
        }
        return p.longPut().strike().compareTo(p.shortPut().strike())<0
            && p.shortPut().strike().compareTo(p.index().bid())<0
            && p.index().bid().compareTo(p.shortCall().strike())<0
            && p.shortCall().strike().compareTo(p.longCall().strike())<0
            && Duration.between(min,max).compareTo(Duration.ofSeconds(90))<=0;
    }
    private static boolean validTime(Instant exchange,Instant firstKnown,
        VerifiedClose last,Instant now){
        return exchange!=null&&firstKnown!=null&&!exchange.isAfter(firstKnown)
            &&!firstKnown.isAfter(now)&&!exchange.isAfter(now)
            &&!last.firstKnownAt().isAfter(exchange)
            &&!Duration.between(exchange,now).isNegative()
            &&Duration.between(exchange,now).compareTo(Duration.ofSeconds(180))<=0;
    }
    private static boolean positive(BigDecimal n){return n!=null&&n.signum()>0;}
    private static boolean validKey(String key){
        return key!=null&&key.matches("NSE_FO\\|[A-Za-z0-9._-]{1,64}");
    }
    private static FnoFuturesResearchService.StrategyStatus blocked(
        Instant now,String reason,boolean denied,int history){
        var decision=FnoIronCondorV1.evaluate(null,null,null,null,List.of(),
            null,null,null,null,null,
            new FnoIronCondorV1.Rights(!denied,!denied,!denied,!denied),null,now);
        return new FnoFuturesResearchService.StrategyStatus(
            FnoIronCondorV1.STRATEGY_ID,decision.status().name(),reason,
            now,"NONE",0,0,history,"IRON_CONDOR_SOURCE_BLOCKED_NO_ORDER");
    }
}
