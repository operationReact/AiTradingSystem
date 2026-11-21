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
 * STR-203: retrospective, research-only gamma-scalping path estimate.
 * Long source-verified same-strike NIFTY CE and PE; hypothetical delta
 * rebalancing against an independently listed NIFTY futures instrument.
 *
 * This does NOT execute futures orders or backtest actual hedge fills.
 * Gamma approximation minus theta/cost is NOT realized options P&L.
 */
public final class FnoGammaScalpingV1 {
    public static final String STRATEGY_ID="strategy.STR-203";
    private static final MathContext MC=MathContext.DECIMAL128;
    private static final BigDecimal BPS=BigDecimal.valueOf(10000);
    private static final BigDecimal HALF=new BigDecimal("0.5");
    private static final ZoneId IST=ZoneId.of("Asia/Kolkata");
    private static final LocalTime CLOSE=LocalTime.of(15,30);
    private static final LocalTime OPEN=LocalTime.of(9,15);
    private FnoGammaScalpingV1(){}

    public enum Status { BLOCKED_RIGHTS, BLOCKED_DATA, NO_SIGNAL, RESEARCH_CANDIDATE }
    public record Rights(boolean indexResearch,boolean listedOptionResearch,
                         boolean originalGreeksResearch,boolean listedFuturesResearch,
                         boolean completedHistoryResearch) {}
    public record OptionContract(String key,String index,String optionType,BigDecimal strike,
                                 BigDecimal tick,LocalDate listedFrom,LocalDate expiry,
                                 int lotSize,boolean sourceVerified) {}
    public record FutureContract(String key,String index,BigDecimal tick,LocalDate listedFrom,
                                 LocalDate expiry,int lotSize,boolean sourceVerified) {}
    public record CompletedClose(LocalDate session,String provider,BigDecimal close,
                                 Instant exchangeCloseAt,Instant firstKnownAt,
                                 boolean sourceVerified) {}
    /**
     * Each sample is an authenticated, synchronized, originally known source
     * state. Delta is per underlying unit, gamma is per underlying point,
     * theta is option premium per day, IV is annualized percent.
     */
    public record Sample(String provider,String indexKey,String callKey,String putKey,
                         String futureKey,BigDecimal indexSpot,
                         BigDecimal futuresBid,BigDecimal futuresAsk,
                         BigDecimal callBid,BigDecimal callAsk,
                         BigDecimal putBid,BigDecimal putAsk,
                         BigDecimal callDelta,BigDecimal putDelta,
                         BigDecimal callGamma,BigDecimal putGamma,
                         BigDecimal callThetaPerDay,BigDecimal putThetaPerDay,
                         BigDecimal callIvPercent,BigDecimal putIvPercent,
                         long callOpenInterestLots,long putOpenInterestLots,
                         long futureBidDepthLots,long futureAskDepthLots,
                         Instant exchangeAt,Instant firstKnownAt,
                         boolean sourceVerified) {}
    public record Assumptions(int minSamples,int maxStepMinutes,int maxLastQuoteAgeSeconds,
                              int maxFutureSpreadBps,int maxOptionSpreadBps,
                              long minOiLots,long minFutureDepthLots,int maxAnnualizedIvPercent,
                              int futureBuySlippageBps,int futureSellSlippageBps,
                              BigDecimal perHedgeOrderFeesInr,
                              int minCaptureToThetaPlusCostBps) {}
    public record Decision(Status status,String reasonCode,String strategyId,
                           Instant asOf,int originalSamples,int hypotheticalFuturesRebalances,
                           int largestAbsoluteHedgeLots,
                           BigDecimal approximateGammaCaptureInr,
                           BigDecimal estimatedThetaDragInr,
                           BigDecimal estimatedFuturesHedgeCostsInr,
                           BigDecimal approximateNetGammaThetaAfterHedgeCostsInr,
                           boolean retrospectiveOnly,boolean actualOptionOrHedgeFillsObserved) {
        public boolean executable(){return false;}
        public boolean orderPlaced(){return false;}
        public boolean liveTradeAuthorized(){return false;}
    }

    public static Decision evaluate(OptionContract call,OptionContract put,FutureContract future,
                                    List<CompletedClose> completedIndexHistory,
                                    List<Sample> originalMarketPath,Rights rights,
                                    Assumptions a,Instant asOf){
        Objects.requireNonNull(asOf,"asOf");
        if(rights==null||!rights.indexResearch()||!rights.listedOptionResearch()
                ||!rights.originalGreeksResearch()||!rights.listedFuturesResearch()
                ||!rights.completedHistoryResearch())
            return blocked(Status.BLOCKED_RIGHTS,"GAMMA_SCALP_SOURCE_RIGHTS_UNVERIFIED",asOf);
        if(!contractsValid(call,put,future,asOf)||!assumptionsValid(a))
            return blocked(Status.BLOCKED_DATA,"GAMMA_SCALP_CONTRACT_OR_ASSUMPTIONS_INVALID",asOf);
        if(!historyValid(completedIndexHistory,asOf))
            return blocked(Status.BLOCKED_DATA,"GAMMA_SCALP_COMPLETED_HISTORY_INVALID",asOf);
        if(!samplesValid(originalMarketPath,call,put,future,
                completedIndexHistory.getLast(),a,asOf))
            return blocked(Status.BLOCKED_DATA,"GAMMA_SCALP_SOURCE_GREEKS_OR_PATH_INVALID",asOf);

        // All math is evaluated only after the entire path is known; no ex-ante
        // forecast, signed broker fill or real hedge P&L is inferred.
        BigDecimal gammaCapture=BigDecimal.ZERO;
        BigDecimal thetaDrag=BigDecimal.ZERO;
        BigDecimal hedgeCost=BigDecimal.ZERO;
        BigDecimal lot=BigDecimal.valueOf(call.lotSize());
        BigDecimal futureLot=BigDecimal.valueOf(future.lotSize());
        int previousTarget=0;
        int rebalances=0,maxHedgeLots=0;
        for(int i=0;i<originalMarketPath.size();i++){
            var cur=originalMarketPath.get(i);
            BigDecimal exposure=cur.callDelta().add(cur.putDelta(),MC)
                .multiply(lot,MC);
            int target=exposure.negate().divide(futureLot,MC)
                .setScale(0,RoundingMode.HALF_UP).intValueExact();
            // Book hypothetical futures hedge lot changes at contemporaneous
            // authenticated bid/ask, including slippage, spread and fixed fees.
            int deltaLots=target-previousTarget;
            if(deltaLots!=0){
                hedgeCost=hedgeCost.add(hedgeExecutionCost(cur,deltaLots,futureLot,a),MC);
                rebalances++;
            }
            previousTarget=target;
            maxHedgeLots=Math.max(maxHedgeLots,Math.abs(target));
            if(i>0){
                var prev=originalMarketPath.get(i-1);
                BigDecimal dSpot=cur.indexSpot().subtract(prev.indexSpot(),MC);
                BigDecimal meanGamma=prev.callGamma().add(prev.putGamma(),MC)
                    .add(cur.callGamma(),MC).add(cur.putGamma(),MC)
                    .divide(BigDecimal.valueOf(2),MC);
                gammaCapture=gammaCapture.add(dSpot.multiply(dSpot,MC)
                    .multiply(meanGamma,MC).multiply(lot,MC).multiply(HALF,MC),MC);
                BigDecimal meanTheta=prev.callThetaPerDay().add(prev.putThetaPerDay(),MC)
                    .add(cur.callThetaPerDay(),MC).add(cur.putThetaPerDay(),MC)
                    .divide(BigDecimal.valueOf(2),MC);
                BigDecimal days=BigDecimal.valueOf(
                    Duration.between(prev.exchangeAt(),cur.exchangeAt()).toSeconds())
                    .divide(BigDecimal.valueOf(86400),MC);
                thetaDrag=thetaDrag.subtract(meanTheta.multiply(days,MC).multiply(lot,MC),MC);
            }
        }
        // An actual hedge must eventually be closed: include hypothetical final
        // unwind costs even if the observation path stops with an open future.
        if(previousTarget!=0){
            var last=originalMarketPath.getLast();
            hedgeCost=hedgeCost.add(hedgeExecutionCost(last,-previousTarget,futureLot,a),MC);
        }
        BigDecimal costs=thetaDrag.add(hedgeCost,MC);
        BigDecimal net=gammaCapture.subtract(costs,MC);
        if(rebalances==0)
            return measured(Status.NO_SIGNAL,"GAMMA_SCALP_NO_DELTA_REBALANCE",
                asOf,originalMarketPath.size(),rebalances,maxHedgeLots,
                gammaCapture,thetaDrag,hedgeCost,net);
        if(net.signum()<=0||costs.signum()<=0||
                gammaCapture.multiply(BPS,MC).divide(costs,MC)
                    .compareTo(BigDecimal.valueOf(a.minCaptureToThetaPlusCostBps()))<0)
            return measured(Status.NO_SIGNAL,"GAMMA_SCALP_APPROXIMATE_CAPTURE_INADEQUATE",
                asOf,originalMarketPath.size(),rebalances,maxHedgeLots,
                gammaCapture,thetaDrag,hedgeCost,net);
        return measured(Status.RESEARCH_CANDIDATE,"GAMMA_SCALP_RETROSPECTIVE_RESEARCH_ONLY",
            asOf,originalMarketPath.size(),rebalances,maxHedgeLots,
            gammaCapture,thetaDrag,hedgeCost,net);
    }

    private static BigDecimal hedgeExecutionCost(Sample s,int change,BigDecimal futureLot,
                                                  Assumptions a){
        BigDecimal mid=s.futuresBid().add(s.futuresAsk(),MC)
            .divide(BigDecimal.valueOf(2),MC);
        BigDecimal price=change>0?s.futuresAsk():s.futuresBid();
        BigDecimal slipBps=BigDecimal.valueOf(change>0
            ?a.futureBuySlippageBps():a.futureSellSlippageBps());
        BigDecimal effective=change>0
            ?price.multiply(BPS.add(slipBps,MC).divide(BPS,MC),MC)
            :price.multiply(BPS.subtract(slipBps,MC).divide(BPS,MC),MC);
        return effective.subtract(mid,MC).abs()
            .multiply(BigDecimal.valueOf(Math.abs((long)change)),MC)
            .multiply(futureLot,MC).add(a.perHedgeOrderFeesInr(),MC);
    }

    private static boolean contractsValid(OptionContract c,OptionContract p,
                                          FutureContract f,Instant at){
        if(c==null||p==null||f==null||!c.sourceVerified()||!p.sourceVerified()
                ||!f.sourceVerified()||!key(c.key())||!key(p.key())||!key(f.key())
                ||c.key().equals(p.key())||c.key().equals(f.key())||p.key().equals(f.key())
                ||!"CE".equals(c.optionType())||!"PE".equals(p.optionType())
                ||!FnoLongOptionCrossoverV1.UNDERLYING.equals(c.index())
                ||!c.index().equals(p.index())||!c.index().equals(f.index())
                ||!positive(c.strike())||c.strike().compareTo(p.strike())!=0
                ||!positive(c.tick())||!positive(p.tick())||!positive(f.tick())
                ||c.strike().remainder(c.tick()).signum()!=0
                ||p.strike().remainder(p.tick()).signum()!=0
                ||c.lotSize()<1||c.lotSize()!=p.lotSize()||f.lotSize()<1
                ||c.expiry()==null||p.expiry()==null||f.expiry()==null
                ||!c.expiry().equals(p.expiry())
                ||c.listedFrom()==null||p.listedFrom()==null||f.listedFrom()==null)return false;
        var day=at.atZone(IST).toLocalDate();
        long optionDte=ChronoUnit.DAYS.between(day,c.expiry());
        long futureDte=ChronoUnit.DAYS.between(day,f.expiry());
        return !c.listedFrom().isAfter(day)&&!p.listedFrom().isAfter(day)
            &&!f.listedFrom().isAfter(day)
            &&optionDte>=3&&optionDte<=45&&futureDte>=3&&futureDte<=45;
    }
    private static boolean historyValid(List<CompletedClose> bars,Instant at){
        if(bars==null||bars.size()<35||bars.size()>512)return false;
        LocalDate previous=null;String src=null;
        for(var bar:bars){
            if(bar==null||!bar.sourceVerified()||bar.session()==null
                    ||bar.session().getDayOfWeek().getValue()>5
                    ||!provider(bar.provider())||!positive(bar.close())
                    ||bar.exchangeCloseAt()==null||bar.firstKnownAt()==null
                    ||src!=null&&!src.equals(bar.provider()))return false;
            Instant close=bar.session().atTime(CLOSE).atZone(IST).toInstant();
            if(!close.equals(bar.exchangeCloseAt())
                    ||bar.firstKnownAt().isBefore(close)
                    ||bar.firstKnownAt().isAfter(close.plus(Duration.ofHours(36)))
                    ||bar.firstKnownAt().isAfter(at))return false;
            if(previous!=null){
                long gap=ChronoUnit.DAYS.between(previous,bar.session());
                if(gap<1||gap>5)return false;
            }
            previous=bar.session();src=bar.provider();
        }
        long lag=ChronoUnit.DAYS.between(previous,at.atZone(IST).toLocalDate());
        return lag>=0&&lag<=4;
    }
    private static boolean samplesValid(List<Sample> path,OptionContract c,
                                        OptionContract p,FutureContract f,
                                        CompletedClose prior,Assumptions a,Instant asOf){
        if(path==null||path.size()<a.minSamples()||path.size()>250)return false;
        Instant prev=null;
        LocalDate day=asOf.atZone(IST).toLocalDate();
        for(var sample:path){
            if(sample==null||!sample.sourceVerified()
                    ||!prior.provider().equals(sample.provider())
                    ||!c.index().equals(sample.indexKey())
                    ||!c.key().equals(sample.callKey())
                    ||!p.key().equals(sample.putKey())
                    ||!f.key().equals(sample.futureKey())
                    ||!positive(sample.indexSpot())
                    ||!validSpread(sample.futuresBid(),sample.futuresAsk(),a.maxFutureSpreadBps())
                    ||!validSpread(sample.callBid(),sample.callAsk(),a.maxOptionSpreadBps())
                    ||!validSpread(sample.putBid(),sample.putAsk(),a.maxOptionSpreadBps())
                    ||!deltaValid(sample.callDelta(),true)
                    ||!deltaValid(sample.putDelta(),false)
                    ||!positive(sample.callGamma())||!positive(sample.putGamma())
                    ||sample.callThetaPerDay()==null||sample.putThetaPerDay()==null
                    ||sample.callThetaPerDay().signum()>=0
                    ||sample.putThetaPerDay().signum()>=0
                    ||!ivValid(sample.callIvPercent(),a.maxAnnualizedIvPercent())
                    ||!ivValid(sample.putIvPercent(),a.maxAnnualizedIvPercent())
                    ||sample.callOpenInterestLots()<a.minOiLots()
                    ||sample.putOpenInterestLots()<a.minOiLots()
                    ||sample.futureBidDepthLots()<a.minFutureDepthLots()
                    ||sample.futureAskDepthLots()<a.minFutureDepthLots()
                    ||sample.exchangeAt()==null||sample.firstKnownAt()==null
                    ||sample.exchangeAt().isAfter(sample.firstKnownAt())
                    ||sample.firstKnownAt().isAfter(asOf)
                    ||!prior.firstKnownAt().isBefore(sample.exchangeAt()))return false;
            var z=sample.exchangeAt().atZone(IST);
            if(!z.toLocalDate().equals(day)
                    ||z.getDayOfWeek().getValue()>5
                    ||z.toLocalTime().isBefore(OPEN)
                    ||z.toLocalTime().isAfter(CLOSE))return false;
            if(prev!=null){
                long gap=Duration.between(prev,sample.exchangeAt()).toSeconds();
                if(gap<60||gap>60L*a.maxStepMinutes())return false;
            }
            prev=sample.exchangeAt();
        }
        return Duration.between(prev,asOf)
            .compareTo(Duration.ofSeconds(a.maxLastQuoteAgeSeconds()))<=0;
    }
    private static boolean validSpread(BigDecimal bid,BigDecimal ask,int limit){
        if(!positive(bid)||!positive(ask)||ask.compareTo(bid)<0)return false;
        BigDecimal mid=bid.add(ask,MC).divide(BigDecimal.valueOf(2),MC);
        return ask.subtract(bid,MC).multiply(BPS,MC).divide(mid,MC)
            .compareTo(BigDecimal.valueOf(limit))<=0;
    }
    private static boolean deltaValid(BigDecimal v,boolean call){
        if(v==null)return false;
        return call?(v.signum()>0&&v.compareTo(BigDecimal.ONE)<0)
            :(v.signum()<0&&v.compareTo(BigDecimal.ONE.negate())>0);
    }
    private static boolean assumptionsValid(Assumptions a){
        return a!=null&&between(a.minSamples(),3,30)
            &&between(a.maxStepMinutes(),5,120)
            &&between(a.maxLastQuoteAgeSeconds(),30,600)
            &&between(a.maxFutureSpreadBps(),1,2000)
            &&between(a.maxOptionSpreadBps(),1,3000)
            &&a.minOiLots()>=1&&a.minFutureDepthLots()>=1
            &&between(a.maxAnnualizedIvPercent(),30,500)
            &&between(a.futureBuySlippageBps(),0,1000)
            &&between(a.futureSellSlippageBps(),0,1000)
            &&positive(a.perHedgeOrderFeesInr())
            &&between(a.minCaptureToThetaPlusCostBps(),100,100000);
    }
    private static boolean provider(String s){
        return "UPSTOX_ANALYTICS".equals(s)||"ANGEL_ONE_SMARTAPI".equals(s);
    }
    private static boolean key(String s){
        return s!=null&&s.matches("NSE_FO\\|[A-Za-z0-9._-]{1,64}");
    }
    private static boolean ivValid(BigDecimal iv,int max){
        return positive(iv)&&iv.compareTo(BigDecimal.valueOf(max))<=0;
    }
    private static boolean positive(BigDecimal x){return x!=null&&x.signum()>0;}
    private static boolean between(int n,int low,int high){return n>=low&&n<=high;}
    private static BigDecimal rounded(BigDecimal v){
        return v==null?null:v.setScale(4,RoundingMode.HALF_UP);
    }
    private static Decision measured(Status status,String reason,Instant at,int samples,
                                     int changes,int maxLots,BigDecimal gamma,BigDecimal theta,
                                     BigDecimal cost,BigDecimal net){
        return new Decision(status,reason,STRATEGY_ID,at,samples,changes,maxLots,
            rounded(gamma),rounded(theta),rounded(cost),rounded(net),true,false);
    }
    private static Decision blocked(Status status,String reason,Instant at){
        return measured(status,reason,at,0,0,0,null,null,null,null);
    }
}
