package in.aitradingsystem.tradingrooms;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

/**
 * STR-202: retrospective, event-anchored, research-only observed volatility
 * crush on separately licensed NIFTY ATM constant-30D IV observations.
 *
 * A measured post-event IV decline is NOT a trading signal or an assertion
 * that a short-vol position could have profitably been entered before the event.
 * All decisions are nonexecutable, regardless of their status.
 */
public final class FnoVolatilityCrushV1 {
    public static final String STRATEGY_ID = "strategy.STR-202";
    public static final String IV_METHOD = "LICENSED_ATM_30D_INTERPOLATION";
    public static final int TENOR_DAYS = 30;
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal BPS = new BigDecimal("10000");
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime CLOSE = LocalTime.of(15,30);
    private static final LocalTime MARKET_OPEN = LocalTime.of(9,15);
    private FnoVolatilityCrushV1() {}

    public enum Status { BLOCKED_RIGHTS, BLOCKED_DATA, NO_SIGNAL, RESEARCH_CANDIDATE }

    public record Rights(boolean indexResearch, boolean optionChainResearch,
                         boolean independentIvResearch, boolean eventCalendarResearch,
                         boolean constantTenorNormalizationResearch) {}
    public record CompletedCandle(LocalDate session, String provider,
                                  BigDecimal open, BigDecimal high, BigDecimal low,
                                  BigDecimal close, Instant exchangeCloseAt,
                                  Instant firstKnownAt, boolean sourceVerified) {}
    /** The event source and scheduled release must be authenticated upstream. */
    public record Event(String eventKey, String eventCategory, String calendarProvider,
                        Instant scheduleFirstKnownAt, Instant eventStart, Instant eventEnd,
                        boolean independentlySourceVerified) {}
    /** Annualized IV in percentage points. Also carries true underlying index spot. */
    public record Observation(String provider, String underlyingKey, BigDecimal indexSpot,
                              BigDecimal atmIvPercent, int constantTenorDays,
                              String ivMethod, Instant exchangeAt,
                              Instant firstKnownAt, boolean independentlySourceVerified) {}
    public record Assumptions(int minimumCompletedSessions, int maxHistoryCalendarDays,
                              int maximumPreEventAgeHours, int maxPostQuoteAgeSeconds,
                              int maximumIvPercent, BigDecimal minDropIvPoints,
                              int minRelativeDropBps, int maxAbsoluteIndexMoveBps) {}
    public record Decision(Status status, String reasonCode, String strategyId,
                           String verifiedEventKey, Instant asOf,
                           Instant preEventQuoteAt, Instant postEventQuoteAt,
                           BigDecimal preEventIvPercent, BigDecimal postEventIvPercent,
                           BigDecimal observedIvDropPoints,
                           BigDecimal relativeIvDropBps,
                           BigDecimal observedAbsIndexMoveBps,
                           boolean retrospectiveOnly) {
        public boolean executable() { return false; }
        public boolean orderPlaced() { return false; }
        public boolean liveTradeAuthorized() { return false; }
    }

    public static Decision evaluate(Event event, List<CompletedCandle> completedIndexHistory,
                                    Observation beforeEvent, Observation afterEvent,
                                    Rights rights, Assumptions a, Instant asOf) {
        Objects.requireNonNull(asOf, "asOf");
        if (rights==null || !rights.indexResearch() || !rights.optionChainResearch()
                || !rights.independentIvResearch() || !rights.eventCalendarResearch()
                || !rights.constantTenorNormalizationResearch())
            return blocked(Status.BLOCKED_RIGHTS,"VOL_CRUSH_RIGHTS_UNVERIFIED",asOf);
        if (!assumptionsValid(a) || !eventValid(event,asOf))
            return blocked(Status.BLOCKED_DATA,"VOL_CRUSH_EVENT_OR_ASSUMPTIONS_INVALID",asOf);
        if (!historyValid(completedIndexHistory,event,a,asOf))
            return blocked(Status.BLOCKED_DATA,"VOL_CRUSH_HISTORY_INVALID",asOf);
        if (!observationsValid(beforeEvent,afterEvent,event,
                completedIndexHistory.getLast(),a,asOf))
            return blocked(Status.BLOCKED_DATA,"VOL_CRUSH_SOURCE_IV_OR_TIMING_INVALID",asOf);

        BigDecimal drop=beforeEvent.atmIvPercent().subtract(afterEvent.atmIvPercent(),MC);
        BigDecimal dropBps=drop.multiply(BPS,MC).divide(beforeEvent.atmIvPercent(),MC);
        BigDecimal indexMove=afterEvent.indexSpot().subtract(beforeEvent.indexSpot(),MC)
            .abs().multiply(BPS,MC).divide(beforeEvent.indexSpot(),MC);
        if (drop.signum()<=0)
            return observed(Status.NO_SIGNAL,"VOL_CRUSH_NO_OBSERVED_IV_DROP",
                event,beforeEvent,afterEvent,asOf,drop,dropBps,indexMove);
        if (drop.compareTo(a.minDropIvPoints())<0
                ||dropBps.compareTo(BigDecimal.valueOf(a.minRelativeDropBps()))<0)
            return observed(Status.NO_SIGNAL,"VOL_CRUSH_DROP_TOO_SMALL",
                event,beforeEvent,afterEvent,asOf,drop,dropBps,indexMove);
        if (indexMove.compareTo(BigDecimal.valueOf(a.maxAbsoluteIndexMoveBps()))>0)
            return observed(Status.NO_SIGNAL,"VOL_CRUSH_INDEX_SHOCK_EXCEEDS_RESEARCH_GATE",
                event,beforeEvent,afterEvent,asOf,drop,dropBps,indexMove);

        // Event is already over and both normalized 30D IV points were OBSERVED.
        // This is descriptive post-event research and must never be reinterpreted
        // as a trade that was available *before* the event.
        return observed(Status.RESEARCH_CANDIDATE,"VOL_CRUSH_POST_EVENT_OBSERVATION_ONLY",
            event,beforeEvent,afterEvent,asOf,drop,dropBps,indexMove);
    }

    private static boolean eventValid(Event e, Instant asOf) {
        if(e==null || !e.independentlySourceVerified()
                || !eventKey(e.eventKey()) || !eventCategory(e.eventCategory())
                || !calendarProvider(e.calendarProvider())
                || e.scheduleFirstKnownAt()==null || e.eventStart()==null
                || e.eventEnd()==null || !e.scheduleFirstKnownAt().isBefore(e.eventStart())
                || !e.eventStart().isBefore(e.eventEnd())
                || e.eventEnd().isAfter(asOf))return false;
        var start=e.eventStart().atZone(IST);
        var end=e.eventEnd().atZone(IST);
        // This version only classifies verified announcements strictly within
        // an ordinary NSE weekday cash session. Other sessions need a calendar.
        return start.getDayOfWeek().getValue()<=5
            && start.toLocalDate().equals(end.toLocalDate())
            && !start.toLocalTime().isBefore(MARKET_OPEN)
            && !end.toLocalTime().isAfter(CLOSE)
            && Duration.between(e.eventStart(),e.eventEnd())
                .compareTo(Duration.ofHours(3))<=0;
    }

    private static boolean historyValid(List<CompletedCandle> bars,Event event,
                                        Assumptions a, Instant asOf) {
        if(bars==null||bars.size()<a.minimumCompletedSessions()||bars.size()>370)return false;
        LocalDate prior=null;String source=null;
        for(var c:bars) {
            if(c==null||!c.sourceVerified()||c.session()==null
                    ||c.session().getDayOfWeek().getValue()>5
                    ||!provider(c.provider())||!positive(c.open())||!positive(c.high())
                    ||!positive(c.low())||!positive(c.close())
                    ||c.high().compareTo(c.open())<0||c.high().compareTo(c.close())<0
                    ||c.low().compareTo(c.open())>0||c.low().compareTo(c.close())>0
                    ||c.exchangeCloseAt()==null||c.firstKnownAt()==null
                    ||source!=null&&!source.equals(c.provider()))return false;
            Instant close=c.session().atTime(CLOSE).atZone(IST).toInstant();
            if(!close.equals(c.exchangeCloseAt())
                    ||c.firstKnownAt().isBefore(close)
                    ||c.firstKnownAt().isAfter(close.plus(Duration.ofHours(36)))
                    ||!c.firstKnownAt().isBefore(event.eventStart())
                    ||c.firstKnownAt().isAfter(asOf))return false;
            if(prior!=null){
                long gap=ChronoUnit.DAYS.between(prior,c.session());
                if(gap<1||gap>5)return false;
            }
            prior=c.session();source=c.provider();
        }
        long historySpan=ChronoUnit.DAYS.between(bars.getFirst().session(),prior);
        long freshness=ChronoUnit.DAYS.between(prior,event.eventStart().atZone(IST).toLocalDate());
        return historySpan<=a.maxHistoryCalendarDays() && freshness>=0 && freshness<=4;
    }

    private static boolean observationsValid(Observation pre,Observation post,Event e,
                                             CompletedCandle last,Assumptions a,Instant asOf) {
        if(pre==null||post==null
                ||!basicObservation(pre,a)||!basicObservation(post,a)
                ||!last.provider().equals(pre.provider())||!pre.provider().equals(post.provider())
                ||!FnoLongOptionCrossoverV1.UNDERLYING.equals(pre.underlyingKey())
                ||!pre.underlyingKey().equals(post.underlyingKey()))return false;
        if(!pre.exchangeAt().isBefore(e.eventStart())
                ||pre.firstKnownAt().isAfter(e.eventStart())
                ||!e.scheduleFirstKnownAt().isBefore(pre.firstKnownAt())
                ||!last.firstKnownAt().isBefore(pre.exchangeAt())
                ||!post.exchangeAt().isAfter(e.eventEnd())
                ||post.firstKnownAt().isBefore(post.exchangeAt())
                ||post.firstKnownAt().isAfter(asOf)
                ||Duration.between(pre.exchangeAt(),e.eventStart())
                    .compareTo(Duration.ofHours(a.maximumPreEventAgeHours()))>0
                ||Duration.between(post.exchangeAt(),asOf)
                    .compareTo(Duration.ofSeconds(a.maxPostQuoteAgeSeconds()))>0)return false;
        return true;
    }
    private static boolean basicObservation(Observation q,Assumptions a) {
        if(!q.independentlySourceVerified()||!provider(q.provider())
                ||!positive(q.indexSpot())||!ivValid(q.atmIvPercent(),a.maximumIvPercent())
                ||q.constantTenorDays()!=TENOR_DAYS ||!IV_METHOD.equals(q.ivMethod())
                ||q.exchangeAt()==null||q.firstKnownAt()==null
                ||q.exchangeAt().isAfter(q.firstKnownAt()))return false;
        var z=q.exchangeAt().atZone(IST);
        return z.getDayOfWeek().getValue()<=5
            &&!z.toLocalTime().isBefore(MARKET_OPEN)&&!z.toLocalTime().isAfter(CLOSE);
    }
    private static boolean assumptionsValid(Assumptions a) {
        return a!=null&&between(a.minimumCompletedSessions(),35,300)
            &&between(a.maxHistoryCalendarDays(),45,550)
            &&between(a.maximumPreEventAgeHours(),1,72)
            &&between(a.maxPostQuoteAgeSeconds(),30,600)
            &&between(a.maximumIvPercent(),30,500)
            &&positive(a.minDropIvPoints())&&a.minDropIvPoints().compareTo(BigDecimal.valueOf(50))<=0
            &&between(a.minRelativeDropBps(),100,9500)
            &&between(a.maxAbsoluteIndexMoveBps(),50,5000);
    }
    private static boolean eventKey(String key) {
        return key!=null&&key.matches("[A-Za-z0-9._:-]{3,80}");
    }
    private static boolean eventCategory(String cat) {
        return "RBI_POLICY".equals(cat)||"MACRO_RELEASE".equals(cat)
            ||"OFFICIAL_INDEX_EVENT".equals(cat);
    }
    private static boolean calendarProvider(String source) {
        return "RBI_OFFICIAL_CALENDAR".equals(source)
            ||"NSE_OFFICIAL_CALENDAR".equals(source)
            ||"GOVERNMENT_RELEASE_CALENDAR".equals(source);
    }
    private static boolean provider(String s) {
        return "UPSTOX_ANALYTICS".equals(s)||"ANGEL_ONE_SMARTAPI".equals(s);
    }
    private static boolean ivValid(BigDecimal v,int max) {
        return positive(v)&&v.compareTo(BigDecimal.valueOf(max))<=0;
    }
    private static boolean positive(BigDecimal v){return v!=null&&v.signum()>0;}
    private static boolean between(int v,int low,int high){return v>=low&&v<=high;}
    private static BigDecimal round(BigDecimal v) {
        return v==null?null:v.setScale(4,RoundingMode.HALF_UP);
    }
    private static Decision observed(Status status,String reason,Event e,
                                     Observation pre,Observation post,Instant asOf,
                                     BigDecimal points,BigDecimal relative,
                                     BigDecimal indexMove) {
        return new Decision(status,reason,STRATEGY_ID,e.eventKey(),asOf,
            pre.exchangeAt(),post.exchangeAt(),round(pre.atmIvPercent()),
            round(post.atmIvPercent()),round(points),round(relative),
            round(indexMove),true);
    }
    private static Decision blocked(Status status,String reason,Instant asOf) {
        return new Decision(status,reason,STRATEGY_ID,null,asOf,null,null,
            null,null,null,null,null,true);
    }
}
