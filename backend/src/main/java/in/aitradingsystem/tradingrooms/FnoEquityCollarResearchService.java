package in.aitradingsystem.tradingrooms;

import in.aitradingsystem.marketdata.FnoVerifiedEquityOwnedOptionStore;
import in.aitradingsystem.marketdata.FnoVerifiedEquityOwnedOptionStore.Option;
import in.aitradingsystem.marketdata.FnoVerifiedEquityOwnedOptionStore.VerifiedClose;
import in.aitradingsystem.marketdata.FnoVerifiedOwnedEquityCollarCostStore;
import in.aitradingsystem.marketdata.MarketSessionCalendar;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** STR-189: source-attested settled shares plus same lot PE and CE, and
 * distinct non-double-counted combined fee receipt. Research only. */
@Service
public final class FnoEquityCollarResearchService {
    private static final ZoneId IST=ZoneId.of("Asia/Kolkata");
    private final FnoVerifiedEquityOwnedOptionStore source;
    private final FnoVerifiedOwnedEquityCollarCostStore costSource;
    private final MarketSessionCalendar calendar;
    private final boolean closeRights,holdingsRights,chainRights,masterRights,feesRights,collarRights;
    private final String account,stock,putKey,callKey;
    @Autowired
    public FnoEquityCollarResearchService(
        FnoVerifiedEquityOwnedOptionStore source,
        FnoVerifiedOwnedEquityCollarCostStore costSource,
        MarketSessionCalendar calendar,
        @Value("${minttick.fno-research.owned-equity-close-rights-verified:false}") boolean closeRights,
        @Value("${minttick.fno-research.settled-holdings-research-rights-verified:false}") boolean holdingsRights,
        @Value("${minttick.fno-research.stock-option-chain-research-rights-verified:false}") boolean chainRights,
        @Value("${minttick.fno-research.stock-option-master-research-rights-verified:false}") boolean masterRights,
        @Value("${minttick.fno-research.owned-stock-option-fee-rights-verified:false}") boolean feesRights,
        @Value("${minttick.fno-research.collar-combined-fee-rights-verified:false}") boolean collarRights,
        @Value("${minttick.fno-research.owned-stock-account-scope:}") String account,
        @Value("${minttick.fno-research.owned-stock-equity-key:}") String stock,
        @Value("${minttick.fno-research.owned-stock-pe-key:}") String putKey,
        @Value("${minttick.fno-research.owned-stock-ce-key:}") String callKey){
        this.source=source;this.costSource=costSource;this.calendar=calendar;
        this.closeRights=closeRights;this.holdingsRights=holdingsRights;
        this.chainRights=chainRights;this.masterRights=masterRights;this.feesRights=feesRights;
        this.collarRights=collarRights;
        this.account=account==null?"":account.trim();
        this.stock=stock==null?"":stock.trim();
        this.putKey=putKey==null?"":putKey.trim();
        this.callKey=callKey==null?"":callKey.trim();
    }
    public FnoFuturesResearchService.StrategyStatus evaluate(Instant now){
        if(now==null)throw new IllegalArgumentException("COLLAR_ASOF_MISSING");
        boolean rights=closeRights&&holdingsRights&&chainRights&&masterRights
            &&feesRights&&collarRights;
        if(!rights)return blocked(now,"COLLAR_SOURCE_RIGHTS_UNVERIFIED",true,0);
        if(source==null||costSource==null||calendar==null)
            return blocked(now,"COLLAR_SOURCE_UNAVAILABLE",false,0);
        if(!calendar.isInsideCashSession(now))
            return blocked(now,"COLLAR_CASH_SESSION_CLOSED",false,0);
        if(!account.matches("[A-Za-z0-9_-]{8,80}")
           ||!stock.matches("NSE_EQ\\|[A-Za-z0-9._-]{1,64}")
           ||!putKey.matches("NSE_FO\\|[A-Za-z0-9._-]{1,64}")
           ||!callKey.matches("NSE_FO\\|[A-Za-z0-9._-]{1,64}")
           ||putKey.equals(callKey))
            return blocked(now,"COLLAR_ACCOUNT_OR_LEGS_NOT_CONFIGURED",false,0);
        final List<VerifiedClose> history;
        try{history=source.readCloses(stock,now);}catch(RuntimeException ignored){
            return blocked(now,"COLLAR_EQUITY_HISTORY_UNAVAILABLE",false,0);
        }
        if(!validHistory(history,now))return blocked(now,
            "COLLAR_35_COMPLETED_EQUITY_SESSIONS_MISSING",false,
            history==null?0:history.size());
        final Optional<Option> pe,ce;
        try{
            pe=source.oneSettledLot(account,stock,putKey,"PE",now);
            ce=source.oneSettledLot(account,stock,callKey,"CE",now);
        }catch(RuntimeException ignored){
            return blocked(now,"COLLAR_SETTLED_SHARES_OR_QUOTES_UNAVAILABLE",false,history.size());
        }
        if(pe==null||pe.isEmpty()||ce==null||ce.isEmpty())
            return blocked(now,"COLLAR_BOTH_OPTIONS_AND_SETTLED_SHARES_MISSING",
                false,history.size());
        Option p=pe.get(),c=ce.get();
        if(!validPair(p,c,history.getLast(),now))
            return blocked(now,"COLLAR_STOCK_OWNERSHIP_OR_OPTION_PAIR_MISMATCH",
                false,history.size());
        final Optional<FnoVerifiedOwnedEquityCollarCostStore.CostProof> cost;
        try{cost=costSource.oneCurrent(account,stock,putKey,callKey,p.expiry(),p.lotSize(),now);}
        catch(RuntimeException ignored){
            return blocked(now,"COLLAR_INDEPENDENT_COST_SOURCE_UNAVAILABLE",
                false,history.size());
        }
        if(cost==null||cost.isEmpty()||!p.provider().equals(cost.get().provider()))
            return blocked(now,"COLLAR_VERIFIED_UNDUPLICATED_COST_PROOF_MISSING",
                false,history.size());
        if (p.equityPrice().compareTo(cost.get().equityBid()) < 0
                || p.equityPrice().compareTo(cost.get().equityAsk()) > 0
                || !p.equityExchangeAt().equals(cost.get().equityExchangeAt())
                || !p.equityFirstKnownAt().equals(cost.get().equityFirstKnownAt())
                || c.equityPrice().compareTo(cost.get().equityBid()) < 0
                || c.equityPrice().compareTo(cost.get().equityAsk()) > 0)
            return blocked(now,"COLLAR_EQUITY_QUOTE_SOURCE_MISMATCH",
                false,history.size());
        var a=cost.get().assumptions();
        var put=new FnoEquityCollarV1.Contract(p.optionKey(),stock,"PE",p.strike(),
            p.tickSize(),p.listedFrom(),p.expiry(),p.lotSize(),true,true);
        var call=new FnoEquityCollarV1.Contract(c.optionKey(),stock,"CE",c.strike(),
            c.tickSize(),c.listedFrom(),c.expiry(),c.lotSize(),true,true);
        var holding=new FnoEquityCollarV1.Holding(p.provider(),stock,
            p.holding().settledOwnedShares(),p.holding().acquisitionCost(),
            p.holding().observedAt(),true,true);
        var equity=new FnoEquityCollarV1.Quote(p.provider(),stock,
            cost.get().equityBid(),cost.get().equityAsk(),
            1,1,1,cost.get().equityExchangeAt(),cost.get().equityFirstKnownAt());
        var putQ=new FnoEquityCollarV1.Quote(p.provider(),putKey,p.bid(),p.ask(),
            p.bidDepthLots(),p.askDepthLots(),p.oiLots(),
            p.optionExchangeAt(),p.optionFirstKnownAt());
        var callQ=new FnoEquityCollarV1.Quote(c.provider(),callKey,c.bid(),c.ask(),
            c.bidDepthLots(),c.askDepthLots(),c.oiLots(),
            c.optionExchangeAt(),c.optionFirstKnownAt());
        var bars=new ArrayList<FnoEquityCollarV1.Bar>();
        for(var h:history)bars.add(new FnoEquityCollarV1.Bar(h.session(),h.provider(),
            h.close(),h.verifiedSessionCloseAt(),h.firstKnownAt(),true));
        var d=FnoEquityCollarV1.evaluate(put,call,holding,List.copyOf(bars),
            equity,putQ,callQ,new FnoEquityCollarV1.Rights(true,true,true,true),a,now);
        return new FnoFuturesResearchService.StrategyStatus(d.strategyId(),
            d.status().name(),d.reasonCode(),now,p.provider(),2,3,bars.size(),
            "BROKER_SETTLED_EQUITY_COLLAR_SOURCE_RESEARCH_NO_ORDER");
    }
    private boolean validHistory(List<VerifiedClose> rows,Instant now){
        if(rows==null||rows.size()<35||rows.size()>90)return false;
        LocalDate prev=null;String provider=null;
        for(var h:rows){
            if(h==null||!stock.equals(h.equityKey())||h.session()==null
               ||h.close()==null||h.close().signum()<=0
               ||h.verifiedSessionCloseAt()==null||h.firstKnownAt()==null
               ||!h.verifiedSessionCloseAt().equals(h.session().atTime(15,30).atZone(IST).toInstant())
               ||h.firstKnownAt().isBefore(h.verifiedSessionCloseAt())
               ||h.firstKnownAt().isAfter(now)
               ||!calendar.isTradingDay(h.session())
               ||(provider!=null&&!provider.equals(h.provider()))
               ||(prev!=null&&!prev.isBefore(h.session())))return false;
            if(prev!=null)for(var day=prev.plusDays(1);day.isBefore(h.session());day=day.plusDays(1))
                if(calendar.isTradingDay(day))return false;
            prev=h.session();provider=h.provider();
        }
        return prev!=null&&!prev.isAfter(now.atZone(IST).toLocalDate())
            &&prev.isAfter(now.atZone(IST).toLocalDate().minusDays(5));
    }
    private boolean validPair(Option p,Option c,VerifiedClose last,Instant at){
        if(p==null||c==null||p.holding()==null||c.holding()==null
           ||!putKey.equals(p.optionKey())||!callKey.equals(c.optionKey())
           ||!"PE".equals(p.kind())||!"CE".equals(c.kind())
           ||!stock.equals(p.equityKey())||!stock.equals(c.equityKey())
           ||!account.equals(p.accountScope())||!account.equals(c.accountScope())
           ||!p.provider().equals(c.provider())||!p.provider().equals(last.provider())
           ||!p.provider().equals(p.holding().provider())
           ||!p.provider().equals(c.holding().provider())
           ||!account.equals(p.holding().accountScope())
           ||!account.equals(c.holding().accountScope())
           ||!p.expiry().equals(c.expiry())||p.lotSize()!=c.lotSize()
           ||p.lotSize()<1||p.holding().settledOwnedShares()!=p.lotSize()
           ||c.holding().settledOwnedShares()!=p.lotSize()
           ||!p.holding().observedAt().equals(c.holding().observedAt())
           ||p.holding().acquisitionCost().compareTo(c.holding().acquisitionCost())!=0
           ||!p.equityExchangeAt().equals(c.equityExchangeAt())
           ||p.equityPrice().compareTo(c.equityPrice())!=0
           ||p.masterDigest()==null||!p.masterDigest().equals(c.masterDigest())
           ||p.strike().compareTo(c.strike())>=0
           ||p.optionExchangeAt().isAfter(at)||c.optionExchangeAt().isAfter(at)
           ||Duration.between(p.optionExchangeAt(),c.optionExchangeAt()).abs()
                .compareTo(Duration.ofSeconds(90))>0
           ||last.firstKnownAt().isAfter(p.optionExchangeAt())
           ||last.firstKnownAt().isAfter(c.optionExchangeAt())
           ||Duration.between(p.holding().observedAt(),at).compareTo(Duration.ofMinutes(5))>0)
            return false;
        return true;
    }
    private static FnoFuturesResearchService.StrategyStatus blocked(
            Instant at,String reason,boolean rightsDenied,int history){
        var d=FnoEquityCollarV1.evaluate(null,null,null,List.of(),
            null,null,null,new FnoEquityCollarV1.Rights(
                !rightsDenied,!rightsDenied,!rightsDenied,!rightsDenied),null,at);
        return new FnoFuturesResearchService.StrategyStatus(
            d.strategyId(),d.status().name(),reason,at,"NONE",0,0,history,
            "COLLAR_SOURCE_BLOCKED");
    }
}
