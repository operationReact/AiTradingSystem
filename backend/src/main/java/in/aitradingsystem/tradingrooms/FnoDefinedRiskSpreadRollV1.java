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
import java.util.HashSet;
import java.util.Set;

/**
 * STR-208 (39/43): deterministic, NON-EXECUTABLE research for rolling an
 * ALREADY BROKER-CONFIRMED long NIFTY bear-put debit vertical into a new
 * later-expiring long NIFTY bear-put debit vertical.
 *
 * Each current leg is unwound hypothetically at the real bid/ask, then each
 * new leg is notionally opened on its real bid/ask; slippage/fees are modeled.
 * A bounded-expiry put vertical is NOT an order, actual margin check, fill
 * ledger or guaranteed risk reduction across the historical lifecycle.
 */
public final class FnoDefinedRiskSpreadRollV1 {
    private FnoDefinedRiskSpreadRollV1() {}
    public static final String STRATEGY_ID="strategy.STR-208";
    public static final String INDEX="NSE_INDEX|Nifty 50";
    private static final ZoneId IST=ZoneId.of("Asia/Kolkata");
    private static final LocalTime OPEN=LocalTime.of(9,15);
    private static final LocalTime CLOSE=LocalTime.of(15,30);
    private static final MathContext MC=MathContext.DECIMAL128;
    private static final BigDecimal BPS=BigDecimal.valueOf(10000);

    public enum Status { BLOCKED_RIGHTS, BLOCKED_DATA, NO_ROLL, RESEARCH_CANDIDATE }
    public record Rights(boolean verifiedBrokerExistingVertical,
                         boolean licensedNiftyIndexResearch,
                         boolean licensedOriginalCompletedHistory,
                         boolean licensedListedOptionChain,
                         boolean licensedOriginalOptionGreeks,
                         boolean licensedOfficialInstrumentMaster) {}
    public record Contract(String key,String index,String type,BigDecimal strike,
                           BigDecimal tick,LocalDate listedFrom,LocalDate expiry,
                           int officialLot,boolean sourceListed) {}
    /**
     * Existing original broker-confirmed ratio 1 long higher PE : 1 short
     * lower PE. Original entry debit is actual broker-reported cash flow,
     * not an inferred model fill, and must predate current quotes.
     */
    public record BrokerSpread(String longKey,String shortKey,
                               int verifiedLongLots,int verifiedShortLots,
                               BigDecimal brokerOriginalEntryDebitInr,
                               Instant brokerFirstKnownAt,boolean brokerVerified) {}
    public record Quote(String provider,String key,BigDecimal bid,BigDecimal ask,
                        BigDecimal originalDelta,BigDecimal originalAnnualizedIvPercent,
                        long openInterestLots,long bidDepthLots,long askDepthLots,
                        Instant exchangeAt,Instant firstKnownAt,boolean sourceVerified) {}
    public record CompletedClose(LocalDate session,String provider,BigDecimal indexClose,
                                 Instant exchangeCloseAt,Instant firstKnownAt,
                                 boolean sourceVerified) {}
    public record Snapshot(String provider,String indexKey,BigDecimal indexSpot,
                           Quote oldLongPut,Quote oldShortPut,
                           Quote newLongPut,Quote newShortPut,
                           Instant exchangeAt,Instant firstKnownAt,
                           boolean sourceVerified) {}
    public record Policy(int maxQuoteAgeSeconds,int maxSkewSeconds,
                         int maxOptionSpreadBps,long minOpenInterestLots,
                         int maxAnnualizedIvPercent,int maxExistingLots,
                         int maxOldDteDays,int minNewExtensionDays,
                         int maxNewDteDays,int minPutWingWidthPoints,
                         int minObservedDownMoveBps,int buySlippageBps,
                         int sellSlippageBps,BigDecimal modeledFourLegFeeInr,
                         BigDecimal maxPositiveNetRollDebitInr,
                         BigDecimal maxNewVerticalLossInr) {}
    /**
     * Amounts are entirely hypothetical except the original broker-reported
     * entry cash flow. No historical roll fills or position lifecycle P&L.
     */
    public record Decision(Status status,String reasonCode,String strategyId,Instant asOf,
                           int verifiedExistingLots,LocalDate oldExpiry,LocalDate newExpiry,
                           BigDecimal worstCompletedIndexDeclineBps,
                           BigDecimal modeledOldCloseCreditInr,
                           BigDecimal modeledNewOpenDebitInr,
                           BigDecimal modeledNetRollDebitInr,
                           BigDecimal modeledNewVerticalMaximumLossInr,
                           BigDecimal modeledNewVerticalMaximumGainInr,
                           BigDecimal modeledWholeLifecycleNetDebitInr,
                           int hypotheticalRollLots,boolean expiryScenarioOnly) {
        public boolean executable(){return false;}
        public boolean orderPlaced(){return false;}
        public boolean paperTradeAuthorized(){return false;}
        public boolean liveTradeAuthorized(){return false;}
    }

    public static Decision evaluate(BrokerSpread broker,Contract oldLong,Contract oldShort,
                                    Contract newLong,Contract newShort,Snapshot snapshot,
                                    List<CompletedClose> completedHistory,
                                    Rights rights,Policy policy,Instant asOf) {
        Objects.requireNonNull(asOf,"asOf");
        if (rights==null || !rights.verifiedBrokerExistingVertical()
                || !rights.licensedNiftyIndexResearch()
                || !rights.licensedOriginalCompletedHistory()
                || !rights.licensedListedOptionChain()
                || !rights.licensedOriginalOptionGreeks()
                || !rights.licensedOfficialInstrumentMaster())
            return blocked(Status.BLOCKED_RIGHTS,"ROLL_SOURCE_RIGHTS_UNVERIFIED",asOf);

        if (!validPolicy(policy)||!validContracts(oldLong,oldShort,newLong,newShort,asOf,policy)
                || !validBroker(broker,oldLong,oldShort,policy,asOf)
                || !validSnapshot(snapshot,policy,asOf)
                || broker.brokerFirstKnownAt().isAfter(snapshot.exchangeAt())
                || !validCompletedHistory(completedHistory,snapshot))
            return blocked(Status.BLOCKED_DATA,"ROLL_BROKER_OR_SOURCE_INVALID",asOf);

        int lots=broker.verifiedLongLots();
        if (!validQuote(snapshot.oldLongPut(),oldLong,"SELL",lots,snapshot,policy,asOf)
                || !validQuote(snapshot.oldShortPut(),oldShort,"BUY",lots,snapshot,policy,asOf)
                || !validQuote(snapshot.newLongPut(),newLong,"BUY",lots,snapshot,policy,asOf)
                || !validQuote(snapshot.newShortPut(),newShort,"SELL",lots,snapshot,policy,asOf))
            return blocked(Status.BLOCKED_DATA,"ROLL_FOUR_LEG_ORIGINAL_QUOTE_INVALID",asOf);

        // A verified current close-to-close decline is an observation, not a
        // forecast or an automatic recommendation to increase risk.
        BigDecimal worstDrop=BigDecimal.ZERO;
        for(int t=1;t<completedHistory.size();t++){
            BigDecimal prior=completedHistory.get(t-1).indexClose();
            BigDecimal current=completedHistory.get(t).indexClose();
            BigDecimal ret=current.divide(prior,MC).subtract(BigDecimal.ONE,MC);
            if(ret.signum()<0){
                BigDecimal drop=ret.negate().multiply(BPS,MC);
                if(drop.compareTo(worstDrop)>0)worstDrop=drop;
            }
        }
        if(worstDrop.compareTo(BigDecimal.valueOf(policy.minObservedDownMoveBps()))<0)
            return measured(Status.NO_ROLL,"ROLL_NO_OBSERVED_DOWNSIDE_REGIME",asOf,broker,
                    oldLong,newLong,worstDrop,null,null,null,null,null,null,0);

        BigDecimal units=BigDecimal.valueOf((long)lots*oldLong.officialLot());
        // Close: SELL currently held long put at bid; BUY to close short at ask.
        BigDecimal oldCredit=effectiveSell(snapshot.oldLongPut().bid(),policy.sellSlippageBps())
                .subtract(effectiveBuy(snapshot.oldShortPut().ask(),policy.buySlippageBps()),MC)
                .multiply(units,MC);
        // Open: BUY later-dated high-strike PE at ask; SELL lower-strike PE at bid.
        BigDecimal newDebit=effectiveBuy(snapshot.newLongPut().ask(),policy.buySlippageBps())
                .subtract(effectiveSell(snapshot.newShortPut().bid(),policy.sellSlippageBps()),MC)
                .multiply(units,MC);
        BigDecimal width=newLong.strike().subtract(newShort.strike(),MC).multiply(units,MC);
        // A credit vertical is NOT this evaluated long-put debit-spread strategy.
        if(newDebit.signum()<=0 || newDebit.compareTo(width)>=0)
            return measured(Status.NO_ROLL,"ROLL_NEW_VERTICAL_NOT_DEFINED_DEBIT",asOf,broker,
                    oldLong,newLong,worstDrop,oldCredit,newDebit,null,null,null,null,0);

        // Allocate all modeled four-leg friction conservatively to new-spread
        // maximum expiry loss; this is NOT margin / real broker risk verification.
        BigDecimal modeledFee=policy.modeledFourLegFeeInr();
        BigDecimal newMaxLoss=newDebit.add(modeledFee,MC);
        BigDecimal newMaxGain=width.subtract(newMaxLoss,MC);
        BigDecimal netRollDebit=newDebit.subtract(oldCredit,MC).add(modeledFee,MC);
        BigDecimal wholeLifecycleDebit=broker.brokerOriginalEntryDebitInr()
                .add(netRollDebit,MC);
        if(newMaxGain.signum()<=0
                || newMaxLoss.compareTo(policy.maxNewVerticalLossInr())>0
                || netRollDebit.compareTo(policy.maxPositiveNetRollDebitInr())>0)
            return measured(Status.NO_ROLL,"ROLL_COST_OR_NEW_VERTICAL_RISK_OVER_POLICY",asOf,broker,
                    oldLong,newLong,worstDrop,oldCredit,newDebit,netRollDebit,
                    newMaxLoss,newMaxGain,wholeLifecycleDebit,0);

        return measured(Status.RESEARCH_CANDIDATE,"ROLL_HYPOTHETICAL_FOUR_LEG_ONLY",asOf,broker,
                oldLong,newLong,worstDrop,oldCredit,newDebit,netRollDebit,
                newMaxLoss,newMaxGain,wholeLifecycleDebit,lots);
    }

    private static boolean validPolicy(Policy p){
        return p!=null && between(p.maxQuoteAgeSeconds(),30,600)
                && between(p.maxSkewSeconds(),0,120)
                && between(p.maxOptionSpreadBps(),1,5000)
                && p.minOpenInterestLots()>=1
                && between(p.maxAnnualizedIvPercent(),30,500)
                && between(p.maxExistingLots(),1,100)
                && between(p.maxOldDteDays(),1,21)
                && between(p.minNewExtensionDays(),1,45)
                && between(p.maxNewDteDays(),7,90)
                && between(p.minPutWingWidthPoints(),1,10000)
                && between(p.minObservedDownMoveBps(),1,1000)
                && between(p.buySlippageBps(),0,1000)
                && between(p.sellSlippageBps(),0,1000)
                && positive(p.modeledFourLegFeeInr())
                && positive(p.maxPositiveNetRollDebitInr())
                && positive(p.maxNewVerticalLossInr());
    }
    private static boolean validContracts(Contract a,Contract b,Contract c,Contract d,
                                          Instant now,Policy p){
        if(!validPut(a,now)||!validPut(b,now)||!validPut(c,now)||!validPut(d,now))
            return false;
        Set<String> keys=new HashSet<>(List.of(a.key(),b.key(),c.key(),d.key()));
        if(keys.size()!=4 || !a.expiry().equals(b.expiry())
                || !c.expiry().equals(d.expiry())
                || a.officialLot()!=b.officialLot()
                || a.officialLot()!=c.officialLot()
                || a.officialLot()!=d.officialLot()
                || a.strike().compareTo(b.strike())<=0
                || c.strike().compareTo(d.strike())<=0
                || a.strike().subtract(b.strike(),MC)
                    .compareTo(BigDecimal.valueOf(p.minPutWingWidthPoints()))<0
                || c.strike().subtract(d.strike(),MC)
                    .compareTo(BigDecimal.valueOf(p.minPutWingWidthPoints()))<0)
            return false;
        LocalDate today=now.atZone(IST).toLocalDate();
        long oldDte=ChronoUnit.DAYS.between(today,a.expiry());
        long newDte=ChronoUnit.DAYS.between(today,c.expiry());
        long extension=ChronoUnit.DAYS.between(a.expiry(),c.expiry());
        return oldDte>=1 && oldDte<=p.maxOldDteDays()
                && extension>=p.minNewExtensionDays()
                && newDte<=p.maxNewDteDays();
    }
    private static boolean validPut(Contract c,Instant now){
        return c!=null && c.sourceListed() && key(c.key())
                && INDEX.equals(c.index()) && "PE".equals(c.type())
                && positive(c.strike()) && positive(c.tick())
                && c.strike().remainder(c.tick()).signum()==0
                && c.officialLot()>0 && c.listedFrom()!=null && c.expiry()!=null
                && !c.listedFrom().isAfter(now.atZone(IST).toLocalDate());
    }
    private static boolean validBroker(BrokerSpread b,Contract a,Contract s,
                                       Policy p,Instant now){
        if(b==null || !b.brokerVerified() || !a.key().equals(b.longKey())
                || !s.key().equals(b.shortKey())
                || b.verifiedLongLots()<1 || b.verifiedLongLots()>p.maxExistingLots()
                || b.verifiedLongLots()!=b.verifiedShortLots()
                || b.brokerFirstKnownAt()==null || b.brokerFirstKnownAt().isAfter(now)
                || !positive(b.brokerOriginalEntryDebitInr()))
            return false;
        BigDecimal width=a.strike().subtract(s.strike(),MC)
                .multiply(BigDecimal.valueOf((long)b.verifiedLongLots()*a.officialLot()),MC);
        return b.brokerOriginalEntryDebitInr().compareTo(width)<0;
    }
    private static boolean validSnapshot(Snapshot s,Policy p,Instant now){
        if(s==null || !s.sourceVerified() || !provider(s.provider())
                || !INDEX.equals(s.indexKey()) || !positive(s.indexSpot())
                || s.exchangeAt()==null || s.firstKnownAt()==null
                || s.firstKnownAt().isBefore(s.exchangeAt())
                || s.firstKnownAt().isAfter(now)
                || Duration.between(s.exchangeAt(),now).compareTo(
                        Duration.ofSeconds(p.maxQuoteAgeSeconds()))>0)
            return false;
        var z=s.exchangeAt().atZone(IST);
        return z.getDayOfWeek().getValue()<=5
                && !z.toLocalTime().isBefore(OPEN)
                && !z.toLocalTime().isAfter(CLOSE);
    }
    private static boolean validQuote(Quote q,Contract c,String action,int lots,
                                      Snapshot s,Policy p,Instant now){
        if(q==null || !q.sourceVerified() || !c.key().equals(q.key())
                || !s.provider().equals(q.provider())
                || !spread(q.bid(),q.ask(),p.maxOptionSpreadBps())
                || !positive(q.originalAnnualizedIvPercent())
                || q.originalAnnualizedIvPercent().compareTo(
                        BigDecimal.valueOf(p.maxAnnualizedIvPercent()))>0
                || q.originalDelta()==null || q.originalDelta().signum()>=0
                || q.originalDelta().compareTo(BigDecimal.ONE.negate())<=0
                || q.openInterestLots()<p.minOpenInterestLots()
                || q.exchangeAt()==null || q.firstKnownAt()==null
                || q.firstKnownAt().isBefore(q.exchangeAt())
                || q.firstKnownAt().isAfter(s.firstKnownAt())
                || q.firstKnownAt().isAfter(now)
                || Duration.between(q.exchangeAt(),now).compareTo(
                        Duration.ofSeconds(p.maxQuoteAgeSeconds()))>0
                || Math.abs(Duration.between(q.exchangeAt(),s.exchangeAt()).toSeconds())
                        >p.maxSkewSeconds())
            return false;
        return "BUY".equals(action) ? q.askDepthLots()>=lots : q.bidDepthLots()>=lots;
    }
    private static boolean validCompletedHistory(List<CompletedClose> bars,Snapshot s){
        if(bars==null || bars.size()<35 || bars.size()>512)return false;
        LocalDate previous=null;
        Set<LocalDate> seen=new HashSet<>();
        for(CompletedClose b:bars){
            if(b==null || !b.sourceVerified() || b.session()==null
                    || b.session().getDayOfWeek().getValue()>5
                    || !seen.add(b.session()) || !s.provider().equals(b.provider())
                    || !positive(b.indexClose())
                    || b.exchangeCloseAt()==null || b.firstKnownAt()==null)
                return false;
            Instant close=b.session().atTime(CLOSE).atZone(IST).toInstant();
            if(!close.equals(b.exchangeCloseAt())
                    || b.firstKnownAt().isBefore(close)
                    || b.firstKnownAt().isAfter(close.plus(Duration.ofHours(36)))
                    || !b.firstKnownAt().isBefore(s.exchangeAt())
                    || previous!=null && (ChronoUnit.DAYS.between(previous,b.session())<1
                    || ChronoUnit.DAYS.between(previous,b.session())>8))
                return false;
            previous=b.session();
        }
        long lag=ChronoUnit.DAYS.between(previous,s.exchangeAt().atZone(IST).toLocalDate());
        return lag>=1 && lag<=5;
    }
    private static boolean spread(BigDecimal bid,BigDecimal ask,int maxBps){
        if(!positive(bid)||!positive(ask)||ask.compareTo(bid)<0)return false;
        BigDecimal mid=bid.add(ask,MC).divide(BigDecimal.valueOf(2),MC);
        return ask.subtract(bid,MC).multiply(BPS,MC).divide(mid,MC)
                .compareTo(BigDecimal.valueOf(maxBps))<=0;
    }
    private static BigDecimal effectiveBuy(BigDecimal price,int slipBps){
        return price.multiply(BPS.add(BigDecimal.valueOf(slipBps),MC).divide(BPS,MC),MC);
    }
    private static BigDecimal effectiveSell(BigDecimal price,int slipBps){
        return price.multiply(BPS.subtract(BigDecimal.valueOf(slipBps),MC).divide(BPS,MC),MC);
    }
    private static boolean provider(String x){
        return "UPSTOX_ANALYTICS".equals(x)||"ANGEL_ONE_SMARTAPI".equals(x);
    }
    private static boolean key(String x){
        return x!=null && x.startsWith("NSE_FO|")
                && x.substring(7).matches("[A-Za-z0-9._-]{1,64}");
    }
    private static boolean positive(BigDecimal x){return x!=null&&x.signum()>0;}
    private static boolean between(int x,int lo,int hi){return x>=lo&&x<=hi;}
    private static BigDecimal round(BigDecimal x){
        return x==null?null:x.setScale(4,RoundingMode.HALF_UP);
    }
    private static Decision blocked(Status status,String code,Instant now){
        return measured(status,code,now,null,null,null,null,null,null,null,null,null,null,0);
    }
    private static Decision measured(Status status,String code,Instant now,
                                     BrokerSpread broker,Contract oldLong,Contract newLong,
                                     BigDecimal worst,BigDecimal oldCredit,BigDecimal newDebit,
                                     BigDecimal net,BigDecimal maxLoss,BigDecimal maxGain,
                                     BigDecimal lifecycle,int lots){
        return new Decision(status,code,STRATEGY_ID,now,
                broker==null?0:broker.verifiedLongLots(),
                oldLong==null?null:oldLong.expiry(),
                newLong==null?null:newLong.expiry(),
                round(worst),round(oldCredit),round(newDebit),round(net),
                round(maxLoss),round(maxGain),round(lifecycle),lots,true);
    }
}
