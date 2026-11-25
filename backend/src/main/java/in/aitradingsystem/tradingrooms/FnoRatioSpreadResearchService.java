package in.aitradingsystem.tradingrooms;

import in.aitradingsystem.marketdata.FnoVerifiedNiftyLongOptionSourceStore;
import in.aitradingsystem.marketdata.FnoVerifiedNiftyLongOptionSourceStore.VerifiedClose;
import in.aitradingsystem.marketdata.FnoVerifiedRatioSpreadSourceStore;
import in.aitradingsystem.marketdata.FnoVerifiedRatioSpreadSourceStore.Proof;
import in.aitradingsystem.marketdata.FnoVerifiedRatioSpreadSourceStore.OptionLeg;
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
 * STR-190 (one call long, TWO uncovered short calls) and STR-191
 * (one long put, TWO short puts) are distinct RESEARCH ONLY paths.
 * Call upside loss remains unbounded. Put S=0 finite limit is not
 * broker liquidation/margin coverage. No order or capital mutation.
 */
@Service
public final class FnoRatioSpreadResearchService {
    private static final String NIFTY=FnoLongOptionCrossoverV1.UNDERLYING;
    private static final ZoneId IST=ZoneId.of("Asia/Kolkata");
    private final FnoVerifiedNiftyLongOptionSourceStore daily;
    private final FnoVerifiedRatioSpreadSourceStore ratios;
    private final MarketSessionCalendar calendar;
    private final boolean dailyRights,quoteRights,masterRights,ratioRights;
    private final String account,callLong,callShort,putLong,putShort;
    @Autowired
    public FnoRatioSpreadResearchService(
        FnoVerifiedNiftyLongOptionSourceStore daily,
        FnoVerifiedRatioSpreadSourceStore ratios,
        MarketSessionCalendar calendar,
        @Value("${minttick.fno-research.nifty-daily-close-research-rights-verified:false}")
            boolean dailyRights,
        @Value("${minttick.fno-research.long-option-quote-research-rights-verified:false}")
            boolean quoteRights,
        @Value("${minttick.fno-research.long-option-contract-discovery-verified:false}")
            boolean masterRights,
        @Value("${minttick.fno-research.ratio-research-rights-verified:false}")
            boolean ratioRights,
        @Value("${minttick.fno-research.ratio-account-scope:}") String account,
        @Value("${minttick.fno-research.ratio-call-long-ce-key:}") String callLong,
        @Value("${minttick.fno-research.ratio-call-short-ce-key:}") String callShort,
        @Value("${minttick.fno-research.ratio-put-long-pe-key:}") String putLong,
        @Value("${minttick.fno-research.ratio-put-short-pe-key:}") String putShort) {
        this.daily=daily;this.ratios=ratios;this.calendar=calendar;
        this.dailyRights=dailyRights;this.quoteRights=quoteRights;
        this.masterRights=masterRights;this.ratioRights=ratioRights;
        this.account=account==null?"":account.trim();
        this.callLong=callLong==null?"":callLong.trim();
        this.callShort=callShort==null?"":callShort.trim();
        this.putLong=putLong==null?"":putLong.trim();
        this.putShort=putShort==null?"":putShort.trim();
    }
    public List<FnoFuturesResearchService.StrategyStatus> evaluateAll(Instant now){
        if(now==null)throw new IllegalArgumentException("RATIO_RESEARCH_ASOF_MISSING");
        if(!dailyRights||!quoteRights||!masterRights||!ratioRights)
            return both(now,"RATIO_SOURCE_RIGHTS_UNVERIFIED",true,0);
        if(daily==null||ratios==null||calendar==null)
            return both(now,"RATIO_SOURCE_UNAVAILABLE",false,0);
        if(!calendar.isInsideCashSession(now))
            return both(now,"RATIO_CASH_EXCHANGE_SESSION_CLOSED",false,0);
        if(!account.matches("[A-Za-z0-9_-]{8,80}"))
            return both(now,"RATIO_BROKER_MARGIN_ACCOUNT_NOT_CONFIGURED",false,0);
        if(!validKeys(callLong,callShort)&&!validKeys(putLong,putShort))
            return both(now,"RATIO_SOURCE_CONTRACT_NOT_CONFIGURED",false,0);
        final List<VerifiedClose> closes;
        try{closes=daily.verifiedRecentCloses(now);}catch(RuntimeException ignored){
            return both(now,"RATIO_DAILY_CLOSE_SOURCE_UNAVAILABLE",false,0);
        }
        if(!FnoOptionsResearchService.validHistory(closes,calendar,now))
            return both(now,"RATIO_35_VERIFIED_CLOSES_MISSING",false,
                closes==null?0:closes.size());
        return List.of(one(true,closes,now),one(false,closes,now));
    }
    private List<FnoFuturesResearchService.StrategyStatus> both(
            Instant now,String reason,boolean denied,int history){
        return List.of(blocked(true,now,reason,denied,history),
                       blocked(false,now,reason,denied,history));
    }
    private FnoFuturesResearchService.StrategyStatus one(
            boolean call,List<VerifiedClose> history,Instant now){
        String longKey=call?callLong:putLong;
        String shortKey=call?callShort:putShort;
        if(!validKeys(longKey,shortKey))
            return blocked(call,now,"RATIO_SOURCE_CONTRACT_NOT_CONFIGURED",
                false,history.size());
        final Optional<Proof> attested;
        try{attested=ratios.oneCurrent(account,call?"CALL":"PUT",longKey,shortKey,now);}
        catch(RuntimeException ignored){
            return blocked(call,now,"RATIO_SOURCE_READ_UNAVAILABLE",
                false,history.size());
        }
        if(attested==null||attested.isEmpty())
            return blocked(call,now,"RATIO_TWO_SHORT_LOT_DEPTH_MARGIN_FEES_MISSING",
                false,history.size());
        var p=attested.get();
        if(!account.equals(p.accountScope())||!valid(p,history.getLast(),call,longKey,shortKey,now))
            return blocked(call,now,"RATIO_PROVIDER_CONTRACT_RISK_OR_TIME_MISMATCH",
                false,history.size());
        if(call){
            var bars=new ArrayList<FnoCallRatioSpreadV1.Bar>();
            for(var c:history)bars.add(new FnoCallRatioSpreadV1.Bar(c.session(),
                c.provider(),c.close(),c.verifiedSessionCloseAt(),c.firstKnownAt(),true));
            var l=callContract(p.longLeg());
            var h=callContract(p.shortLeg());
            var spot=new FnoCallRatioSpreadV1.Quote(p.provider(),NIFTY,
                p.index().bid(),p.index().ask(),1,1,1,
                p.index().exchangeAt(),p.index().firstKnownAt());
            var lq=callQuote(p.longLeg(),p.provider());
            var sq=callQuote(p.shortLeg(),p.provider());
            var r=p.risk();
            var a=new FnoCallRatioSpreadV1.Assumptions(
                r.buySlippageBps(),r.sellSlippageBps(),r.maxOptionSpreadBps(),
                r.minDepthLots(),r.minOiLots(),r.maxStrikeDistanceBps(),
                r.threeLegFeesInr(),r.capitalInr(),r.maxLossAllocationBps(),
                r.stressUnderlyingPrice(),r.minPeakRewardBps());
            var d=FnoCallRatioSpreadV1.evaluate(l,h,List.copyOf(bars),spot,
                lq,sq,new FnoCallRatioSpreadV1.Rights(true,true,true),a,now);
            return status(d.strategyId(),d.status().name(),d.reasonCode(),now,
                p.provider(),history.size(),"CALL_RATIO_UNBOUNDED_TAIL_RESEARCH_NO_ORDER");
        }
        var bars=new ArrayList<FnoPutRatioSpreadV1.Bar>();
        for(var c:history)bars.add(new FnoPutRatioSpreadV1.Bar(c.session(),
            c.provider(),c.close(),c.verifiedSessionCloseAt(),c.firstKnownAt(),true));
        var l=putContract(p.longLeg());
        var h=putContract(p.shortLeg());
        var spot=new FnoPutRatioSpreadV1.Quote(p.provider(),NIFTY,
            p.index().bid(),p.index().ask(),1,1,1,
            p.index().exchangeAt(),p.index().firstKnownAt());
        var lq=putQuote(p.longLeg(),p.provider());
        var sq=putQuote(p.shortLeg(),p.provider());
        var r=p.risk();
        var a=new FnoPutRatioSpreadV1.Assumptions(
            r.buySlippageBps(),r.sellSlippageBps(),r.maxOptionSpreadBps(),
            r.minDepthLots(),r.minOiLots(),r.maxStrikeDistanceBps(),
            r.threeLegFeesInr(),r.capitalInr(),r.maxLossAllocationBps(),
            r.stressUnderlyingPrice(),r.minPeakRewardBps());
        var d=FnoPutRatioSpreadV1.evaluate(l,h,List.copyOf(bars),spot,
            lq,sq,new FnoPutRatioSpreadV1.Rights(true,true,true),a,now);
        return status(d.strategyId(),d.status().name(),d.reasonCode(),now,
            p.provider(),history.size(),"PUT_RATIO_FINITE_EXPIRY_TAIL_RESEARCH_NO_ORDER");
    }
    private static FnoCallRatioSpreadV1.Contract callContract(OptionLeg l){
        return new FnoCallRatioSpreadV1.Contract(l.key(),NIFTY,"CE",
            l.strike(),l.tick(),l.listedFrom(),l.expiry(),l.lotSize(),true);
    }
    private static FnoPutRatioSpreadV1.Contract putContract(OptionLeg l){
        return new FnoPutRatioSpreadV1.Contract(l.key(),NIFTY,"PE",
            l.strike(),l.tick(),l.listedFrom(),l.expiry(),l.lotSize(),true);
    }
    private static FnoCallRatioSpreadV1.Quote callQuote(OptionLeg l,String provider){
        return new FnoCallRatioSpreadV1.Quote(provider,l.key(),l.bid(),l.ask(),
            l.bidDepthLots(),l.askDepthLots(),l.oiLots(),l.exchangeAt(),l.firstKnownAt());
    }
    private static FnoPutRatioSpreadV1.Quote putQuote(OptionLeg l,String provider){
        return new FnoPutRatioSpreadV1.Quote(provider,l.key(),l.bid(),l.ask(),
            l.bidDepthLots(),l.askDepthLots(),l.oiLots(),l.exchangeAt(),l.firstKnownAt());
    }
    private static boolean valid(Proof p,VerifiedClose last,boolean call,
            String longKey,String shortKey,Instant now){
        if(p==null||p.provider()==null||last==null||!p.provider().equals(last.provider())
           ||!NIFTY.equals(p.indexKey())
           ||!(call?"CALL":"PUT").equals(p.ratioSide())
           ||p.longLeg()==null||p.shortLeg()==null||p.index()==null||p.risk()==null
           ||!longKey.equals(p.longLeg().key())||!shortKey.equals(p.shortLeg().key())
           ||p.contractDigest()==null||!p.contractDigest().matches("[a-f0-9]{64}")
           ||p.longLeg().expiry()==null||!p.longLeg().expiry().equals(p.shortLeg().expiry())
           ||p.longLeg().lotSize()<1
           ||p.longLeg().lotSize()!=p.shortLeg().lotSize()
           ||p.longLeg().strike()==null||p.shortLeg().strike()==null
           ||(call?p.longLeg().strike().compareTo(p.shortLeg().strike())>=0
                    :p.longLeg().strike().compareTo(p.shortLeg().strike())<=0)
           ||p.index().bid()==null||p.index().ask()==null
           ||p.index().bid().signum()<=0
           ||p.index().ask().compareTo(p.index().bid())<0
           ||p.index().exchangeAt()==null||p.index().firstKnownAt()==null
           ||p.index().firstKnownAt().isAfter(now)
           ||last.firstKnownAt().isAfter(p.index().exchangeAt())
           ||p.risk().brokerMarginReserveInr()==null
           ||p.risk().brokerMarginReserveInr().signum()<=0
           ||p.risk().threeLegFeesInr()==null||p.risk().threeLegFeesInr().signum()<=0)
            return false;
        Instant min=p.index().exchangeAt(),max=min;
        for(var l:List.of(p.longLeg(),p.shortLeg())){
            if(l.bid()==null||l.ask()==null||l.bid().signum()<=0
               ||l.ask().compareTo(l.bid())<0
               ||l.bidDepthLots()<1||l.askDepthLots()<1||l.oiLots()<1
               ||l.exchangeAt()==null||l.firstKnownAt()==null
               ||l.exchangeAt().isAfter(l.firstKnownAt())
               ||l.firstKnownAt().isAfter(now)
               ||l.tick()==null||l.tick().signum()<=0
               ||l.strike().remainder(l.tick()).signum()!=0
               ||l.listedFrom()==null
               ||l.listedFrom().isAfter(now.atZone(IST).toLocalDate())
               ||last.firstKnownAt().isAfter(l.exchangeAt()))
                return false;
            if(l.exchangeAt().isBefore(min))min=l.exchangeAt();
            if(l.exchangeAt().isAfter(max))max=l.exchangeAt();
        }
        if(p.shortLeg().bidDepthLots()<Math.max(2,2*p.risk().minDepthLots()))
            return false;
        return Duration.between(min,max).compareTo(Duration.ofSeconds(90))<=0
            && Duration.between(max,now).compareTo(Duration.ofSeconds(180))<=0
            && p.longLeg().expiry().isAfter(now.atZone(IST).toLocalDate().plusDays(2))
            && !p.longLeg().expiry().isAfter(now.atZone(IST).toLocalDate().plusDays(45));
    }
    private static boolean validKeys(String l,String s){
        return l!=null&&s!=null&&!l.equals(s)
            &&l.matches("NSE_FO\\|[A-Za-z0-9._-]{1,80}")
            &&s.matches("NSE_FO\\|[A-Za-z0-9._-]{1,80}");
    }
    private static FnoFuturesResearchService.StrategyStatus blocked(
            boolean call,Instant at,String reason,boolean denied,int bars){
        String status=call
            ?FnoCallRatioSpreadV1.evaluate(null,null,List.of(),null,null,null,
                new FnoCallRatioSpreadV1.Rights(!denied,!denied,!denied),
                null,at).status().name()
            :FnoPutRatioSpreadV1.evaluate(null,null,List.of(),null,null,null,
                new FnoPutRatioSpreadV1.Rights(!denied,!denied,!denied),
                null,at).status().name();
        return new FnoFuturesResearchService.StrategyStatus(
            call?FnoCallRatioSpreadV1.STRATEGY_ID:FnoPutRatioSpreadV1.STRATEGY_ID,
            status,reason,at,"NONE",0,0,bars,"RATIO_SOURCE_BLOCKED");
    }
    private static FnoFuturesResearchService.StrategyStatus status(
            String id,String state,String reason,Instant at,String source,int history,String input){
        return new FnoFuturesResearchService.StrategyStatus(id,state,reason,at,
            source,2,3,history,input);
    }
}
