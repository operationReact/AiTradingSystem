package in.aitradingsystem.tradingrooms;

import in.aitradingsystem.marketdata.FnoVerifiedNiftyVolCrushSourceStore;
import in.aitradingsystem.marketdata.FnoVerifiedNiftyVolCrushSourceStore.Historical;
import in.aitradingsystem.marketdata.FnoVerifiedNiftyVolCrushSourceStore.Snapshot;
import in.aitradingsystem.marketdata.MarketSessionCalendar;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * STR-202 event-anchored, exclusively RETROSPECTIVE volatility crush research.
 * Observed pre/post 30-calendar-day normalized NIFTY IV does NOT imply
 * a pre-event trading opportunity, broker execution or positive PnL.
 */
@Service
public final class FnoVolatilityCrushResearchService {
    private static final ZoneId IST=ZoneId.of("Asia/Kolkata");
    private static final String NIFTY=FnoLongOptionCrossoverV1.UNDERLYING;
    private final FnoVerifiedNiftyVolCrushSourceStore source;
    private final MarketSessionCalendar calendar;
    private final boolean indexRights,chainRights,ivRights,calendarRights,normalizationRights;
    private final String eventKey;
    private final FnoVolatilityCrushV1.Assumptions assumptions;

    @Autowired
    public FnoVolatilityCrushResearchService(
        FnoVerifiedNiftyVolCrushSourceStore source,
        MarketSessionCalendar calendar,
        @Value("$" + "{minttick.fno-research.vol-crush-index-rights-verified:false}") boolean indexRights,
        @Value("$" + "{minttick.fno-research.vol-crush-chain-rights-verified:false}") boolean chainRights,
        @Value("$" + "{minttick.fno-research.vol-crush-original-iv-rights-verified:false}") boolean ivRights,
        @Value("$" + "{minttick.fno-research.vol-crush-official-event-calendar-rights-verified:false}") boolean calendarRights,
        @Value("$" + "{minttick.fno-research.vol-crush-normalized-30d-iv-rights-verified:false}") boolean normalizationRights,
        @Value("$" + "{minttick.fno-research.vol-crush-original-event-key:}") String eventKey){
        this.source=source;this.calendar=calendar;
        this.indexRights=indexRights;this.chainRights=chainRights;
        this.ivRights=ivRights;this.calendarRights=calendarRights;
        this.normalizationRights=normalizationRights;
        this.eventKey=eventKey==null?"":eventKey.trim();
        this.assumptions=new FnoVolatilityCrushV1.Assumptions(
            35,90,24,180,200,new BigDecimal("5"),1500,600);
    }
    public FnoFuturesResearchService.StrategyStatus evaluate(Instant now){
        if(now==null)throw new IllegalArgumentException("VOL_CRUSH_AS_OF_REQUIRED");
        if(!indexRights||!chainRights||!ivRights||!calendarRights||!normalizationRights)
            return blocked(now,"VOL_CRUSH_ORIGINAL_EVENT_AND_IV_RIGHTS_UNVERIFIED",true,0);
        if(source==null||calendar==null)
            return blocked(now,"VOL_CRUSH_ORIGINAL_SOURCE_UNAVAILABLE",false,0);
        if(!eventKey.matches("[A-Za-z0-9._:-]{3,80}"))
            return blocked(now,"VOL_CRUSH_OFFICIAL_EVENT_KEY_UNVERIFIED",false,0);
        final Optional<FnoVerifiedNiftyVolCrushSourceStore.Source> evidence;
        try{evidence=source.oneCurrent(eventKey,now);}
        catch(RuntimeException unavailable){
            return blocked(now,"VOL_CRUSH_ORIGINAL_EVENT_SOURCE_READ_FAILED",false,0);
        }
        if(evidence==null||evidence.isEmpty())
            return blocked(now,"VOL_CRUSH_EVENT_PRE_POST_IV_AND_OHLC_MISSING",false,0);
        var p=evidence.get();
        if(!valid(p,now))
            return blocked(now,"VOL_CRUSH_EVENT_SOURCE_OR_TIMING_INVALID",
                false,p.history()==null?0:p.history().size());
        var e=p.event();
        var event=new FnoVolatilityCrushV1.Event(e.eventKey(),e.category(),
            e.calendarProvider(),e.scheduleKnownAt(),e.startAt(),e.endAt(),true);
        var before=observation(p.pre());
        var after=observation(p.post());
        var bars=new ArrayList<FnoVolatilityCrushV1.CompletedCandle>();
        for(var h:p.history()){
            if(!calendar.isTradingDay(h.session()) || h.firstKnownAt().isAfter(now)
                ||!h.firstKnownAt().isBefore(e.startAt()))
                return blocked(now,"VOL_CRUSH_EVENT_HISTORY_AS_OF_INVALID",false,bars.size());
            bars.add(new FnoVolatilityCrushV1.CompletedCandle(
                h.session(),h.provider(),h.open(),h.high(),h.low(),h.close(),
                h.exchangeCloseAt(),h.firstKnownAt(),true));
        }
        var result=FnoVolatilityCrushV1.evaluate(event,List.copyOf(bars),
            before,after,new FnoVolatilityCrushV1.Rights(true,true,true,true,true),
            assumptions,now);
        if(!result.retrospectiveOnly()||result.executable()||result.orderPlaced()
            ||result.liveTradeAuthorized())
            return blocked(now,"VOL_CRUSH_RETROSPECTIVE_ONLY_NO_ORDER_INVARIANT_FAILED",
                false,bars.size());
        return new FnoFuturesResearchService.StrategyStatus(
            result.strategyId(),result.status().name(),result.reasonCode(),now,
            p.pre().provider(),0,2,bars.size(),
            "ORIGINAL_OFFICIAL_EVENT_PRE_POST_30D_IV_OBSERVATION_RETROSPECTIVE_NO_ORDER");
    }
    private static FnoVolatilityCrushV1.Observation observation(Snapshot s){
        return new FnoVolatilityCrushV1.Observation(s.provider(),NIFTY,s.spot(),
            s.annualized30DayIv(),30,FnoVolatilityCrushV1.IV_METHOD,
            s.exchangeAt(),s.firstKnownAt(),true);
    }
    private boolean valid(FnoVerifiedNiftyVolCrushSourceStore.Source p,Instant now){
        if(p==null||p.event()==null||p.pre()==null||p.post()==null
           ||p.history()==null||p.history().size()<35||p.history().size()>370)
            return false;
        var e=p.event();var before=p.pre();var after=p.post();
        if(!eventKey.equals(e.eventKey())||e.scheduleKnownAt()==null
           ||e.startAt()==null||e.endAt()==null
           ||!e.scheduleKnownAt().isBefore(e.startAt())
           ||!e.startAt().isBefore(e.endAt())
           ||e.endAt().isAfter(now)||e.firstKnownAt()==null||e.firstKnownAt().isAfter(now)
           ||before.provider()==null||!before.provider().equals(after.provider())
           ||!before.provider().equals(e.provider())
           ||!positive(before.spot())||!positive(after.spot())
           ||!positive(before.annualized30DayIv())||!positive(after.annualized30DayIv())
           ||before.exchangeAt()==null||before.firstKnownAt()==null
           ||after.exchangeAt()==null||after.firstKnownAt()==null
           ||!before.exchangeAt().isBefore(e.startAt())
           ||before.firstKnownAt().isAfter(e.startAt())
           ||!e.scheduleKnownAt().isBefore(before.firstKnownAt())
           ||!after.exchangeAt().isAfter(e.endAt())
           ||after.exchangeAt().isAfter(after.firstKnownAt())
           ||after.firstKnownAt().isAfter(now))
            return false;
        for(Historical h:p.history()){
            if(h==null||h.session()==null||h.exchangeCloseAt()==null
               ||h.firstKnownAt()==null||!e.provider().equals(h.provider())
               ||!positive(h.open())||!positive(h.high())||!positive(h.low())
               ||!positive(h.close())
               ||!h.firstKnownAt().isBefore(e.startAt())
               ||!h.exchangeCloseAt().atZone(IST).toLocalTime().equals(LocalTime.of(15,30)))
                return false;
        }
        return true;
    }
    private static boolean positive(BigDecimal n){return n!=null&&n.signum()>0;}
    private static FnoFuturesResearchService.StrategyStatus blocked(
        Instant at,String reason,boolean rightsDenied,int n){
        var d=FnoVolatilityCrushV1.evaluate(null,List.of(),null,null,
            new FnoVolatilityCrushV1.Rights(!rightsDenied,!rightsDenied,
                !rightsDenied,!rightsDenied,!rightsDenied),null,at);
        return new FnoFuturesResearchService.StrategyStatus(
            FnoVolatilityCrushV1.STRATEGY_ID,d.status().name(),reason,at,"NONE",
            0,0,n,"VOL_CRUSH_RETROSPECTIVE_SOURCE_BLOCKED_NO_ORDER");
    }
}
