package in.aitradingsystem.tradingrooms;

import in.aitradingsystem.marketdata.FnoVerifiedNiftyDiagonalSourceStore;
import in.aitradingsystem.marketdata.FnoVerifiedNiftyDiagonalSourceStore.Leg;
import in.aitradingsystem.marketdata.FnoVerifiedNiftyDiagonalSourceStore.Proof;
import in.aitradingsystem.marketdata.FnoVerifiedNiftyLongOptionSourceStore;
import in.aitradingsystem.marketdata.FnoVerifiedNiftyLongOptionSourceStore.VerifiedClose;
import in.aitradingsystem.marketdata.MarketSessionCalendar;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * STR-200 source-backed, research-only long diagonal: sell the original near OTM bid,
 * buy original far ITM ask, SAME type/lot but DIFFERENT strikes AND expiries.
 * Never invent an expiry payoff or mark-to-market for the remaining far option.
 * All entitlement and exact contract keys are denied/blank by default.
 */
@Service
public final class FnoDiagonalSpreadResearchService {
    private static final String NIFTY=FnoLongOptionCrossoverV1.UNDERLYING;
    private static final ZoneId IST=ZoneId.of("Asia/Kolkata");
    private final FnoVerifiedNiftyLongOptionSourceStore daily;
    private final FnoVerifiedNiftyDiagonalSourceStore diagonals;
    private final MarketSessionCalendar diagonal;
    private final boolean dailyRights,chainRights,masterRights,ivRights,feesRights;
    private final String nearKey,farKey,optionType;

    @Autowired
    public FnoDiagonalSpreadResearchService(
        FnoVerifiedNiftyLongOptionSourceStore daily,
        FnoVerifiedNiftyDiagonalSourceStore diagonals,
        MarketSessionCalendar diagonal,
        @Value("$" + "{minttick.fno-research.nifty-daily-close-research-rights-verified:false}") boolean dailyRights,
        @Value("$" + "{minttick.fno-research.long-option-quote-research-rights-verified:false}") boolean chainRights,
        @Value("$" + "{minttick.fno-research.long-option-contract-discovery-verified:false}") boolean masterRights,
        @Value("$" + "{minttick.fno-research.diagonal-both-expiries-original-iv-rights-verified:false}") boolean ivRights,
        @Value("$" + "{minttick.fno-research.diagonal-two-leg-fees-rights-verified:false}") boolean feesRights,
        @Value("$" + "{minttick.fno-research.diagonal-near-short-key:}") String nearKey,
        @Value("$" + "{minttick.fno-research.diagonal-far-long-key:}") String farKey,
        @Value("$" + "{minttick.fno-research.diagonal-option-type:}") String optionType) {
        this.daily=daily;this.diagonals=diagonals;this.diagonal=diagonal;
        this.dailyRights=dailyRights;this.chainRights=chainRights;
        this.masterRights=masterRights;this.ivRights=ivRights;this.feesRights=feesRights;
        this.nearKey=clean(nearKey);this.farKey=clean(farKey);this.optionType=clean(optionType);
    }
    private static String clean(String x){return x==null?"":x.trim();}

    public FnoFuturesResearchService.StrategyStatus evaluate(Instant now) {
        if(now==null)throw new IllegalArgumentException("DIAGONAL_AS_OF_REQUIRED");
        if(!dailyRights||!chainRights||!masterRights||!ivRights||!feesRights)
            return blocked(now,"DIAGONAL_ORIGINAL_SOURCE_RIGHTS_UNVERIFIED",true,0);
        if(daily==null||diagonals==null||diagonal==null)
            return blocked(now,"DIAGONAL_SOURCE_UNAVAILABLE",false,0);
        if(!diagonal.isInsideCashSession(now))
            return blocked(now,"DIAGONAL_NSE_SESSION_CLOSED",false,0);
        if(!validKey(nearKey)||!validKey(farKey)||nearKey.equals(farKey)
                ||!("CE".equals(optionType)||"PE".equals(optionType)))
            return blocked(now,"DIAGONAL_TWO_EXPIRY_CONTRACT_KEYS_UNVERIFIED",false,0);
        final List<VerifiedClose> closes;
        try{closes=daily.verifiedRecentCloses(now);}
        catch(RuntimeException unavailable){
            return blocked(now,"DIAGONAL_COMPLETED_DAILY_HISTORY_READ_FAILED",false,0);
        }
        if(!FnoOptionsResearchService.validHistory(closes,diagonal,now))
            return blocked(now,"DIAGONAL_35_VERIFIED_INDEX_CLOSES_MISSING",
                false,closes==null?0:closes.size());
        final Optional<Proof> source;
        try{source=diagonals.oneCurrent(nearKey,farKey,optionType,now);}
        catch(RuntimeException unavailable){
            return blocked(now,"DIAGONAL_ORIGINAL_TWO_EXPIRY_SOURCE_READ_FAILED",
                false,closes.size());
        }
        if(source==null||source.isEmpty())
            return blocked(now,"DIAGONAL_INDEPENDENT_NEAR_FAR_IV_QUOTES_MISSING",
                false,closes.size());
        var p=source.get();
        if(!valid(p,closes.getLast(),now))
            return blocked(now,"DIAGONAL_EXPIRY_QUOTE_OR_IV_PROVENANCE_INVALID",
                false,closes.size());
        var bars=new ArrayList<FnoDiagonalSpreadV1.CompletedClose>();
        for(var close:closes)bars.add(new FnoDiagonalSpreadV1.CompletedClose(
            close.session(),close.provider(),close.close(),close.verifiedSessionCloseAt(),
            close.firstKnownAt(),true));
        var near=contract(p.near(),p);
        var far=contract(p.far(),p);
        var spot=new FnoDiagonalSpreadV1.Quote(p.provider(),NIFTY,p.index().bid(),
            p.index().ask(),null,1,1,1,
            p.index().exchangeAt(),p.index().firstKnownAt());
        var b=p.budget();
        var assumptions=new FnoDiagonalSpreadV1.Assumptions(
            b.buySlippageBps(),b.sellSlippageBps(),b.maxSpreadBps(),
            b.minDepthLots(),b.minOiLots(),b.maxStrikeDistanceBps(),b.maxIvPercent(),
            b.twoLegFeesInr(),b.researchCapitalInr(),b.maxNetDebitAllocationBps(),
            b.minNearFarIvRatioBps(),b.minNearPremiumFarPremiumBps(),
            b.maxNearOneSigmaBps());
        var result=FnoDiagonalSpreadV1.evaluate(near,far,List.copyOf(bars),spot,
            quote(p.near(),p),quote(p.far(),p),
            new FnoDiagonalSpreadV1.Rights(true,true,true,true),assumptions,now);
        if(!result.nearExpiryResidualLongMarkUnknown()
                ||result.executable()||result.orderPlaced()||result.liveTradeAuthorized())
            return blocked(now,"DIAGONAL_UNRESOLVED_FAR_MARK_AND_NO_ORDER_INVARIANT",false,closes.size());
        return new FnoFuturesResearchService.StrategyStatus(
            result.strategyId(),result.status().name(),result.reasonCode(),
            now,p.provider(),2,3,bars.size(),
            "ORIGINAL_DIFFERENT_EXPIRIES_FAR_MARK_UNRESOLVED_NO_ORDER");
    }
    private static FnoDiagonalSpreadV1.Contract contract(Leg leg,Proof p){
        return new FnoDiagonalSpreadV1.Contract(leg.key(),NIFTY,p.optionType(),
            leg.equals(p.near())?p.nearStrike():p.farStrike(),
            p.tick(),leg.listedFrom(),leg.expiry(),p.lotSize(),true);
    }
    private static FnoDiagonalSpreadV1.Quote quote(Leg l,Proof p){
        return new FnoDiagonalSpreadV1.Quote(p.provider(),l.key(),l.bid(),l.ask(),
            l.ivPercent(),l.bidDepthLots(),l.askDepthLots(),l.oiLots(),
            l.exchangeAt(),l.firstKnownAt());
    }
    private boolean valid(Proof p,VerifiedClose last,Instant now){
        if(p==null||last==null||p.provider()==null||p.index()==null
            ||p.near()==null||p.far()==null||p.budget()==null
            ||!p.provider().equals(last.provider())
            ||!optionType.equals(p.optionType())
            ||!positive(p.nearStrike())||!positive(p.farStrike())
            ||p.nearStrike().compareTo(p.farStrike())==0
            ||p.tick()==null||p.tick().signum()<=0||p.lotSize()<1
            ||p.nearStrike().remainder(p.tick()).signum()!=0
            ||p.farStrike().remainder(p.tick()).signum()!=0
            ||p.firstKnownAt()==null||p.firstKnownAt().isAfter(now)
            ||!positive(p.index().bid())||!positive(p.index().ask())
            ||p.index().ask().compareTo(p.index().bid())<0
            ||!validTime(p.index().exchangeAt(),p.index().firstKnownAt(),last,now)
            ||p.index().firstKnownAt().isAfter(p.firstKnownAt()))return false;
        var near=p.near();var far=p.far();
        for(var l:List.of(near,far)){
            if(!validKey(l.key())||l.listedFrom()==null
                ||l.listedFrom().isAfter(now.atZone(IST).toLocalDate())
                ||l.expiry()==null||!positive(l.bid())||!positive(l.ask())
                ||l.ask().compareTo(l.bid())<0||!positive(l.ivPercent())
                ||l.bidDepthLots()<1||l.askDepthLots()<1||l.oiLots()<1
                ||!validTime(l.exchangeAt(),l.firstKnownAt(),last,now)
                ||l.firstKnownAt().isAfter(p.firstKnownAt()))return false;
        }
        long gap=ChronoUnit.DAYS.between(near.expiry(),far.expiry());
        Instant first=p.index().exchangeAt();
        for(var l:List.of(near,far))if(l.exchangeAt().isBefore(first))first=l.exchangeAt();
        Instant lastExchange=p.index().exchangeAt();
        for(var l:List.of(near,far))if(l.exchangeAt().isAfter(lastExchange))lastExchange=l.exchangeAt();
        boolean oriented="CE".equals(optionType)
            ?p.farStrike().compareTo(p.index().bid())<0
                &&p.index().bid().compareTo(p.nearStrike())<0
            :p.nearStrike().compareTo(p.index().bid())<0
                &&p.index().bid().compareTo(p.farStrike())<0;
        return oriented && nearKey.equals(near.key()) && farKey.equals(far.key())
            && !near.key().equals(far.key())
            && gap>=7&&gap<=60
            && p.nearStrike().subtract(p.index().bid()).abs()
                .multiply(BigDecimal.valueOf(10000)).divide(p.index().bid(),
                    java.math.MathContext.DECIMAL128)
                .compareTo(BigDecimal.valueOf(p.budget().maxStrikeDistanceBps()))<=0
            && p.farStrike().subtract(p.index().bid()).abs()
                .multiply(BigDecimal.valueOf(10000)).divide(p.index().bid(),
                    java.math.MathContext.DECIMAL128)
                .compareTo(BigDecimal.valueOf(p.budget().maxStrikeDistanceBps()))<=0
            && near.expiry().isAfter(now.atZone(IST).toLocalDate())
            && Duration.between(first,lastExchange).compareTo(Duration.ofSeconds(90))<=0
            && near.bidDepthLots()>=p.budget().minDepthLots()
            && far.askDepthLots()>=p.budget().minDepthLots();
    }
    private static boolean validTime(Instant exchange,Instant known,VerifiedClose last,Instant now){
        return exchange!=null&&known!=null&&!exchange.isAfter(known)
            &&!known.isAfter(now)&&!exchange.isAfter(now)
            &&!last.firstKnownAt().isAfter(exchange)
            &&!Duration.between(exchange,now).isNegative()
            &&Duration.between(exchange,now).compareTo(Duration.ofSeconds(180))<=0;
    }
    private static boolean validKey(String s){return s!=null&&s.matches("NSE_FO\\|[A-Za-z0-9._-]{1,64}");}
    private static boolean positive(BigDecimal n){return n!=null&&n.signum()>0;}
    private static FnoFuturesResearchService.StrategyStatus blocked(
        Instant now,String reason,boolean denied,int history){
        var d=FnoDiagonalSpreadV1.evaluate(null,null,List.of(),null,null,null,
            new FnoDiagonalSpreadV1.Rights(!denied,!denied,!denied,!denied),
            null,now);
        return new FnoFuturesResearchService.StrategyStatus(
            FnoDiagonalSpreadV1.STRATEGY_ID,d.status().name(),reason,
            now,"NONE",0,0,history,"DIAGONAL_UNRESOLVED_FAR_MARK_SOURCE_BLOCKED_NO_ORDER");
    }
}
