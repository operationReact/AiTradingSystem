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
 * STR-198 research-only long CALL butterfly: buy low-strike CE, sell 2x ATM
 * center-strike CE, buy high-strike CE. Symmetric strikes, same expiry and lot.
 * All three option contracts must exist; the 2x center lot cannot be omitted.
 * NO PAPER or LIVE broker orders are generated.
 */
public final class FnoLongButterflyV1 {
    public static final String STRATEGY_ID = "strategy.STR-198";
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal BPS = new BigDecimal("10000");
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime NSE_CLOSE = LocalTime.of(15, 30);
    private static final Duration MAX_QUOTE_AGE = Duration.ofSeconds(180);
    private static final Duration MAX_QUOTE_SKEW = Duration.ofSeconds(90);
    private FnoLongButterflyV1() {}

    public enum Status { BLOCKED_RIGHTS, BLOCKED_DATA, NO_SIGNAL, RESEARCH_CANDIDATE }

    public record Contract(String key, String underlying, String optionType,
                           BigDecimal strike, BigDecimal tick, LocalDate listedFrom,
                           LocalDate expiry, int lotSize, boolean sourceVerified) {}
    public record CompletedClose(LocalDate session, String provider, BigDecimal close,
                                 Instant exchangeCloseAt, Instant firstKnownAt,
                                 boolean verified) {}
    public record Quote(String provider, String key, BigDecimal bid, BigDecimal ask,
                        BigDecimal impliedVolatilityPercent, long bidDepthLots,
                        long askDepthLots, long openInterestLots,
                        Instant exchangeAt, Instant firstKnownAt) {}
    public record Rights(boolean indexResearch, boolean optionChainResearch,
                         boolean instrumentDiscoveryResearch, boolean impliedVolatilityResearch) {}
    /** IV is an annualized PERCENT: 20 means 20%, not 0.20. */
    public record Assumptions(int buySlippageBps, int sellSlippageBps,
                              int maxSpreadBps, long minDepthLots, long minOpenInterestLots,
                              int maxWingDistanceBps, int maxCenterAtmDistanceBps,
                              int maxIvPercent, BigDecimal fourContractFeesInr,
                              BigDecimal researchCapitalInr, int maxDebitAllocationBps,
                              int minRewardToRiskBps, int maxIvMoveToWingBps) {}
    public record Decision(Status status, String reasonCode, String strategyId,
                           String longLowerCallKey, String twoShortCenterCallsKey,
                           String longUpperCallKey, Instant asOf,
                           BigDecimal totalDebitWithFeesInr,
                           BigDecimal theoreticalMaximumExpiryLossInr,
                           BigDecimal theoreticalMaximumExpiryProfitInr,
                           BigDecimal lowerExpiryBreakeven, BigDecimal upperExpiryBreakeven,
                           BigDecimal illustrativeOneSigmaMove,
                           boolean expiryRiskBoundedOnlyWhenAllLegsIntact) {
        public boolean executable() { return false; }
        public boolean orderPlaced() { return false; }
        public boolean liveTradeAuthorized() { return false; }
    }

    public static Decision evaluate(Contract lowerCall, Contract centerCall, Contract upperCall,
                                    List<CompletedClose> completedDailyIndex,
                                    Quote indexQuote, Quote lowerQuote,
                                    Quote centerQuote, Quote upperQuote,
                                    Rights rights, Assumptions assumptions, Instant asOf) {
        Objects.requireNonNull(asOf, "asOf");
        if (rights==null || !rights.indexResearch() || !rights.optionChainResearch()
                || !rights.instrumentDiscoveryResearch() || !rights.impliedVolatilityResearch())
            return blocked(Status.BLOCKED_RIGHTS, "LONG_BUTTERFLY_RIGHTS_UNVERIFIED", asOf);
        if (!contractsValid(lowerCall,centerCall,upperCall,asOf)
                || !assumptionsValid(assumptions))
            return blocked(Status.BLOCKED_DATA, "LONG_BUTTERFLY_CONTRACT_OR_COST_INVALID", asOf);
        if (!completedHistoryValid(completedDailyIndex,asOf))
            return blocked(Status.BLOCKED_DATA, "LONG_BUTTERFLY_HISTORY_INVALID", asOf);
        if (!quotesValid(lowerCall,centerCall,upperCall,completedDailyIndex.getLast(),
                indexQuote,lowerQuote,centerQuote,upperQuote,assumptions,asOf))
            return blocked(Status.BLOCKED_DATA, "LONG_BUTTERFLY_QUOTES_OR_IV_INVALID", asOf);

        BigDecimal buyFactor=BPS.add(BigDecimal.valueOf(assumptions.buySlippageBps()),MC)
            .divide(BPS,MC);
        BigDecimal sellFactor=BPS.subtract(BigDecimal.valueOf(assumptions.sellSlippageBps()),MC)
            .divide(BPS,MC);
        BigDecimal lots=BigDecimal.valueOf(lowerCall.lotSize());
        // A true butterfly has FOUR option units: +1 lower CE, -2 center CE, +1 upper CE.
        BigDecimal debitUnit=lowerQuote.ask().add(upperQuote.ask(),MC)
            .multiply(buyFactor,MC)
            .subtract(centerQuote.bid().multiply(BigDecimal.valueOf(2),MC)
                .multiply(sellFactor,MC),MC);
        BigDecimal debit=debitUnit.multiply(lots,MC).add(assumptions.fourContractFeesInr(),MC);
        if (debit.signum()<=0)
            return measured(Status.NO_SIGNAL,"LONG_BUTTERFLY_NONPOSITIVE_DEBIT",
                lowerCall,centerCall,upperCall,asOf,debit,null,null,null,null,null);

        BigDecimal width=centerCall.strike().subtract(lowerCall.strike(),MC);
        // Width equality is verified in contractsValid(). Full protection requires both longs.
        BigDecimal maximumPayoff=width.multiply(lots,MC);
        if (debit.compareTo(maximumPayoff)>=0)
            return measured(Status.NO_SIGNAL,"LONG_BUTTERFLY_DEBIT_EXCEEDS_PEAK_PAYOFF",
                lowerCall,centerCall,upperCall,asOf,debit,debit,BigDecimal.ZERO,null,null,null);
        BigDecimal maxProfit=maximumPayoff.subtract(debit,MC);
        BigDecimal lowerBreakeven=lowerCall.strike().add(debit.divide(lots,MC),MC);
        BigDecimal upperBreakeven=upperCall.strike().subtract(debit.divide(lots,MC),MC);
        BigDecimal budget=assumptions.researchCapitalInr()
            .multiply(BigDecimal.valueOf(assumptions.maxDebitAllocationBps()),MC)
            .divide(BPS,MC);
        if (debit.compareTo(budget)>0)
            return measured(Status.NO_SIGNAL,"LONG_BUTTERFLY_FULL_DEBIT_BUDGET_EXCEEDED",
                lowerCall,centerCall,upperCall,asOf,debit,debit,maxProfit,lowerBreakeven,upperBreakeven,null);
        if (maxProfit.multiply(BPS,MC).divide(debit,MC)
                .compareTo(BigDecimal.valueOf(assumptions.minRewardToRiskBps()))<0)
            return measured(Status.NO_SIGNAL,"LONG_BUTTERFLY_REWARD_TO_RISK_INADEQUATE",
                lowerCall,centerCall,upperCall,asOf,debit,debit,maxProfit,lowerBreakeven,upperBreakeven,null);

        long dte=ChronoUnit.DAYS.between(asOf.atZone(IST).toLocalDate(),lowerCall.expiry());
        // Center has two option units, so its source IV carries 2x weight.
        BigDecimal weightedIv=lowerQuote.impliedVolatilityPercent()
            .add(centerQuote.impliedVolatilityPercent().multiply(BigDecimal.valueOf(2),MC),MC)
            .add(upperQuote.impliedVolatilityPercent(),MC)
            .divide(BigDecimal.valueOf(4),MC);
        BigDecimal oneSigma=indexQuote.bid().multiply(weightedIv,MC)
            .divide(BigDecimal.valueOf(100),MC)
            .multiply(BigDecimal.valueOf(StrictMath.sqrt(dte/365.0)),MC);
        // A simple square-root-of-time heuristic, not a distributional tail bound.
        if (oneSigma.multiply(BPS,MC).divide(width,MC)
                .compareTo(BigDecimal.valueOf(assumptions.maxIvMoveToWingBps()))>0)
            return measured(Status.NO_SIGNAL,"LONG_BUTTERFLY_IV_MOVE_EXCEEDS_WING_WIDTH",
                lowerCall,centerCall,upperCall,asOf,debit,debit,maxProfit,
                lowerBreakeven,upperBreakeven,oneSigma);

        return measured(Status.RESEARCH_CANDIDATE,"LONG_BUTTERFLY_RESEARCH_ONLY",
            lowerCall,centerCall,upperCall,asOf,debit,debit,maxProfit,
            lowerBreakeven,upperBreakeven,oneSigma);
    }

    private static boolean contractsValid(Contract low,Contract mid,Contract high,Instant at) {
        if(low==null||mid==null||high==null)return false;
        var cs=List.of(low,mid,high);
        var keys=new HashSet<String>();
        for(var c:cs) {
            if(!c.sourceVerified()||!"CE".equals(c.optionType())||!key(c.key())
                    ||!keys.add(c.key())
                    ||!FnoLongOptionCrossoverV1.UNDERLYING.equals(c.underlying())
                    ||!low.underlying().equals(c.underlying())
                    ||!positive(c.strike())||!positive(c.tick())
                    ||c.strike().remainder(c.tick()).signum()!=0
                    ||c.lotSize()<1||c.lotSize()!=low.lotSize()
                    ||c.expiry()==null||!c.expiry().equals(low.expiry())
                    ||c.listedFrom()==null
                    ||c.listedFrom().isAfter(at.atZone(IST).toLocalDate()))return false;
        }
        long dte=ChronoUnit.DAYS.between(at.atZone(IST).toLocalDate(),low.expiry());
        BigDecimal left=mid.strike().subtract(low.strike(),MC);
        BigDecimal right=high.strike().subtract(mid.strike(),MC);
        return dte>=3&&dte<=45&&left.signum()>0
            &&left.compareTo(right)==0; // symmetric only; broken wing is a different strategy.
    }

    private static boolean completedHistoryValid(List<CompletedClose> bars,Instant at) {
        if(bars==null||bars.size()<35||bars.size()>512)return false;
        LocalDate prior=null;String source=null;
        for(var bar:bars) {
            if(bar==null||!bar.verified()||bar.session()==null
                    ||bar.session().getDayOfWeek().getValue()>5
                    ||!provider(bar.provider())||!positive(bar.close())
                    ||bar.exchangeCloseAt()==null||bar.firstKnownAt()==null)return false;
            Instant close=bar.session().atTime(NSE_CLOSE).atZone(IST).toInstant();
            if(!close.equals(bar.exchangeCloseAt())||bar.firstKnownAt().isBefore(close)
                    ||bar.firstKnownAt().isAfter(close.plus(Duration.ofHours(36)))
                    ||bar.firstKnownAt().isAfter(at)
                    ||source!=null&&!source.equals(bar.provider()))return false;
            if(prior!=null){
                long gap=ChronoUnit.DAYS.between(prior,bar.session());
                if(gap<1||gap>5)return false;
            }
            prior=bar.session();source=bar.provider();
        }
        long age=ChronoUnit.DAYS.between(prior,at.atZone(IST).toLocalDate());
        return age>=0&&age<=4;
    }

    private static boolean quotesValid(Contract low,Contract mid,Contract high,
                                       CompletedClose last,Quote spot,Quote lq,Quote mq,
                                       Quote hq,Assumptions a,Instant at) {
        if(spot==null||lq==null||mq==null||hq==null)return false;
        if(!low.underlying().equals(spot.key())||!low.key().equals(lq.key())
                ||!mid.key().equals(mq.key())||!high.key().equals(hq.key()))return false;
        var qs=List.of(spot,lq,mq,hq);
        Instant min=null,max=null;
        for(int i=0;i<qs.size();i++){
            var q=qs.get(i);
            long depth=i==0?1:(i==2?2L*a.minDepthLots():a.minDepthLots());
            long oi=i==0?1:a.minOpenInterestLots();
            int spread=i==0?Integer.MAX_VALUE:a.maxSpreadBps();
            if(!last.provider().equals(q.provider())
                    ||!validQuote(q,depth,oi,spread,i==2)
                    ||!validTime(q.exchangeAt(),q.firstKnownAt(),at)
                    ||last.firstKnownAt().isAfter(q.exchangeAt()))return false;
            if(i>0&&!ivValid(q.impliedVolatilityPercent(),a.maxIvPercent()))return false;
            Instant t=q.exchangeAt();
            if(min==null||t.isBefore(min))min=t;
            if(max==null||t.isAfter(max))max=t;
        }
        if(Duration.between(min,max).compareTo(MAX_QUOTE_SKEW)>0)return false;
        BigDecimal index=spot.bid();
        BigDecimal atmDistance=index.subtract(mid.strike(),MC).abs()
            .multiply(BPS,MC).divide(index,MC);
        if(atmDistance.compareTo(BigDecimal.valueOf(a.maxCenterAtmDistanceBps()))>0)return false;
        BigDecimal lowDistance=index.subtract(low.strike(),MC)
            .multiply(BPS,MC).divide(index,MC);
        BigDecimal highDistance=high.strike().subtract(index,MC)
            .multiply(BPS,MC).divide(index,MC);
        return lowDistance.signum()>0&&highDistance.signum()>0
            &&lowDistance.compareTo(BigDecimal.valueOf(a.maxWingDistanceBps()))<=0
            &&highDistance.compareTo(BigDecimal.valueOf(a.maxWingDistanceBps()))<=0;
    }
    private static boolean validQuote(Quote q,long depth,long oi,int maxSpread,boolean selling){
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
    private static boolean assumptionsValid(Assumptions a){
        return a!=null&&between(a.buySlippageBps(),0,1000)
            &&between(a.sellSlippageBps(),0,1000)
            &&between(a.maxSpreadBps(),1,3000)
            &&a.minDepthLots()>=1&&a.minOpenInterestLots()>=1
            &&between(a.maxWingDistanceBps(),100,10000)
            &&between(a.maxCenterAtmDistanceBps(),1,1000)
            &&between(a.maxIvPercent(),5,500)
            &&positive(a.fourContractFeesInr())
            &&positive(a.researchCapitalInr())
            &&between(a.maxDebitAllocationBps(),100,10000)
            &&between(a.minRewardToRiskBps(),100,100000)
            &&between(a.maxIvMoveToWingBps(),100,100000);
    }
    private static boolean ivValid(BigDecimal iv,int max){
        return positive(iv)&&iv.compareTo(BigDecimal.valueOf(max))<=0;
    }
    private static boolean provider(String src) {
        return "UPSTOX_ANALYTICS".equals(src)||"ANGEL_ONE_SMARTAPI".equals(src);
    }
    private static boolean key(String k){
        return k!=null&&k.matches("NSE_FO\\|[A-Za-z0-9._-]{1,64}");
    }
    private static boolean positive(BigDecimal v){return v!=null&&v.signum()>0;}
    private static boolean between(int v,int min,int max){return v>=min&&v<=max;}
    private static BigDecimal rounded(BigDecimal v){
        return v==null?null:v.setScale(4,RoundingMode.HALF_UP);
    }
    private static Decision measured(Status status,String reason,Contract l,Contract m,Contract h,
                                     Instant at,BigDecimal debit,BigDecimal loss,
                                     BigDecimal profit,BigDecimal lower,BigDecimal upper,
                                     BigDecimal move){
        return new Decision(status,reason,STRATEGY_ID,l.key(),m.key(),h.key(),at,
            rounded(debit),rounded(loss),rounded(profit),rounded(lower),rounded(upper),
            rounded(move),true);
    }
    private static Decision blocked(Status status,String reason,Instant at){
        return new Decision(status,reason,STRATEGY_ID,null,null,null,at,
            null,null,null,null,null,null,true);
    }
}
