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
 * STR-201: research-only 30-day constant-tenor NIFTY ATM IV-rank mean-reversion
 * classifier. No order, naked short, broker entitlement or historical success
 * is asserted. Real source IV data and original point-in-time history required.
 */
public final class FnoIvRankMeanReversionV1 {
    public static final String STRATEGY_ID = "strategy.STR-201";
    public static final int STANDARD_TENOR_DAYS = 30;
    public static final String SOURCE_IV_METHOD = "LICENSED_ATM_30D_INTERPOLATION";
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime NSE_CLOSE = LocalTime.of(15,30);

    private FnoIvRankMeanReversionV1() {}

    public enum Status { BLOCKED_RIGHTS, BLOCKED_DATA, NO_SIGNAL, RESEARCH_CANDIDATE }
    public enum ResearchRegime { NONE, HIGH_IV_PULLBACK, LOW_IV_REBOUND }

    public record Rights(boolean indexHistoryResearch, boolean optionChainResearch,
                         boolean licensedIvHistoryResearch,
                         boolean constantTenorNormalizationResearch) {}
    /** All input volatilities are annualized percentage *points*: 40 == 40%. */
    public record DailyIv(LocalDate session, String provider, BigDecimal atmIvPercent,
                          int constantTenorDays, String sourceMethod, Instant firstKnownAt,
                          boolean independentlySourceVerified) {}
    public record CompletedCandle(LocalDate session, String provider,
                                  BigDecimal open, BigDecimal high, BigDecimal low,
                                  BigDecimal close, long volume,
                                  Instant exchangeCloseAt, Instant firstKnownAt,
                                  boolean independentlySourceVerified) {}
    public record CurrentIv(String provider, String underlyingKey, BigDecimal atmIvPercent,
                            int constantTenorDays, String sourceMethod,
                            Instant exchangeAt, Instant firstKnownAt,
                            boolean independentlySourceVerified) {}
    public record Assumptions(int minHistorySessions, int maxHistoryCalendarDays,
                              int maxCurrentQuoteAgeSeconds, int maxIvPercent,
                              BigDecimal minHistoricalIvRangePoints,
                              int highIvRankPercent, int lowIvRankPercent,
                              int recentLookbackSessions,
                              BigDecimal minReversalIvPoints) {}
    public record Decision(Status status, String reasonCode, String strategyId,
                           ResearchRegime regime, Instant asOf, int historySessions,
                           BigDecimal current30DayAtmIvPercent,
                           BigDecimal historicalMinIvPercent,
                           BigDecimal historicalMaxIvPercent,
                           BigDecimal ivRankPercent,
                           BigDecimal fiveDayExtremumIvPercent,
                           BigDecimal reversalIvPoints) {
        public boolean executable() { return false; }
        public boolean orderPlaced() { return false; }
        public boolean liveTradeAuthorized() { return false; }
    }

    public static Decision evaluate(List<CompletedCandle> completedIndexCandles,
                                    List<DailyIv> originalHistorical30DayAtmIv,
                                    CurrentIv current, Rights rights,
                                    Assumptions assumptions, Instant asOf) {
        Objects.requireNonNull(asOf,"asOf");
        if (rights==null || !rights.indexHistoryResearch()
                || !rights.optionChainResearch()
                || !rights.licensedIvHistoryResearch()
                || !rights.constantTenorNormalizationResearch())
            return blocked(Status.BLOCKED_RIGHTS,"IV_RANK_SOURCE_RIGHTS_UNVERIFIED",asOf);
        if (!assumptionsValid(assumptions))
            return blocked(Status.BLOCKED_DATA,"IV_RANK_ASSUMPTIONS_INVALID",asOf);
        if (!historyValid(completedIndexCandles,originalHistorical30DayAtmIv,
                assumptions,asOf))
            return blocked(Status.BLOCKED_DATA,"IV_RANK_ORIGINAL_HISTORY_INVALID",asOf);
        if (!currentValid(current,originalHistorical30DayAtmIv.getLast(),assumptions,asOf))
            return blocked(Status.BLOCKED_DATA,"IV_RANK_CURRENT_SOURCE_IV_INVALID",asOf);

        BigDecimal min=null,max=null;
        for(var mark:originalHistorical30DayAtmIv) {
            var v=mark.atmIvPercent();
            if(min==null||v.compareTo(min)<0)min=v;
            if(max==null||v.compareTo(max)>0)max=v;
        }
        BigDecimal range=max.subtract(min,MC);
        if (range.compareTo(assumptions.minHistoricalIvRangePoints())<0)
            return measured(Status.NO_SIGNAL,"IV_RANK_HISTORICAL_RANGE_TOO_NARROW",
                ResearchRegime.NONE,asOf,originalHistorical30DayAtmIv.size(),
                current.atmIvPercent(),min,max,null,null,null);

        // Standard 52-week IV rank = 100 * (current IV - observed prior low)
        // / (observed prior high - prior low), clamped to [0,100].
        // No historical observation after asOf is admitted.
        BigDecimal rank=current.atmIvPercent().subtract(min,MC)
            .multiply(HUNDRED,MC).divide(range,MC)
            .max(BigDecimal.ZERO).min(HUNDRED);
        int n=originalHistorical30DayAtmIv.size();
        var recent=originalHistorical30DayAtmIv.subList(n-assumptions.recentLookbackSessions(),n);
        BigDecimal recentHigh=null,recentLow=null;
        for(var mark:recent) {
            var v=mark.atmIvPercent();
            if(recentHigh==null||v.compareTo(recentHigh)>0)recentHigh=v;
            if(recentLow==null||v.compareTo(recentLow)<0)recentLow=v;
        }
        // A high rank alone is not a "mean reversion" event: require a
        // *measurable actual pullback* from recent historical IV highs.
        BigDecimal down=recentHigh.subtract(current.atmIvPercent(),MC);
        if (rank.compareTo(BigDecimal.valueOf(assumptions.highIvRankPercent()))>=0
                && down.compareTo(assumptions.minReversalIvPoints())>=0)
            return measured(Status.RESEARCH_CANDIDATE,"IV_RANK_HIGH_IV_PULLBACK_RESEARCH_ONLY",
                ResearchRegime.HIGH_IV_PULLBACK,asOf,n,current.atmIvPercent(),
                min,max,rank,recentHigh,down);
        // Low rank alone is not a reversion event either: check observed
        // positive rebound above a genuine recent trailing historical low.
        BigDecimal up=current.atmIvPercent().subtract(recentLow,MC);
        if (rank.compareTo(BigDecimal.valueOf(assumptions.lowIvRankPercent()))<=0
                && up.compareTo(assumptions.minReversalIvPoints())>=0)
            return measured(Status.RESEARCH_CANDIDATE,"IV_RANK_LOW_IV_REBOUND_RESEARCH_ONLY",
                ResearchRegime.LOW_IV_REBOUND,asOf,n,current.atmIvPercent(),
                min,max,rank,recentLow,up);
        return measured(Status.NO_SIGNAL,"IV_RANK_NO_VERIFIED_REVERSAL",
            ResearchRegime.NONE,asOf,n,current.atmIvPercent(),min,max,rank,null,null);
    }

    private static boolean historyValid(List<CompletedCandle> candles,List<DailyIv> marks,
                                        Assumptions a,Instant asOf) {
        if(candles==null||marks==null||candles.size()!=marks.size()
                ||candles.size()<a.minHistorySessions()||candles.size()>370)return false;
        LocalDate prior=null;
        String source=null;
        LocalDate asOfSession=asOf.atZone(IST).toLocalDate();
        for(int i=0;i<candles.size();i++){
            var c=candles.get(i);
            var m=marks.get(i);
            if(c==null||m==null||!c.independentlySourceVerified()
                    ||!m.independentlySourceVerified()
                    ||c.session()==null||m.session()==null||!c.session().equals(m.session())
                    ||c.session().getDayOfWeek().getValue()>5
                    ||!provider(c.provider())||!c.provider().equals(m.provider())
                    ||source!=null&&!source.equals(c.provider())
                    ||!positive(c.open())||!positive(c.high())||!positive(c.low())
                    ||!positive(c.close())||c.volume()<0
                    ||c.high().compareTo(c.open())<0||c.high().compareTo(c.close())<0
                    ||c.low().compareTo(c.open())>0||c.low().compareTo(c.close())>0
                    ||!ivValid(m.atmIvPercent(),a.maxIvPercent())
                    ||m.constantTenorDays()!=STANDARD_TENOR_DAYS
                    ||!SOURCE_IV_METHOD.equals(m.sourceMethod())
                    ||c.exchangeCloseAt()==null||c.firstKnownAt()==null
                    ||m.firstKnownAt()==null)return false;
            Instant close=c.session().atTime(NSE_CLOSE).atZone(IST).toInstant();
            if(!close.equals(c.exchangeCloseAt())
                    ||c.firstKnownAt().isBefore(close)
                    ||m.firstKnownAt().isBefore(close)
                    ||c.firstKnownAt().isAfter(close.plus(Duration.ofHours(36)))
                    ||m.firstKnownAt().isAfter(close.plus(Duration.ofHours(36)))
                    ||c.firstKnownAt().isAfter(asOf)||m.firstKnownAt().isAfter(asOf))return false;
            if(prior!=null) {
                long gap=ChronoUnit.DAYS.between(prior,c.session());
                if(gap<1||gap>5)return false;
            }
            prior=c.session();source=c.provider();
        }
        long calendarSpan=ChronoUnit.DAYS.between(candles.getFirst().session(),prior);
        long lag=ChronoUnit.DAYS.between(prior,asOfSession);
        return calendarSpan<=a.maxHistoryCalendarDays() && lag>=0 && lag<=4;
    }
    private static boolean currentValid(CurrentIv current,DailyIv last,Assumptions a,Instant asOf) {
        if(current==null||!current.independentlySourceVerified()
                ||!last.provider().equals(current.provider())
                ||!FnoLongOptionCrossoverV1.UNDERLYING.equals(current.underlyingKey())
                ||!ivValid(current.atmIvPercent(),a.maxIvPercent())
                ||current.constantTenorDays()!=STANDARD_TENOR_DAYS
                ||!SOURCE_IV_METHOD.equals(current.sourceMethod())
                ||current.exchangeAt()==null||current.firstKnownAt()==null
                ||current.exchangeAt().isAfter(current.firstKnownAt())
                ||current.firstKnownAt().isAfter(asOf)
                ||current.exchangeAt().isAfter(asOf)
                ||last.firstKnownAt().isAfter(current.exchangeAt())
                ||Duration.between(current.exchangeAt(),asOf)
                  .compareTo(Duration.ofSeconds(a.maxCurrentQuoteAgeSeconds()))>0)return false;
        var z=current.exchangeAt().atZone(IST);
        return z.getDayOfWeek().getValue()<=5
            &&!z.toLocalTime().isBefore(LocalTime.of(9,15))
            &&!z.toLocalTime().isAfter(NSE_CLOSE);
    }
    private static boolean assumptionsValid(Assumptions a) {
        return a!=null
            &&between(a.minHistorySessions(),200,300)
            &&between(a.maxHistoryCalendarDays(),365,550)
            &&between(a.maxCurrentQuoteAgeSeconds(),30,600)
            &&between(a.maxIvPercent(),50,500)
            &&positive(a.minHistoricalIvRangePoints())
            &&a.minHistoricalIvRangePoints().compareTo(BigDecimal.valueOf(60))<=0
            &&between(a.lowIvRankPercent(),5,40)
            &&between(a.highIvRankPercent(),60,95)
            &&a.lowIvRankPercent()<a.highIvRankPercent()
            &&between(a.recentLookbackSessions(),3,20)
            &&positive(a.minReversalIvPoints())
            &&a.minReversalIvPoints().compareTo(BigDecimal.valueOf(15))<=0;
    }
    private static boolean provider(String s) {
        return "UPSTOX_ANALYTICS".equals(s)||"ANGEL_ONE_SMARTAPI".equals(s);
    }
    private static boolean ivValid(BigDecimal iv,int max) {
        return positive(iv)&&iv.compareTo(BigDecimal.valueOf(max))<=0;
    }
    private static boolean positive(BigDecimal v){return v!=null&&v.signum()>0;}
    private static boolean between(int n,int min,int max){return n>=min&&n<=max;}
    private static BigDecimal rounded(BigDecimal v) {
        return v==null?null:v.setScale(4,RoundingMode.HALF_UP);
    }
    private static Decision measured(Status status,String reason,ResearchRegime regime,
                                     Instant asOf,int n,BigDecimal current,BigDecimal min,
                                     BigDecimal max,BigDecimal rank,BigDecimal recent,
                                     BigDecimal reversal) {
        return new Decision(status,reason,STRATEGY_ID,regime,asOf,n,rounded(current),
            rounded(min),rounded(max),rounded(rank),rounded(recent),rounded(reversal));
    }
    private static Decision blocked(Status status,String reason,Instant asOf) {
        return measured(status,reason,ResearchRegime.NONE,asOf,0,null,null,null,null,null,null);
    }
}
