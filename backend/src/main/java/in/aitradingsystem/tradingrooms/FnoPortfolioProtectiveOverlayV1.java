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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * STR-210 (41/43): deterministic, NON-EXECUTABLE research on a portfolio of
 * already broker-verified LONG NSE cash equities, using an optional long NIFTY
 * put overlay. Compares TWO genuine put strikes over THREE distinct historically
 * observed worst original-source NIFTY down days, each with SAME-DAY matched
 * equity returns at the currently confirmed long holdings weights.
 *
 * Put intrinsic is conditional on the one-day NIFTY shock level persisting
 * until the candidate's FUTURE expiry. No realized P&L, probability, fill,
 * expected hedge or protection of the actual heterogeneous equity basket.
 */
public final class FnoPortfolioProtectiveOverlayV1 {
    public static final String STRATEGY_ID = "strategy.STR-210";
    public static final String INDEX = "NSE_INDEX|Nifty 50";
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal BPS = BigDecimal.valueOf(10000);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime OPEN = LocalTime.of(9,15);
    private static final LocalTime CLOSE = LocalTime.of(15,30);
    private FnoPortfolioProtectiveOverlayV1() {}

    public enum Status { BLOCKED_RIGHTS, BLOCKED_DATA, NO_OVERLAY, RESEARCH_CANDIDATE }
    public record Rights(boolean brokerHoldingsResearch, boolean completedIndexCloses,
                         boolean completedEquityCloses, boolean licensedOptionChain,
                         boolean originalOptionGreeks, boolean officialInstrumentMaster) {}
    public record Holding(String key, long shares, Instant brokerFirstKnownAt,
                          boolean brokerVerified) {}
    public record EquityMark(String key, String provider, BigDecimal price,
                             Instant exchangeAt, Instant firstKnownAt,
                             boolean sourceVerified) {}
    public record EquityClose(String key, String provider, BigDecimal close,
                              Instant exchangeCloseAt, Instant firstKnownAt,
                              boolean sourceVerified) {}
    public record CompletedDay(LocalDate session, String provider, BigDecimal niftyClose,
                               Instant exchangeCloseAt, Instant firstKnownAt,
                               List<EquityClose> equities, boolean sourceVerified) {}
    public record PutContract(String key, String underlying, BigDecimal strike,
                              BigDecimal tick, LocalDate listedFrom,
                              LocalDate expiry, int officialLot, boolean sourceListed) {}
    public record PutQuote(String key, String provider, BigDecimal bid, BigDecimal ask,
                           BigDecimal originalPutDelta, BigDecimal originalIvAnnualizedPercent,
                           long originalOpenInterestLots, long originalAskDepthLots,
                           Instant exchangeAt, Instant firstKnownAt, boolean sourceVerified) {}
    public record Snapshot(String provider, String indexKey, BigDecimal indexSpot,
                           List<EquityMark> equityMarks, List<PutQuote> putQuotes,
                           Instant exchangeAt, Instant firstKnownAt, boolean sourceVerified) {}
    public record Policy(int minCompletedCloses, int maxQuoteAgeSeconds,
                         int maxQuoteSkewSeconds, int maxOptionSpreadBps,
                         long minOpenInterestLots, int maxAnnualizedIvPercent,
                         int maxLots, int buySlippageBps,
                         BigDecimal modeledOneOrderFeeInr, int premiumBudgetBps,
                         int minHistoricalDropBps, int maxHistoricalDropBps,
                         int minAggregateReductionBps, int maxExpiryDays) {}
    /**
     * Total scenario loss is a sum across three distinct conditional scenarios,
     * NOT a lifetime equity portfolio loss nor a true probabilistic estimate.
     */
    public record Decision(Status status, String reasonCode, String strategyId, Instant asOf,
                           int originalCompletedCloses, int distinctTailScenarios,
                           BigDecimal worstObservedNiftyDropBps,
                           BigDecimal verifiedCurrentLongEquityValueInr,
                           BigDecimal sumThreeScenarioUnhedgedLossesInr,
                           BigDecimal sumThreeScenarioAfterOverlayLossesInr,
                           String hypotheticalSelectedPut,
                           int hypotheticalBoughtLots, BigDecimal hypotheticalPutDebitInr,
                           BigDecimal aggregateScenarioLossReductionBps,
                           boolean cashEquityIndexBasisRiskUnmodeled,
                           boolean shockHeldToOptionExpiryAssumption) {
        public boolean executable() {return false;}
        public boolean orderPlaced() {return false;}
        public boolean paperTradeAuthorized() {return false;}
        public boolean liveTradeAuthorized() {return false;}
    }
    private record Tail(int dayIndex, BigDecimal niftyReturn) {}

    public static Decision evaluate(List<Holding> holdings, List<PutContract> candidates,
                                    Snapshot snapshot, List<CompletedDay> completed,
                                    Rights rights, Policy policy, Instant asOf) {
        Objects.requireNonNull(asOf,"asOf");
        if (rights == null || !rights.brokerHoldingsResearch()
                || !rights.completedIndexCloses() || !rights.completedEquityCloses()
                || !rights.licensedOptionChain() || !rights.originalOptionGreeks()
                || !rights.officialInstrumentMaster())
            return blocked(Status.BLOCKED_RIGHTS,"OVERLAY_RIGHTS_UNVERIFIED",asOf);

        Map<String, BigDecimal> currentValues = new HashMap<>();
        if (!validPolicy(policy) || !validSnapshot(snapshot,policy,asOf)
                || !validHoldings(holdings,snapshot,policy,currentValues)
                || !validCandidateAndQuotePairs(candidates,snapshot,policy,asOf)
                || !validHistory(completed,snapshot,currentValues.keySet(),policy))
            return blocked(Status.BLOCKED_DATA,"OVERLAY_ORIGINAL_SOURCES_INVALID",asOf);

        BigDecimal currentValue = BigDecimal.ZERO;
        for (BigDecimal v : currentValues.values()) currentValue = currentValue.add(v,MC);
        List<Tail> downs = new ArrayList<>();
        for (int i=1;i<completed.size();i++) {
            BigDecimal previous = completed.get(i-1).niftyClose();
            BigDecimal current = completed.get(i).niftyClose();
            BigDecimal r = current.divide(previous,MC).subtract(BigDecimal.ONE,MC);
            if (r.signum()<0) downs.add(new Tail(i,r));
        }
        downs.sort(Comparator.comparing(Tail::niftyReturn)
                .thenComparingInt(Tail::dayIndex));
        if (downs.size()<3)
            return observed(Status.NO_OVERLAY,"OVERLAY_FEWER_THAN_THREE_TRUE_DOWNS",
                    asOf,completed.size(),downs.size(),null,currentValue,
                    null,null,null,0,null,null);
        List<Tail> worstThree=downs.subList(0,3);
        BigDecimal[] indexScenario=new BigDecimal[3];
        BigDecimal[] equityLoss=new BigDecimal[3];
        BigDecimal aggregateLoss=BigDecimal.ZERO;
        for(int k=0;k<3;k++){
            Tail tail=worstThree.get(k);
            BigDecimal drop=tail.niftyReturn().negate().multiply(BPS,MC);
            if (drop.compareTo(BigDecimal.valueOf(policy.minHistoricalDropBps()))<0
                    || drop.compareTo(BigDecimal.valueOf(policy.maxHistoricalDropBps()))>0)
                return observed(Status.NO_OVERLAY,"OVERLAY_TAIL_SHOCK_OUTSIDE_POLICY",
                        asOf,completed.size(),3,worstThree.get(0).niftyReturn().negate()
                                .multiply(BPS,MC),currentValue,null,null,null,0,null,null);
            indexScenario[k]=snapshot.indexSpot().multiply(
                    BigDecimal.ONE.add(tail.niftyReturn(),MC),MC);
            Map<String,BigDecimal> previous=closeMap(completed.get(tail.dayIndex()-1).equities());
            BigDecimal basketChange=BigDecimal.ZERO;
            for(EquityClose equity:completed.get(tail.dayIndex()).equities()){
                BigDecimal ret=equity.close().divide(previous.get(equity.key()),MC)
                        .subtract(BigDecimal.ONE,MC);
                basketChange=basketChange.add(currentValues.get(equity.key())
                        .multiply(ret,MC),MC);
            }
            equityLoss[k]=basketChange.negate().max(BigDecimal.ZERO);
            aggregateLoss=aggregateLoss.add(equityLoss[k],MC);
        }
        BigDecimal worstDrop=worstThree.get(0).niftyReturn().negate().multiply(BPS,MC);
        if (aggregateLoss.signum()==0)
            return observed(Status.NO_OVERLAY,"OVERLAY_SAME_DAY_CASH_EQUITY_BASKET_DID_NOT_LOSE",
                    asOf,completed.size(),3,worstDrop,currentValue,aggregateLoss,
                    aggregateLoss,null,0,null,BigDecimal.ZERO);

        String selectedKey=null;
        int selectedLots=0;
        BigDecimal selectedDebit=null;
        BigDecimal selectedResidual=aggregateLoss;
        Map<String,PutQuote> quotes=new HashMap<>();
        for(PutQuote q:snapshot.putQuotes())quotes.put(q.key(),q);
        BigDecimal budget=currentValue.multiply(BigDecimal.valueOf(
                policy.premiumBudgetBps()),MC).divide(BPS,MC);
        for(PutContract c:candidates){
            PutQuote quote=quotes.get(c.key());
            BigDecimal unitDebit=quote.ask().multiply(BPS.add(
                    BigDecimal.valueOf(policy.buySlippageBps()),MC).divide(BPS,MC),MC)
                    .multiply(BigDecimal.valueOf(c.officialLot()),MC);
            int limit=(int)Math.min((long)policy.maxLots(),quote.originalAskDepthLots());
            for(int lots=1;lots<=limit;lots++){
                BigDecimal debit=unitDebit.multiply(BigDecimal.valueOf(lots),MC)
                        .add(policy.modeledOneOrderFeeInr(),MC);
                if(debit.compareTo(budget)>0)continue;
                // Do not model more index-equivalent exposure than the entire
                // broker-verified long equity portfolio. This is a strict
                // conservative notional ceiling, NOT a beta or basis hedge.
                BigDecimal overlayNotional=snapshot.indexSpot()
                        .multiply(BigDecimal.valueOf((long)lots*c.officialLot()),MC);
                if(overlayNotional.compareTo(currentValue)>0)break;
                BigDecimal residual=BigDecimal.ZERO;
                for(int k=0;k<3;k++){
                    BigDecimal putExpiryIntrinsic=c.strike().subtract(
                            indexScenario[k],MC).max(BigDecimal.ZERO)
                            .multiply(BigDecimal.valueOf((long)lots*c.officialLot()),MC);
                    // Each scenario is independent, but each hypothetical
                    // protection purchase still pays premium even if that put
                    // later expires worthless. Never clip a negative benefit
                    // to zero and misrepresent free downside insurance.
                    BigDecimal scenarioLoss=equityLoss[k].add(debit,MC)
                            .subtract(putExpiryIntrinsic,MC).max(BigDecimal.ZERO);
                    residual=residual.add(scenarioLoss,MC);
                }
                if(residual.compareTo(selectedResidual)<0
                        || (selectedKey!=null && residual.compareTo(selectedResidual)==0
                        && debit.compareTo(selectedDebit)<0)){
                    selectedKey=c.key();
                    selectedLots=lots;
                    selectedDebit=debit;
                    selectedResidual=residual;
                }
            }
        }
        BigDecimal reduction=aggregateLoss.subtract(selectedResidual,MC)
                .multiply(BPS,MC).divide(aggregateLoss,MC);
        if(selectedKey==null||reduction.compareTo(BigDecimal.valueOf(
                policy.minAggregateReductionBps()))<0)
            return observed(Status.NO_OVERLAY,"OVERLAY_NO_AFFORDABLE_MATERIAL_SCENARIO_OFFSET",
                    asOf,completed.size(),3,worstDrop,currentValue,aggregateLoss,
                    aggregateLoss,null,0,null,BigDecimal.ZERO);
        return observed(Status.RESEARCH_CANDIDATE,
                "OVERLAY_HYPOTHETICAL_THREE_EXPIRY_SCENARIOS_ONLY",asOf,
                completed.size(),3,worstDrop,currentValue,aggregateLoss,
                selectedResidual,selectedKey,selectedLots,selectedDebit,reduction);
    }
    private static boolean validPolicy(Policy p){
        return p!=null && between(p.minCompletedCloses(),65,252)
                && between(p.maxQuoteAgeSeconds(),30,600)
                && between(p.maxQuoteSkewSeconds(),0,120)
                && between(p.maxOptionSpreadBps(),1,5000)
                && p.minOpenInterestLots()>=1
                && between(p.maxAnnualizedIvPercent(),30,500)
                && between(p.maxLots(),1,500)
                && between(p.buySlippageBps(),0,1000)
                && positive(p.modeledOneOrderFeeInr())
                && between(p.premiumBudgetBps(),1,5000)
                && between(p.minHistoricalDropBps(),1,5000)
                && between(p.maxHistoricalDropBps(),200,9500)
                && p.minHistoricalDropBps()<=p.maxHistoricalDropBps()
                && between(p.minAggregateReductionBps(),1,9900)
                && between(p.maxExpiryDays(),7,90);
    }
    private static boolean validSnapshot(Snapshot s,Policy p,Instant now){
        if(s==null||!s.sourceVerified()||!provider(s.provider())
                ||!INDEX.equals(s.indexKey())||!positive(s.indexSpot())
                ||s.exchangeAt()==null||s.firstKnownAt()==null
                ||s.firstKnownAt().isBefore(s.exchangeAt())
                ||s.firstKnownAt().isAfter(now)
                ||Duration.between(s.exchangeAt(),now)
                    .compareTo(Duration.ofSeconds(p.maxQuoteAgeSeconds()))>0
                ||s.equityMarks()==null||s.putQuotes()==null)return false;
        var z=s.exchangeAt().atZone(IST);
        return z.getDayOfWeek().getValue()<=5&&!z.toLocalTime().isBefore(OPEN)
                &&!z.toLocalTime().isAfter(CLOSE);
    }
    private static boolean validHoldings(List<Holding> holdings,Snapshot s,
                                         Policy p,Map<String,BigDecimal> values){
        if(holdings==null||holdings.isEmpty()||holdings.size()>15
                ||s.equityMarks().size()!=holdings.size())return false;
        Map<String,Long> shares=new HashMap<>();
        for(Holding h:holdings){
            if(h==null||!equityKey(h.key())||!h.brokerVerified()
                    ||h.shares()<1||h.shares()>10_000_000
                    ||h.brokerFirstKnownAt()==null
                    ||h.brokerFirstKnownAt().isAfter(s.exchangeAt())
                    ||shares.putIfAbsent(h.key(),h.shares())!=null)return false;
        }
        for(EquityMark mark:s.equityMarks()){
            if(mark==null||!mark.sourceVerified()||!shares.containsKey(mark.key())
                    ||values.containsKey(mark.key())||!s.provider().equals(mark.provider())
                    ||!positive(mark.price())||mark.exchangeAt()==null
                    ||mark.firstKnownAt()==null
                    ||mark.firstKnownAt().isBefore(mark.exchangeAt())
                    ||mark.firstKnownAt().isAfter(s.firstKnownAt())
                    ||Math.abs(Duration.between(mark.exchangeAt(),s.exchangeAt()).toSeconds())
                        >p.maxQuoteSkewSeconds())return false;
            values.put(mark.key(),mark.price().multiply(
                    BigDecimal.valueOf(shares.get(mark.key())),MC));
        }
        return values.size()==holdings.size();
    }
    private static boolean validCandidateAndQuotePairs(List<PutContract> puts,
                                                        Snapshot s,Policy policy,Instant now){
        if(puts==null||puts.size()!=2||s.putQuotes().size()!=2)return false;
        Set<String> keys=new HashSet<>();
        LocalDate commonExpiry=null;
        int commonLot=0;
        LocalDate today=now.atZone(IST).toLocalDate();
        for(PutContract c:puts){
            if(c==null||!c.sourceListed()||!optionKey(c.key())||!keys.add(c.key())
                    ||!INDEX.equals(c.underlying())||!positive(c.strike())
                    ||!positive(c.tick())||c.strike().remainder(c.tick()).signum()!=0
                    ||c.strike().compareTo(s.indexSpot())>=0||c.officialLot()<1
                    ||c.listedFrom()==null||c.listedFrom().isAfter(today)
                    ||c.expiry()==null)return false;
            long days=ChronoUnit.DAYS.between(today,c.expiry());
            if(days<7||days>policy.maxExpiryDays())return false;
            if(commonExpiry==null){commonExpiry=c.expiry();commonLot=c.officialLot();}
            else if(!commonExpiry.equals(c.expiry())||commonLot!=c.officialLot())return false;
        }
        for(PutQuote q:s.putQuotes()){
            if(q==null||!keys.remove(q.key())||!q.sourceVerified()
                    ||!s.provider().equals(q.provider())||!positive(q.bid())
                    ||!positive(q.ask())||q.ask().compareTo(q.bid())<0
                    ||q.originalPutDelta()==null||q.originalPutDelta().signum()>=0
                    ||q.originalPutDelta().compareTo(BigDecimal.ONE.negate())<=0
                    ||!positive(q.originalIvAnnualizedPercent())
                    ||q.originalIvAnnualizedPercent().compareTo(
                        BigDecimal.valueOf(policy.maxAnnualizedIvPercent()))>0
                    ||q.originalOpenInterestLots()<policy.minOpenInterestLots()
                    ||q.originalAskDepthLots()<1||q.exchangeAt()==null
                    ||q.firstKnownAt()==null||q.firstKnownAt().isBefore(q.exchangeAt())
                    ||q.firstKnownAt().isAfter(s.firstKnownAt())
                    ||q.firstKnownAt().isAfter(now)
                    ||Duration.between(q.exchangeAt(),now).compareTo(
                        Duration.ofSeconds(policy.maxQuoteAgeSeconds()))>0
                    ||Math.abs(Duration.between(q.exchangeAt(),s.exchangeAt()).toSeconds())
                        >policy.maxQuoteSkewSeconds())return false;
            BigDecimal mid=q.bid().add(q.ask(),MC).divide(BigDecimal.valueOf(2),MC);
            if(q.ask().subtract(q.bid(),MC).multiply(BPS,MC).divide(mid,MC)
                    .compareTo(BigDecimal.valueOf(policy.maxOptionSpreadBps()))>0)return false;
        }
        return keys.isEmpty();
    }
    private static boolean validHistory(List<CompletedDay> bars,Snapshot s,
                                        Set<String> keys,Policy p){
        if(bars==null||bars.size()<p.minCompletedCloses()||bars.size()>512)return false;
        LocalDate previous=null;
        for(CompletedDay day:bars){
            if(day==null||!day.sourceVerified()||day.session()==null
                    ||day.session().getDayOfWeek().getValue()>5
                    ||!s.provider().equals(day.provider())||!positive(day.niftyClose())
                    ||day.exchangeCloseAt()==null||day.firstKnownAt()==null
                    ||day.equities()==null||day.equities().size()!=keys.size())return false;
            Instant close=day.session().atTime(CLOSE).atZone(IST).toInstant();
            if(!close.equals(day.exchangeCloseAt())
                    ||day.firstKnownAt().isBefore(close)
                    ||day.firstKnownAt().isAfter(close.plus(Duration.ofHours(36)))
                    ||!day.firstKnownAt().isBefore(s.exchangeAt())
                    ||previous!=null&&(ChronoUnit.DAYS.between(previous,day.session())<1
                    ||ChronoUnit.DAYS.between(previous,day.session())>8))return false;
            Set<String> seen=new HashSet<>();
            for(EquityClose e:day.equities()){
                if(e==null||!e.sourceVerified()||!keys.contains(e.key())||!seen.add(e.key())
                        ||!s.provider().equals(e.provider())||!positive(e.close())
                        ||e.exchangeCloseAt()==null||!close.equals(e.exchangeCloseAt())
                        ||e.firstKnownAt()==null||e.firstKnownAt().isBefore(close)
                        ||e.firstKnownAt().isAfter(close.plus(Duration.ofHours(36)))
                        ||!e.firstKnownAt().isBefore(s.exchangeAt()))return false;
            }
            previous=day.session();
        }
        long lag=ChronoUnit.DAYS.between(previous,s.exchangeAt().atZone(IST).toLocalDate());
        return lag>=1&&lag<=5;
    }
    private static Map<String,BigDecimal> closeMap(List<EquityClose> equities){
        Map<String,BigDecimal> m=new HashMap<>();
        for(EquityClose e:equities)m.put(e.key(),e.close());
        return m;
    }
    private static boolean provider(String p){
        return "UPSTOX_ANALYTICS".equals(p)||"ANGEL_ONE_SMARTAPI".equals(p);
    }
    private static boolean equityKey(String k){
        return k!=null&&k.startsWith("NSE_EQ|")
                &&k.substring(7).matches("[A-Za-z0-9._-]{1,64}");
    }
    private static boolean optionKey(String k){
        return k!=null&&k.startsWith("NSE_FO|")
                &&k.substring(7).matches("[A-Za-z0-9._-]{1,64}");
    }
    private static boolean positive(BigDecimal b){return b!=null&&b.signum()>0;}
    private static boolean between(int v,int lo,int hi){return v>=lo&&v<=hi;}
    private static BigDecimal round(BigDecimal v){
        return v==null?null:v.setScale(4,RoundingMode.HALF_UP);
    }
    private static Decision blocked(Status s,String reason,Instant now){
        return observed(s,reason,now,0,0,null,null,null,null,null,0,null,null);
    }
    private static Decision observed(Status s,String reason,Instant now,
                                     int completed,int scenarios,BigDecimal worstDrop,
                                     BigDecimal value,BigDecimal unhedged,
                                     BigDecimal residual,String key,int lots,
                                     BigDecimal debit,BigDecimal reduction){
        return new Decision(s,reason,STRATEGY_ID,now,completed,scenarios,round(worstDrop),
                round(value),round(unhedged),round(residual),key,lots,round(debit),
                round(reduction),true,true);
    }
}
