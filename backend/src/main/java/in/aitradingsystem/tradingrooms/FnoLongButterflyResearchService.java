package in.aitradingsystem.tradingrooms;

import in.aitradingsystem.marketdata.FnoVerifiedNiftyLongOptionSourceStore;
import in.aitradingsystem.marketdata.FnoVerifiedNiftyLongOptionSourceStore.VerifiedClose;
import in.aitradingsystem.marketdata.FnoVerifiedNiftyLongButterflySourceStore;
import in.aitradingsystem.marketdata.FnoVerifiedNiftyLongButterflySourceStore.Leg;
import in.aitradingsystem.marketdata.FnoVerifiedNiftyLongButterflySourceStore.Proof;
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
 * STR-198: separate direct research evaluator for 1 lower long CE,
 * 2 center short CE lots and 1 upper long CE. Three contracts, FOUR units.
 * Original source BUY ask depth at wings and TWICE the minimum SELL bid
 * depth at center, with independent provider IV and four-contract fees.
 * No paper, LIVE broker order, account permission, or deployment action.
 */
@Service
public final class FnoLongButterflyResearchService {
    private static final String NIFTY=FnoLongOptionCrossoverV1.UNDERLYING;
    private static final ZoneId IST=ZoneId.of("Asia/Kolkata");
    private final FnoVerifiedNiftyLongOptionSourceStore daily;
    private final FnoVerifiedNiftyLongButterflySourceStore butterfly;
    private final MarketSessionCalendar calendar;
    private final boolean dailyRights,chainRights,masterRights,ivRights,feesRights;
    private final String lowerKey,centerKey,upperKey;

    @Autowired
    public FnoLongButterflyResearchService(
        FnoVerifiedNiftyLongOptionSourceStore daily,
        FnoVerifiedNiftyLongButterflySourceStore butterfly,
        MarketSessionCalendar calendar,
        @Value("$" + "{minttick.fno-research.nifty-daily-close-research-rights-verified:false}")
            boolean dailyRights,
        @Value("$" + "{minttick.fno-research.long-option-quote-research-rights-verified:false}")
            boolean chainRights,
        @Value("$" + "{minttick.fno-research.long-option-contract-discovery-verified:false}")
            boolean masterRights,
        @Value("$" + "{minttick.fno-research.long-butterfly-three-original-iv-rights-verified:false}")
            boolean ivRights,
        @Value("$" + "{minttick.fno-research.long-butterfly-four-contract-fees-rights-verified:false}")
            boolean feesRights,
        @Value("$" + "{minttick.fno-research.long-butterfly-lower-ce-key:}") String lowerKey,
        @Value("$" + "{minttick.fno-research.long-butterfly-center-ce-key:}") String centerKey,
        @Value("$" + "{minttick.fno-research.long-butterfly-upper-ce-key:}") String upperKey){
        this.daily=daily;this.butterfly=butterfly;this.calendar=calendar;
        this.dailyRights=dailyRights;this.chainRights=chainRights;
        this.masterRights=masterRights;this.ivRights=ivRights;this.feesRights=feesRights;
        this.lowerKey=clean(lowerKey);this.centerKey=clean(centerKey);
        this.upperKey=clean(upperKey);
    }
    private static String clean(String s){return s==null?"":s.trim();}

    public FnoFuturesResearchService.StrategyStatus evaluate(Instant now){
        if(now==null)throw new IllegalArgumentException("LONG_BUTTERFLY_AS_OF_REQUIRED");
        if(!dailyRights||!chainRights||!masterRights||!ivRights||!feesRights)
            return blocked(now,"LONG_BUTTERFLY_SOURCE_RIGHTS_UNVERIFIED",true,0);
        if(daily==null||butterfly==null||calendar==null)
            return blocked(now,"LONG_BUTTERFLY_SOURCE_UNAVAILABLE",false,0);
        if(!calendar.isInsideCashSession(now))
            return blocked(now,"LONG_BUTTERFLY_NSE_SESSION_CLOSED",false,0);
        if(!validKey(lowerKey)||!validKey(centerKey)||!validKey(upperKey)
                ||java.util.Set.of(lowerKey,centerKey,upperKey).size()!=3)
            return blocked(now,"LONG_BUTTERFLY_THREE_CALL_KEYS_UNVERIFIED",false,0);
        final List<VerifiedClose> closes;
        try{closes=daily.verifiedRecentCloses(now);}
        catch(RuntimeException unavailable){
            return blocked(now,"LONG_BUTTERFLY_HISTORY_READ_FAILED",false,0);
        }
        if(!FnoOptionsResearchService.validHistory(closes,calendar,now))
            return blocked(now,"LONG_BUTTERFLY_35_VERIFIED_CLOSES_MISSING",
                false,closes==null?0:closes.size());
        final Optional<Proof> source;
        try{source=butterfly.oneCurrent(lowerKey,centerKey,upperKey,now);}
        catch(RuntimeException unavailable){
            return blocked(now,"LONG_BUTTERFLY_ORIGINAL_THREE_CALL_SOURCE_READ_FAILED",
                false,closes.size());
        }
        if(source==null||source.isEmpty())
            return blocked(now,"LONG_BUTTERFLY_THREE_VERIFIED_IV_QUOTES_MISSING",
                false,closes.size());
        Proof p=source.get();
        if(!valid(p,closes.getLast(),now))
            return blocked(now,"LONG_BUTTERFLY_CONTRACT_TIME_DEPTH_OR_IV_INVALID",
                false,closes.size());
        var history=new ArrayList<FnoLongButterflyV1.CompletedClose>();
        for(var bar:closes)history.add(new FnoLongButterflyV1.CompletedClose(
            bar.session(),bar.provider(),bar.close(),bar.verifiedSessionCloseAt(),
            bar.firstKnownAt(),true));
        var lower=contract(p.lower(),p);
        var center=contract(p.center(),p);
        var upper=contract(p.upper(),p);
        var spot=new FnoLongButterflyV1.Quote(p.provider(),NIFTY,
            p.index().bid(),p.index().ask(),null,1,1,1,
            p.index().exchangeAt(),p.index().firstKnownAt());
        var b=p.budget();
        var assumptions=new FnoLongButterflyV1.Assumptions(
            b.buySlippageBps(),b.sellSlippageBps(),b.maxSpreadBps(),
            b.minDepthLots(),b.minOiLots(),b.maxWingDistanceBps(),
            b.maxAtmDistanceBps(),b.maxIvPercent(),b.fourContractFeesInr(),
            b.researchCapitalInr(),b.maxDebitAllocationBps(),
            b.minRewardToRiskBps(),b.maxIvMoveToWingBps());
        var result=FnoLongButterflyV1.evaluate(lower,center,upper,
            List.copyOf(history),spot,quote(p.lower(),p),quote(p.center(),p),
            quote(p.upper(),p),new FnoLongButterflyV1.Rights(true,true,true,true),
            assumptions,now);
        if(!result.expiryRiskBoundedOnlyWhenAllLegsIntact()
                ||result.executable()||result.orderPlaced()||result.liveTradeAuthorized())
            return blocked(now,"LONG_BUTTERFLY_FOUR_UNIT_READ_ONLY_INVARIANT_FAILED",
                false,closes.size());
        return new FnoFuturesResearchService.StrategyStatus(
            result.strategyId(),result.status().name(),result.reasonCode(),
            now,p.provider(),3,4,history.size(),
            "ORIGINAL_THREE_CALL_TWO_CENTER_SHORT_LOTS_NO_ORDER");
    }
    private static FnoLongButterflyV1.Contract contract(Leg l,Proof p){
        return new FnoLongButterflyV1.Contract(l.key(),NIFTY,"CE",l.strike(),p.tick(),
            l.listedFrom(),p.expiry(),p.lotSize(),true);
    }
    private static FnoLongButterflyV1.Quote quote(Leg l,Proof p){
        return new FnoLongButterflyV1.Quote(p.provider(),l.key(),l.bid(),l.ask(),
            l.ivPercent(),l.bidDepthLots(),l.askDepthLots(),l.oiLots(),
            l.exchangeAt(),l.firstKnownAt());
    }
    private boolean valid(Proof p,VerifiedClose last,Instant now){
        if(p==null||last==null||p.provider()==null||p.index()==null
                ||p.lower()==null||p.center()==null||p.upper()==null||p.budget()==null
                ||!p.provider().equals(last.provider())
                ||p.lotSize()<1||p.tick()==null||p.tick().signum()<=0
                ||p.expiry()==null||p.firstKnownAt()==null||p.firstKnownAt().isAfter(now)
                ||!positive(p.index().bid())||!positive(p.index().ask())
                ||p.index().ask().compareTo(p.index().bid())<0
                ||!validTime(p.index().exchangeAt(),p.index().firstKnownAt(),last,now)
                ||p.firstKnownAt().isBefore(p.index().firstKnownAt()))
            return false;
        var legs=List.of(p.lower(),p.center(),p.upper());
        var keys=List.of(lowerKey,centerKey,upperKey);
        Instant min=p.index().exchangeAt(),max=p.index().exchangeAt();
        for(int i=0;i<legs.size();i++){
            var l=legs.get(i);
            if(!keys.get(i).equals(l.key())||!positive(l.strike())
                ||l.strike().remainder(p.tick()).signum()!=0
                ||l.listedFrom()==null||l.listedFrom().isAfter(now.atZone(IST).toLocalDate())
                ||!positive(l.bid())||!positive(l.ask())||l.ask().compareTo(l.bid())<0
                ||!positive(l.ivPercent())||l.bidDepthLots()<1
                ||l.askDepthLots()<1||l.oiLots()<1
                ||!validTime(l.exchangeAt(),l.firstKnownAt(),last,now)
                ||p.firstKnownAt().isBefore(l.firstKnownAt()))return false;
            if(l.exchangeAt().isBefore(min))min=l.exchangeAt();
            if(l.exchangeAt().isAfter(max))max=l.exchangeAt();
        }
        BigDecimal left=p.center().strike().subtract(p.lower().strike());
        BigDecimal right=p.upper().strike().subtract(p.center().strike());
        var budget=p.budget();
        return left.signum()>0 && left.compareTo(right)==0
            && p.lower().strike().compareTo(p.index().bid())<0
            && p.index().bid().compareTo(p.upper().strike())<0
            && p.center().bidDepthLots()>=2*budget.minDepthLots()
            && p.lower().askDepthLots()>=budget.minDepthLots()
            && p.upper().askDepthLots()>=budget.minDepthLots()
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
        var d=FnoLongButterflyV1.evaluate(null,null,null,List.of(),
            null,null,null,null,
            new FnoLongButterflyV1.Rights(!denied,!denied,!denied,!denied),
            null,now);
        return new FnoFuturesResearchService.StrategyStatus(
            FnoLongButterflyV1.STRATEGY_ID,d.status().name(),reason,
            now,"NONE",0,0,history,"LONG_BUTTERFLY_SOURCE_BLOCKED_NO_ORDER");
    }
}
