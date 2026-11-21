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
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * STR-196: four-leg defined-risk iron condor, research-only.
 * Buy low PE, sell higher PE, sell lower CE, buy high CE.
 * Actual executable margin and non-atomic fills are NOT defined by this research payoff.
 */
public final class FnoIronCondorV1 {
    public static final String STRATEGY_ID = "strategy.STR-196";
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal BPS = new BigDecimal("10000");
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime NSE_CLOSE = LocalTime.of(15, 30);
    private static final Duration MAX_QUOTE_AGE = Duration.ofSeconds(180);
    private static final Duration MAX_SKEW = Duration.ofSeconds(90);
    private FnoIronCondorV1() {}

    public enum Status { BLOCKED_RIGHTS, BLOCKED_DATA, NO_SIGNAL, RESEARCH_CANDIDATE }
    public record Contract(String key, String underlying, String optionType, BigDecimal strike,
                           BigDecimal tick, LocalDate listedFrom, LocalDate expiry,
                           int lotSize, boolean sourceVerified) {}
    public record CompletedClose(LocalDate session, String provider, BigDecimal close,
                                 Instant exchangeCloseAt, Instant firstKnownAt,
                                 boolean verified) {}
    public record Quote(String provider, String key, BigDecimal bid, BigDecimal ask,
                        BigDecimal impliedVolatilityPercent, long bidDepthLots,
                        long askDepthLots, long openInterestLots,
                        Instant exchangeAt, Instant firstKnownAt) {}
    public record Rights(boolean indexResearch, boolean optionChainResearch,
                         boolean instrumentsResearch, boolean impliedVolatilityResearch) {}
    public record Assumptions(int buySlippageBps, int sellSlippageBps, int maxSpreadBps,
                              long minDepthLots, long minOpenInterestLots,
                              int maxWingDistanceBps, int maxIvPercent,
                              BigDecimal fourLegFeesInr, BigDecimal researchCapitalInr,
                              int maxWorstExpiryLossAllocationBps,
                              int minCreditToWorstLossBps,
                              int maxOneSigmaMoveToNearestShortStrikeBps) {}
    public record Decision(Status status, String reasonCode, String strategyId,
                           String longPutKey, String shortPutKey,
                           String shortCallKey, String longCallKey,
                           Instant asOf, BigDecimal netCreditInr,
                           BigDecimal theoreticalMaximumExpiryProfitInr,
                           BigDecimal theoreticalMaximumExpiryLossInr,
                           BigDecimal lowerBreakeven, BigDecimal upperBreakeven,
                           BigDecimal illustrativeOneSigmaMove,
                           boolean expiryPayoffDefinedRisk) {
        public boolean executable() { return false; }
        public boolean orderPlaced() { return false; }
        public boolean liveTradeAuthorized() { return false; }
    }

    public static Decision evaluate(Contract longPut, Contract shortPut,
                                    Contract shortCall, Contract longCall,
                                    List<CompletedClose> completedIndexBars,
                                    Quote spot, Quote longPutQuote, Quote shortPutQuote,
                                    Quote shortCallQuote, Quote longCallQuote,
                                    Rights rights, Assumptions a, Instant asOf) {
        Objects.requireNonNull(asOf, "asOf");
        if (rights==null || !rights.indexResearch() || !rights.optionChainResearch()
                || !rights.instrumentsResearch() || !rights.impliedVolatilityResearch())
            return blocked(Status.BLOCKED_RIGHTS, "IRON_CONDOR_RIGHTS_MISSING", asOf);
        if (!contractsValid(longPut,shortPut,shortCall,longCall,asOf) || !assumptionsValid(a))
            return blocked(Status.BLOCKED_DATA, "IRON_CONDOR_CONTRACT_OR_COST_INVALID", asOf);
        if (!historyValid(completedIndexBars,asOf))
            return blocked(Status.BLOCKED_DATA, "IRON_CONDOR_COMPLETED_HISTORY_INVALID", asOf);
        if (!quotesValid(longPut,shortPut,shortCall,longCall,completedIndexBars.getLast(),
                spot,longPutQuote,shortPutQuote,shortCallQuote,longCallQuote,a,asOf))
            return blocked(Status.BLOCKED_DATA, "IRON_CONDOR_QUOTE_OR_IV_INVALID", asOf);

        BigDecimal lot=BigDecimal.valueOf(longPut.lotSize());
        BigDecimal sellMultiplier=BPS.subtract(BigDecimal.valueOf(a.sellSlippageBps()),MC)
            .divide(BPS,MC);
        BigDecimal buyMultiplier=BPS.add(BigDecimal.valueOf(a.buySlippageBps()),MC)
            .divide(BPS,MC);
        BigDecimal perUnitCredit=shortPutQuote.bid().add(shortCallQuote.bid(),MC)
            .multiply(sellMultiplier,MC)
            .subtract(longPutQuote.ask().add(longCallQuote.ask(),MC)
                .multiply(buyMultiplier,MC),MC);
        BigDecimal credit=perUnitCredit.multiply(lot,MC).subtract(a.fourLegFeesInr(),MC);
        if (credit.signum()<=0)
            return measured(Status.NO_SIGNAL,"IRON_CONDOR_NONPOSITIVE_NET_CREDIT",
                longPut,shortPut,shortCall,longCall,asOf,credit,null,null,null,null,null);

        BigDecimal putWidth=shortPut.strike().subtract(longPut.strike(),MC);
        BigDecimal callWidth=longCall.strike().subtract(shortCall.strike(),MC);
        // Compute *worst of either wing*, not just one or average width.
        BigDecimal worstWing=putWidth.max(callWidth).multiply(lot,MC);
        BigDecimal worstLoss=worstWing.subtract(credit,MC).max(BigDecimal.ZERO);
        BigDecimal lower=shortPut.strike().subtract(credit.divide(lot,MC),MC);
        BigDecimal upper=shortCall.strike().add(credit.divide(lot,MC),MC);
        if (worstLoss.signum()==0 || credit.compareTo(worstWing)>=0)
            return measured(Status.NO_SIGNAL,"IRON_CONDOR_CREDIT_EXCEEDS_WING_RISK_MODEL",
                longPut,shortPut,shortCall,longCall,asOf,credit,credit,worstLoss,lower,upper,null);

        BigDecimal cap=a.researchCapitalInr()
            .multiply(BigDecimal.valueOf(a.maxWorstExpiryLossAllocationBps()),MC)
            .divide(BPS,MC);
        if (worstLoss.compareTo(cap)>0)
            return measured(Status.NO_SIGNAL,"IRON_CONDOR_WORST_EXPIRY_LOSS_BUDGET_EXCEEDED",
                longPut,shortPut,shortCall,longCall,asOf,credit,credit,worstLoss,lower,upper,null);
        if (credit.multiply(BPS,MC).divide(worstLoss,MC)
                .compareTo(BigDecimal.valueOf(a.minCreditToWorstLossBps()))<0)
            return measured(Status.NO_SIGNAL,"IRON_CONDOR_REWARD_TO_RISK_INADEQUATE",
                longPut,shortPut,shortCall,longCall,asOf,credit,credit,worstLoss,lower,upper,null);

        long dte=ChronoUnit.DAYS.between(asOf.atZone(IST).toLocalDate(),longPut.expiry());
        BigDecimal iv=longPutQuote.impliedVolatilityPercent()
            .add(shortPutQuote.impliedVolatilityPercent(),MC)
            .add(shortCallQuote.impliedVolatilityPercent(),MC)
            .add(longCallQuote.impliedVolatilityPercent(),MC)
            .divide(BigDecimal.valueOf(4),MC);
        BigDecimal oneSigma=spot.bid().multiply(iv,MC)
            .divide(BigDecimal.valueOf(100),MC)
            .multiply(BigDecimal.valueOf(StrictMath.sqrt(dte/365.0)),MC);
        BigDecimal nearestInnerDistance=spot.bid().subtract(shortPut.strike(),MC)
            .min(shortCall.strike().subtract(spot.bid(),MC));
        // A one-sigma move is a heuristic, NOT a loss limit, tail forecast or backtest.
        if (oneSigma.multiply(BPS,MC).divide(nearestInnerDistance,MC)
                .compareTo(BigDecimal.valueOf(a.maxOneSigmaMoveToNearestShortStrikeBps()))>0)
            return measured(Status.NO_SIGNAL,"IRON_CONDOR_IV_MOVE_CROSSES_SHORT_WING_HURDLE",
                longPut,shortPut,shortCall,longCall,asOf,credit,credit,worstLoss,lower,upper,oneSigma);

        // The theoretical expiry payoff is finite with both protective wings
        // INTACT. Actual legging, partial fills, liquidity and liquidation
        // can invalidate the hedge. Never route orders from this class.
        return measured(Status.RESEARCH_CANDIDATE,"IRON_CONDOR_DEFINED_EXPIRY_RISK_RESEARCH_ONLY",
            longPut,shortPut,shortCall,longCall,asOf,credit,credit,worstLoss,lower,upper,oneSigma);
    }

    private static boolean contractsValid(Contract lp,Contract sp,Contract sc,Contract lc,
                                          Instant at) {
        if(lp==null||sp==null||sc==null||lc==null) return false;
        if(!"PE".equals(lp.optionType())||!"PE".equals(sp.optionType())
                ||!"CE".equals(sc.optionType())||!"CE".equals(lc.optionType()))return false;
        var cs=List.of(lp,sp,sc,lc);
        var keys=new HashSet<String>();
        Contract base=lp;
        for(var c:cs) {
            if(!c.sourceVerified() || !key(c.key()) || !keys.add(c.key())
                    || !FnoLongOptionCrossoverV1.UNDERLYING.equals(c.underlying())
                    || !base.underlying().equals(c.underlying())
                    || !positive(c.strike()) || !positive(c.tick())
                    || c.strike().remainder(c.tick()).signum()!=0
                    || c.lotSize()<1 || c.lotSize()!=base.lotSize()
                    || c.expiry()==null || !c.expiry().equals(base.expiry())
                    || c.listedFrom()==null
                    || c.listedFrom().isAfter(at.atZone(IST).toLocalDate()))return false;
        }
        long dte=ChronoUnit.DAYS.between(at.atZone(IST).toLocalDate(),lp.expiry());
        return dte>=3&&dte<=45
            && lp.strike().compareTo(sp.strike())<0
            && sp.strike().compareTo(sc.strike())<0
            && sc.strike().compareTo(lc.strike())<0;
    }
    private static boolean historyValid(List<CompletedClose> bars,Instant at) {
        if(bars==null||bars.size()<35||bars.size()>512)return false;
        LocalDate prior=null;String provider=null;
        for(var b:bars) {
            if(b==null||!b.verified()||b.session()==null
                    ||b.session().getDayOfWeek().getValue()>5
                    ||!provider(b.provider())||!positive(b.close())
                    ||b.exchangeCloseAt()==null||b.firstKnownAt()==null)return false;
            Instant close=b.session().atTime(NSE_CLOSE).atZone(IST).toInstant();
            if(!close.equals(b.exchangeCloseAt())
                    ||b.firstKnownAt().isBefore(close)
                    ||b.firstKnownAt().isAfter(close.plus(Duration.ofHours(36)))
                    ||b.firstKnownAt().isAfter(at)
                    ||provider!=null&&!provider.equals(b.provider()))return false;
            if(prior!=null) {
                long gap=ChronoUnit.DAYS.between(prior,b.session());
                if(gap<1||gap>5)return false;
            }
            prior=b.session();provider=b.provider();
        }
        long age=ChronoUnit.DAYS.between(prior,at.atZone(IST).toLocalDate());
        return age>=0&&age<=4;
    }
    private static boolean quotesValid(Contract lp,Contract sp,Contract sc,Contract lc,
                                       CompletedClose last,Quote spot,Quote lpq,Quote spq,
                                       Quote scq,Quote lcq,Assumptions a,Instant at) {
        if(spot==null||lpq==null||spq==null||scq==null||lcq==null)return false;
        var q=List.of(spot,lpq,spq,scq,lcq);
        if(!lp.underlying().equals(spot.key()) || !lp.key().equals(lpq.key())
                ||!sp.key().equals(spq.key())||!sc.key().equals(scq.key())
                ||!lc.key().equals(lcq.key()))return false;
        Instant min=null,max=null;
        for(int i=0;i<q.size();i++) {
            var v=q.get(i);
            long depth=i==0?1:a.minDepthLots();
            long oi=i==0?1:a.minOpenInterestLots();
            int spread=i==0?Integer.MAX_VALUE:a.maxSpreadBps();
            if(!last.provider().equals(v.provider())
                    ||!validQuote(v,depth,oi,spread,i==2||i==3)
                    ||!validTime(v.exchangeAt(),v.firstKnownAt(),at)
                    ||last.firstKnownAt().isAfter(v.exchangeAt()))return false;
            if(i>0&&!ivValid(v.impliedVolatilityPercent(),a.maxIvPercent()))return false;
            Instant t=v.exchangeAt();
            if(min==null||t.isBefore(min))min=t;
            if(max==null||t.isAfter(max))max=t;
        }
        if(Duration.between(min,max).compareTo(MAX_SKEW)>0)return false;
        BigDecimal index=spot.bid();
        if(index.compareTo(sp.strike())<=0||index.compareTo(sc.strike())>=0)return false;
        BigDecimal lowDistance=index.subtract(lp.strike(),MC).multiply(BPS,MC).divide(index,MC);
        BigDecimal highDistance=lc.strike().subtract(index,MC).multiply(BPS,MC).divide(index,MC);
        return lowDistance.signum()>0&&highDistance.signum()>0
            &&lowDistance.compareTo(BigDecimal.valueOf(a.maxWingDistanceBps()))<=0
            &&highDistance.compareTo(BigDecimal.valueOf(a.maxWingDistanceBps()))<=0;
    }
    private static boolean validQuote(Quote q,long depth,long oi,int maxSpread,boolean selling) {
        if(!positive(q.bid())||!positive(q.ask())||q.ask().compareTo(q.bid())<0
                ||q.openInterestLots()<oi
                ||(selling?q.bidDepthLots():q.askDepthLots())<depth)return false;
        BigDecimal mid=q.bid().add(q.ask(),MC).divide(BigDecimal.valueOf(2),MC);
        return q.ask().subtract(q.bid(),MC).multiply(BPS,MC).divide(mid,MC)
            .compareTo(BigDecimal.valueOf(maxSpread))<=0;
    }
    private static boolean validTime(Instant exchange,Instant first,Instant at) {
        if(exchange==null||first==null||exchange.isAfter(first)||first.isAfter(at)
                ||exchange.isAfter(at)
                ||Duration.between(exchange,at).compareTo(MAX_QUOTE_AGE)>0)return false;
        var z=exchange.atZone(IST);
        return z.getDayOfWeek().getValue()<=5
            &&!z.toLocalTime().isBefore(LocalTime.of(9,15))
            &&!z.toLocalTime().isAfter(NSE_CLOSE);
    }
    private static boolean assumptionsValid(Assumptions a) {
        return a!=null&&between(a.buySlippageBps(),0,1000)
            &&between(a.sellSlippageBps(),0,1000)
            &&between(a.maxSpreadBps(),1,3000)
            &&a.minDepthLots()>=1&&a.minOpenInterestLots()>=1
            &&between(a.maxWingDistanceBps(),100,10000)
            &&between(a.maxIvPercent(),5,500)
            &&positive(a.fourLegFeesInr())&&positive(a.researchCapitalInr())
            &&between(a.maxWorstExpiryLossAllocationBps(),100,10000)
            &&between(a.minCreditToWorstLossBps(),100,100000)
            &&between(a.maxOneSigmaMoveToNearestShortStrikeBps(),100,100000);
    }
    private static boolean ivValid(BigDecimal iv,int maxIv){
        return positive(iv)&&iv.compareTo(BigDecimal.valueOf(maxIv))<=0;
    }
    private static boolean provider(String s) {
        return "UPSTOX_ANALYTICS".equals(s)||"ANGEL_ONE_SMARTAPI".equals(s);
    }
    private static boolean key(String s) {
        return s!=null&&s.matches("NSE_FO\\|[A-Za-z0-9._-]{1,64}");
    }
    private static boolean positive(BigDecimal x){return x!=null&&x.signum()>0;}
    private static boolean between(int x,int lo,int hi){return x>=lo&&x<=hi;}
    private static BigDecimal r(BigDecimal x) {
        return x==null?null:x.setScale(4,RoundingMode.HALF_UP);
    }
    private static Decision measured(Status status,String reason,Contract lp,Contract sp,
                                     Contract sc,Contract lc,Instant at,BigDecimal credit,
                                     BigDecimal profit,BigDecimal loss,BigDecimal lower,
                                     BigDecimal upper,BigDecimal move) {
        return new Decision(status,reason,STRATEGY_ID,lp.key(),sp.key(),sc.key(),lc.key(),
            at,r(credit),r(profit),r(loss),r(lower),r(upper),r(move),true);
    }
    private static Decision blocked(Status status,String reason,Instant at) {
        return new Decision(status,reason,STRATEGY_ID,null,null,null,null,
            at,null,null,null,null,null,null,true);
    }
}
