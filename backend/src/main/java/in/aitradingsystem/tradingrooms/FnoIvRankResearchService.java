package in.aitradingsystem.tradingrooms;

import in.aitradingsystem.marketdata.FnoVerifiedNiftyIvRankSourceStore;
import in.aitradingsystem.marketdata.FnoVerifiedNiftyIvRankSourceStore.Historical;
import in.aitradingsystem.marketdata.MarketSessionCalendar;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * STR-201 original daily source-joined index OHLC plus 30-day constant-tenor
 * ATM IV ranks. No IV inference from near expiry quotes, no vendor-derived
 * placeholders, no broker/paper/live orders. An IV rank is NOT probability.
 */
@Service
public final class FnoIvRankResearchService {
    private static final ZoneId IST=ZoneId.of("Asia/Kolkata");
    private static final String NIFTY=FnoLongOptionCrossoverV1.UNDERLYING;
    private final FnoVerifiedNiftyIvRankSourceStore source;
    private final MarketSessionCalendar calendar;
    private final boolean indexRights,chainRights,ivHistoryRights,normalizationRights;
    private final FnoIvRankMeanReversionV1.Assumptions assumptions;
    @Autowired
    public FnoIvRankResearchService(
        FnoVerifiedNiftyIvRankSourceStore source,MarketSessionCalendar calendar,
        @Value("$" + "{minttick.fno-research.iv-rank-index-history-rights-verified:false}") boolean indexRights,
        @Value("$" + "{minttick.fno-research.iv-rank-chain-rights-verified:false}") boolean chainRights,
        @Value("$" + "{minttick.fno-research.iv-rank-licensed-iv-history-rights-verified:false}") boolean ivHistoryRights,
        @Value("$" + "{minttick.fno-research.iv-rank-original-30d-normalization-rights-verified:false}") boolean normalizationRights){
        this.source=source;this.calendar=calendar;
        this.indexRights=indexRights;this.chainRights=chainRights;
        this.ivHistoryRights=ivHistoryRights;this.normalizationRights=normalizationRights;
        this.assumptions=new FnoIvRankMeanReversionV1.Assumptions(
            200,450,180,300,new BigDecimal("8"),80,20,5,new BigDecimal("2"));
    }
    public FnoFuturesResearchService.StrategyStatus evaluate(Instant now){
        if(now==null)throw new IllegalArgumentException("IV_RANK_AS_OF_REQUIRED");
        if(!indexRights||!chainRights||!ivHistoryRights||!normalizationRights)
            return blocked(now,"IV_RANK_ORIGINAL_HISTORY_OR_IV_RIGHTS_UNVERIFIED",true,0);
        if(source==null||calendar==null)
            return blocked(now,"IV_RANK_ORIGINAL_SOURCE_UNAVAILABLE",false,0);
        if(!calendar.isInsideCashSession(now))
            return blocked(now,"IV_RANK_NSE_SESSION_CLOSED",false,0);
        final Optional<FnoVerifiedNiftyIvRankSourceStore.Series> evidence;
        try{evidence=source.oneCurrent(now);}
        catch(RuntimeException unavailable){
            return blocked(now,"IV_RANK_ORIGINAL_HISTORY_SOURCE_READ_FAILED",false,0);
        }
        if(evidence==null||evidence.isEmpty())
            return blocked(now,"IV_RANK_200_ALIGNED_IV_AND_OHLC_SESSIONS_MISSING",false,0);
        var data=evidence.get();
        if(data.current()==null||data.historical()==null||data.historical().size()<200
                ||data.historical().size()>370
                ||!NIFTY.equals(data.current().indexKey()))
            return blocked(now,"IV_RANK_HISTORY_OR_NORMALIZATION_INVALID",
                false,data.historical()==null?0:data.historical().size());
        var bars=new ArrayList<FnoIvRankMeanReversionV1.CompletedCandle>();
        var iv=new ArrayList<FnoIvRankMeanReversionV1.DailyIv>();
        for(var row:data.historical()){
            if(row==null||row.session()==null
                ||row.exchangeCloseAt()==null||row.candleFirstKnownAt()==null
                ||row.ivFirstKnownAt()==null
                ||!calendar.isTradingDay(row.session())
                ||!row.exchangeCloseAt().atZone(IST).toLocalTime().equals(LocalTime.of(15,30))
                ||row.candleFirstKnownAt().isAfter(now)
                ||row.ivFirstKnownAt().isAfter(now))
                return blocked(now,"IV_RANK_HISTORY_SESSION_ORIGINAL_TIME_INVALID",
                    false,bars.size());
            bars.add(new FnoIvRankMeanReversionV1.CompletedCandle(
                row.session(),row.provider(),row.open(),row.high(),row.low(),
                row.close(),row.volume(),row.exchangeCloseAt(),
                row.candleFirstKnownAt(),true));
            iv.add(new FnoIvRankMeanReversionV1.DailyIv(
                row.session(),row.provider(),row.annualized30DayIvPercent(),30,
                FnoIvRankMeanReversionV1.SOURCE_IV_METHOD,row.ivFirstKnownAt(),true));
        }
        var current=data.current();
        var normalized=new FnoIvRankMeanReversionV1.CurrentIv(
            current.provider(),current.indexKey(),current.annualized30DayIvPercent(),
            30,FnoIvRankMeanReversionV1.SOURCE_IV_METHOD,current.exchangeAt(),
            current.firstKnownAt(),true);
        var rights=new FnoIvRankMeanReversionV1.Rights(true,true,true,true);
        var decision=FnoIvRankMeanReversionV1.evaluate(List.copyOf(bars),
            List.copyOf(iv),normalized,rights,assumptions,now);
        if(decision.executable()||decision.orderPlaced()||decision.liveTradeAuthorized())
            return blocked(now,"IV_RANK_NO_ORDER_INVARIANT_FAILED",false,bars.size());
        return new FnoFuturesResearchService.StrategyStatus(
            decision.strategyId(),decision.status().name(),decision.reasonCode(),now,
            current.provider(),0,1,decision.historySessions(),
            "ORIGINAL_30_DAY_ATM_IV_200_SOURCE_ALIGNED_CANDLES_NO_ORDER");
    }
    private static FnoFuturesResearchService.StrategyStatus blocked(
        Instant now,String reason,boolean denied,int n){
        var d=FnoIvRankMeanReversionV1.evaluate(List.of(),List.of(),null,
            new FnoIvRankMeanReversionV1.Rights(!denied,!denied,!denied,!denied),
            null,now);
        return new FnoFuturesResearchService.StrategyStatus(
            FnoIvRankMeanReversionV1.STRATEGY_ID,d.status().name(),reason,
            now,"NONE",0,0,n,"IV_RANK_SOURCE_BLOCKED_NO_ORDER");
    }
}
