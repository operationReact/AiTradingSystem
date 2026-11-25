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
 * STR-199: research-only long calendar spread, SELL one near-expiry CE or PE
 * and BUY one same-strike/type/lot FAR-expiry option on the same NIFTY index.
 * The two expiries prevent a single common-expiry intrinsic payoff analysis.
 * No orders, PAPER activation or LIVE permissions are produced.
 */
public final class FnoCalendarSpreadV1 {
    public static final String STRATEGY_ID = "strategy.STR-199";
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal BPS = new BigDecimal("10000");
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime NSE_CLOSE = LocalTime.of(15, 30);
    private static final Duration MAX_QUOTE_AGE = Duration.ofSeconds(180);
    private static final Duration MAX_SKEW = Duration.ofSeconds(90);
    private FnoCalendarSpreadV1() {}

    public enum Status { BLOCKED_RIGHTS, BLOCKED_DATA, NO_SIGNAL, RESEARCH_CANDIDATE }

    public record Contract(String key, String underlying, String optionType,
                           BigDecimal strike, BigDecimal tick,
                           LocalDate listedFrom, LocalDate expiry,
                           int lotSize, boolean sourceVerified) {}
    public record CompletedClose(LocalDate session, String provider, BigDecimal close,
                                 Instant exchangeCloseAt, Instant firstKnownAt,
                                 boolean sourceVerified) {}
    public record Quote(String provider, String key, BigDecimal bid, BigDecimal ask,
                        BigDecimal impliedVolatilityPercent,
                        long bidDepthLots, long askDepthLots, long openInterestLots,
                        Instant exchangeAt, Instant firstKnownAt) {}
    public record Rights(boolean indexResearch, boolean optionChainResearch,
                         boolean instrumentDiscoveryResearch, boolean impliedVolatilityResearch) {}
    /** IV values are annualized percentages, e.g., 35 means 35%. */
    public record Assumptions(int buySlippageBps, int sellSlippageBps,
                              int maxSpreadBps, long minDepthLots, long minOiLots,
                              int maxAtmDistanceBps, int maxIvPercent,
                              BigDecimal twoLegFeesInr, BigDecimal researchCapitalInr,
                              int maxNetDebitAllocationBps, int minNearToFarIvRatioBps,
                              int minNearPremiumToFarPremiumBps,
                              int maxNearOneSigmaMoveBpsOfSpot) {}
    public record Decision(Status status, String reasonCode, String strategyId,
                           String shortNearKey, String longFarKey, Instant asOf,
                           LocalDate nearExpiry, LocalDate farExpiry,
                           BigDecimal netDebitWithFeesInr,
                           BigDecimal nearIvToFarIvRatioBps,
                           BigDecimal shortPremiumToLongPremiumRatioBps,
                           BigDecimal illustrativeNearOneSigmaMove,
                           boolean nearExpiryResidualLongMarkUnknown) {
        public boolean executable() { return false; }
        public boolean orderPlaced() { return false; }
        public boolean liveTradeAuthorized() { return false; }
    }

    public static Decision evaluate(Contract shortNear, Contract longFar,
                                    List<CompletedClose> completedIndexCloses,
                                    Quote index, Quote shortNearQuote, Quote longFarQuote,
                                    Rights rights, Assumptions a, Instant asOf) {
        Objects.requireNonNull(asOf, "asOf");
        if (rights==null || !rights.indexResearch() || !rights.optionChainResearch()
                || !rights.instrumentDiscoveryResearch() || !rights.impliedVolatilityResearch())
            return blocked(Status.BLOCKED_RIGHTS, "CALENDAR_SPREAD_RIGHTS_UNVERIFIED", asOf);
        if (!contractsValid(shortNear,longFar,asOf) || !assumptionsValid(a))
            return blocked(Status.BLOCKED_DATA, "CALENDAR_SPREAD_CONTRACT_OR_FEES_INVALID", asOf);
        if (!historyValid(completedIndexCloses,asOf))
            return blocked(Status.BLOCKED_DATA, "CALENDAR_SPREAD_HISTORY_INVALID", asOf);
        if (!quotesValid(shortNear,longFar,completedIndexCloses.getLast(),
                index,shortNearQuote,longFarQuote,a,asOf))
            return blocked(Status.BLOCKED_DATA, "CALENDAR_SPREAD_QUOTES_OR_IV_INVALID", asOf);

        BigDecimal lot=BigDecimal.valueOf(shortNear.lotSize());
        BigDecimal sellFactor=BPS.subtract(BigDecimal.valueOf(a.sellSlippageBps()),MC).divide(BPS,MC);
        BigDecimal buyFactor=BPS.add(BigDecimal.valueOf(a.buySlippageBps()),MC).divide(BPS,MC);
        // Short near expiration is SOLD at available BID; longer option is BOUGHT at ASK.
        BigDecimal shortCredit=shortNearQuote.bid().multiply(sellFactor,MC);
        BigDecimal longDebit=longFarQuote.ask().multiply(buyFactor,MC);
        BigDecimal netDebit=longDebit.subtract(shortCredit,MC).multiply(lot,MC)
            .add(a.twoLegFeesInr(),MC);
        if (netDebit.signum()<=0)
            return measured(Status.NO_SIGNAL,"CALENDAR_SPREAD_NONPOSITIVE_NET_DEBIT",
                shortNear,longFar,asOf,netDebit,null,null,null);

        BigDecimal budget=a.researchCapitalInr()
            .multiply(BigDecimal.valueOf(a.maxNetDebitAllocationBps()),MC).divide(BPS,MC);
        if (netDebit.compareTo(budget)>0)
            return measured(Status.NO_SIGNAL,"CALENDAR_SPREAD_NET_DEBIT_BUDGET_EXCEEDED",
                shortNear,longFar,asOf,netDebit,null,null,null);
        // IV term structure is only descriptive. High near-term relative IV does
        // NOT imply a profitable calendar (earnings, skew, theta and gap risks).
        BigDecimal ivRatio=shortNearQuote.impliedVolatilityPercent()
            .multiply(BPS,MC).divide(longFarQuote.impliedVolatilityPercent(),MC);
        BigDecimal premiumRatio=shortCredit.multiply(BPS,MC).divide(longDebit,MC);
        if (ivRatio.compareTo(BigDecimal.valueOf(a.minNearToFarIvRatioBps()))<0)
            return measured(Status.NO_SIGNAL,"CALENDAR_SPREAD_IV_TERM_STRUCTURE_INADEQUATE",
                shortNear,longFar,asOf,netDebit,ivRatio,premiumRatio,null);
        if (premiumRatio.compareTo(BigDecimal.valueOf(a.minNearPremiumToFarPremiumBps()))<0)
            return measured(Status.NO_SIGNAL,"CALENDAR_SPREAD_SHORT_PREMIUM_COVERAGE_INADEQUATE",
                shortNear,longFar,asOf,netDebit,ivRatio,premiumRatio,null);

        long nearDte=ChronoUnit.DAYS.between(asOf.atZone(IST).toLocalDate(),shortNear.expiry());
        BigDecimal sigmaMove=index.bid()
            .multiply(shortNearQuote.impliedVolatilityPercent(),MC)
            .divide(BigDecimal.valueOf(100),MC)
            .multiply(BigDecimal.valueOf(StrictMath.sqrt(nearDte/365.0)),MC);
        BigDecimal nearSigmaBps=sigmaMove.multiply(BPS,MC).divide(index.bid(),MC);
        if (nearSigmaBps.compareTo(BigDecimal.valueOf(a.maxNearOneSigmaMoveBpsOfSpot()))>0)
            return measured(Status.NO_SIGNAL,"CALENDAR_SPREAD_NEAR_IV_MOVE_EXCEEDS_HURDLE",
                shortNear,longFar,asOf,netDebit,ivRatio,premiumRatio,sigmaMove);
        // No common expiry exists; long far-dated option must be marked when the
        // near leg settles. Neither expiry breakeven nor max realized loss can be
        // inferred from one intrinsic diagram. This is research, NOT margin logic.
        return measured(Status.RESEARCH_CANDIDATE,"CALENDAR_SPREAD_MARK_TO_MARKET_UNRESOLVED_RESEARCH_ONLY",
            shortNear,longFar,asOf,netDebit,ivRatio,premiumRatio,sigmaMove);
    }

    private static boolean contractsValid(Contract near,Contract far,Instant at) {
        if(near==null||far==null||!near.sourceVerified()||!far.sourceVerified()
                ||!key(near.key())||!key(far.key())||near.key().equals(far.key())
                ||!FnoLongOptionCrossoverV1.UNDERLYING.equals(near.underlying())
                ||!near.underlying().equals(far.underlying())
                ||!("CE".equals(near.optionType())||"PE".equals(near.optionType()))
                ||!near.optionType().equals(far.optionType())
                ||!positive(near.strike())||near.strike().compareTo(far.strike())!=0
                ||!positive(near.tick())||!positive(far.tick())
                ||near.strike().remainder(near.tick()).signum()!=0
                ||far.strike().remainder(far.tick()).signum()!=0
                ||near.lotSize()<1||near.lotSize()!=far.lotSize()
                ||near.expiry()==null||far.expiry()==null
                ||near.listedFrom()==null||far.listedFrom()==null
                ||near.listedFrom().isAfter(at.atZone(IST).toLocalDate())
                ||far.listedFrom().isAfter(at.atZone(IST).toLocalDate()))return false;
        LocalDate today=at.atZone(IST).toLocalDate();
        long nearDte=ChronoUnit.DAYS.between(today,near.expiry());
        long farDte=ChronoUnit.DAYS.between(today,far.expiry());
        long gap=ChronoUnit.DAYS.between(near.expiry(),far.expiry());
        return nearDte>=3 && nearDte<=35 && farDte>=10 && farDte<=90
            && gap>=7 && gap<=60;
    }

    private static boolean historyValid(List<CompletedClose> bars,Instant at) {
        if(bars==null||bars.size()<35||bars.size()>512)return false;
        LocalDate prior=null;String source=null;
        for(var b:bars){
            if(b==null||!b.sourceVerified()||b.session()==null
                    ||b.session().getDayOfWeek().getValue()>5
                    ||!provider(b.provider())||!positive(b.close())
                    ||b.exchangeCloseAt()==null||b.firstKnownAt()==null)return false;
            Instant close=b.session().atTime(NSE_CLOSE).atZone(IST).toInstant();
            if(!close.equals(b.exchangeCloseAt())
                    ||b.firstKnownAt().isBefore(close)
                    ||b.firstKnownAt().isAfter(close.plus(Duration.ofHours(36)))
                    ||b.firstKnownAt().isAfter(at)
                    ||source!=null&&!source.equals(b.provider()))return false;
            if(prior!=null){
                long gap=ChronoUnit.DAYS.between(prior,b.session());
                if(gap<1||gap>5)return false;
            }
            prior=b.session();source=b.provider();
        }
        long days=ChronoUnit.DAYS.between(prior,at.atZone(IST).toLocalDate());
        return days>=0&&days<=4;
    }

    private static boolean quotesValid(Contract near,Contract far,CompletedClose last,
                                       Quote spot,Quote nq,Quote fq,Assumptions a,Instant at) {
        if(spot==null||nq==null||fq==null
                ||!near.underlying().equals(spot.key())
                ||!near.key().equals(nq.key())||!far.key().equals(fq.key()))return false;
        var qs=List.of(spot,nq,fq);Instant min=null,max=null;
        for(int i=0;i<qs.size();i++){
            var q=qs.get(i);
            long depth=i==0?1:a.minDepthLots();
            long oi=i==0?1:a.minOiLots();
            int maxSpread=i==0?Integer.MAX_VALUE:a.maxSpreadBps();
            if(!last.provider().equals(q.provider())
                    ||!validQuote(q,depth,oi,maxSpread,i==1)
                    ||!validTime(q.exchangeAt(),q.firstKnownAt(),at)
                    ||last.firstKnownAt().isAfter(q.exchangeAt()))return false;
            if(i>0&&!ivValid(q.impliedVolatilityPercent(),a.maxIvPercent()))return false;
            Instant t=q.exchangeAt();
            if(min==null||t.isBefore(min))min=t;
            if(max==null||t.isAfter(max))max=t;
        }
        if(Duration.between(min,max).compareTo(MAX_SKEW)>0)return false;
        BigDecimal distance=spot.bid().subtract(near.strike(),MC).abs()
            .multiply(BPS,MC).divide(spot.bid(),MC);
        return distance.compareTo(BigDecimal.valueOf(a.maxAtmDistanceBps()))<=0;
    }
    private static boolean validQuote(Quote q,long depth,long oi,int maxSpread,boolean selling){
        if(!positive(q.bid())||!positive(q.ask())||q.ask().compareTo(q.bid())<0
                ||q.openInterestLots()<oi
                ||(selling?q.bidDepthLots():q.askDepthLots())<depth)return false;
        BigDecimal mid=q.bid().add(q.ask(),MC).divide(BigDecimal.valueOf(2),MC);
        return q.ask().subtract(q.bid(),MC).multiply(BPS,MC).divide(mid,MC)
            .compareTo(BigDecimal.valueOf(maxSpread))<=0;
    }
    private static boolean validTime(Instant exchange,Instant first,Instant at){
        if(exchange==null||first==null||exchange.isAfter(first)||first.isAfter(at)
                ||exchange.isAfter(at)
                ||Duration.between(exchange,at).compareTo(MAX_QUOTE_AGE)>0)return false;
        var z=exchange.atZone(IST);
        return z.getDayOfWeek().getValue()<=5
            &&!z.toLocalTime().isBefore(LocalTime.of(9,15))
            &&!z.toLocalTime().isAfter(NSE_CLOSE);
    }
    private static boolean assumptionsValid(Assumptions a){
        return a!=null&&between(a.buySlippageBps(),0,1000)
            &&between(a.sellSlippageBps(),0,1000)
            &&between(a.maxSpreadBps(),1,3000)
            &&a.minDepthLots()>=1&&a.minOiLots()>=1
            &&between(a.maxAtmDistanceBps(),1,2000)
            &&between(a.maxIvPercent(),5,500)
            &&positive(a.twoLegFeesInr())&&positive(a.researchCapitalInr())
            &&between(a.maxNetDebitAllocationBps(),100,10000)
            &&between(a.minNearToFarIvRatioBps(),100,30000)
            &&between(a.minNearPremiumToFarPremiumBps(),100,10000)
            &&between(a.maxNearOneSigmaMoveBpsOfSpot(),100,20000);
    }
    private static boolean ivValid(BigDecimal v,int max){return positive(v)&&v.compareTo(BigDecimal.valueOf(max))<=0;}
    private static boolean provider(String s){
        return "UPSTOX_ANALYTICS".equals(s)||"ANGEL_ONE_SMARTAPI".equals(s);
    }
    private static boolean key(String k){
        return k!=null&&k.matches("NSE_FO\\|[A-Za-z0-9._-]{1,64}");
    }
    private static boolean positive(BigDecimal v){return v!=null&&v.signum()>0;}
    private static boolean between(int v,int lo,int hi){return v>=lo&&v<=hi;}
    private static BigDecimal rounded(BigDecimal v){
        return v==null?null:v.setScale(4,RoundingMode.HALF_UP);
    }
    private static Decision measured(Status status,String reason,Contract near,Contract far,
                                     Instant at,BigDecimal debit,BigDecimal ivRatio,
                                     BigDecimal premiumRatio,BigDecimal sigma){
        return new Decision(status,reason,STRATEGY_ID,near.key(),far.key(),at,
            near.expiry(),far.expiry(),rounded(debit),rounded(ivRatio),
            rounded(premiumRatio),rounded(sigma),true);
    }
    private static Decision blocked(Status status,String reason,Instant at){
        return new Decision(status,reason,STRATEGY_ID,null,null,at,null,null,
            null,null,null,null,true);
    }
}
