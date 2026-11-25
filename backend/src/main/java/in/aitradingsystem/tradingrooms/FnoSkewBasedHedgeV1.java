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
import java.util.Set;

/**
 * STR-212 (43/43) -- NON-EXECUTABLE, source-attested NIFTY put-skew research.
 *
 * Against an ACTUAL broker-verified existing LONG NIFTY future, compare the
 * quoted hypothetical purchase of a higher-strike OTM put versus buying the
 * same put AND selling a lower-strike put. Source-original same-expiry
 * near-25-delta call IV is a comparable reference to the bought put IV.
 * A steep put-vs-call IV skew is a diagnostic, NOT an edge or trade signal.
 *
 * The put spread sacrifices ALL incremental protective payoff below the lower
 * strike: NIFTY future losses continue to grow. No orders, no broker margin
 * conclusions, no actual fills, no P&L backtest or user rights change.
 */
public final class FnoSkewBasedHedgeV1 {
    public static final String STRATEGY_ID="strategy.STR-212";
    public static final String INDEX="NSE_INDEX|Nifty 50";
    private static final MathContext MC=MathContext.DECIMAL128;
    private static final BigDecimal BPS=BigDecimal.valueOf(10000);
    private static final ZoneId IST=ZoneId.of("Asia/Kolkata");
    private static final LocalTime OPEN=LocalTime.of(9,15);
    private static final LocalTime CLOSE=LocalTime.of(15,30);
    private FnoSkewBasedHedgeV1() {}

    public enum Status {BLOCKED_RIGHTS,BLOCKED_DATA,NO_HEDGE,RESEARCH_CANDIDATE}
    public record Rights(boolean brokerConfirmedExistingLongFuture,
                         boolean licensedOriginalNiftyCloses,
                         boolean licensedListedNiftyOptionChain,
                         boolean licensedOriginalOptionIVAndDelta,
                         boolean licensedListedFutureMarket,
                         boolean licensedOfficialNseInstrumentMaster) {}
    public record FutureContract(String key,String index,LocalDate listedFrom,
                                 LocalDate expiry,int officialLot,boolean sourceListed) {}
    public record BrokerLongFuture(String key,int verifiedLongLots,
                                   BigDecimal brokerAverageEntryPriceInr,
                                   Instant brokerFirstKnownAt,boolean brokerVerified) {}
    public record OptionContract(String key,String index,String type,BigDecimal strike,
                                 BigDecimal tick,LocalDate listedFrom,LocalDate expiry,
                                 int officialLot,boolean sourceListed) {}
    public record Quote(String provider,String key,BigDecimal bid,BigDecimal ask,
                        BigDecimal originalDelta,BigDecimal originalAnnualizedIvPercent,
                        long originalOpenInterestLots,long originalBidDepthLots,
                        long originalAskDepthLots,Instant exchangeAt,Instant firstKnownAt,
                        boolean sourceVerified) {}
    public record CompletedClose(LocalDate session,String provider,BigDecimal close,
                                 Instant exchangeCloseAt,Instant firstKnownAt,
                                 boolean sourceVerified) {}
    public record Snapshot(String provider,String indexKey,String futureKey,
                           BigDecimal genuineIndexSpot,BigDecimal genuineFutureBid,
                           BigDecimal genuineFutureAsk,Quote longHigherPut,
                           Quote shortLowerPut,Quote referenceOtmCall,
                           Instant exchangeAt,Instant firstKnownAt,boolean sourceVerified) {}
    public record Policy(int minimumCompletedCloses,int maxQuoteAgeSeconds,
                         int maxQuoteSkewSeconds,int maxOptionSpreadBps,
                         int maxFutureSpreadBps,long minimumOriginalOpenInterestLots,
                         int maximumIvPercent,int maximumVerifiedLots,
                         int minDteDays,int maxDteDays,
                         int minimumPutWingWidthPoints,
                         BigDecimal maximumAbsDeltaMismatch,
                         BigDecimal minimumPutOverCallIvVolPoints,
                         int minimumObservedIndexDownBps,
                         int buySlippageBps,int sellSlippageBps,
                         BigDecimal oneLegFeeInr,BigDecimal twoLegFeeInr,
                         BigDecimal maximumSpreadDebitInr) {}
    /**
     * All numerical scenario amounts are hypothetical common-index expiry
     * outcomes. They are NOT observed backtest outcomes or actual broker P&L.
     */
    public record Decision(Status status,String reasonCode,String strategyId,
                           Instant asOf,int originalCompletedSessions,
                           int brokerConfirmedLongFutureLots,
                           BigDecimal sourceOriginalPutCallIvSkewVolPoints,
                           BigDecimal worstObservedOriginalDailyIndexDropBps,
                           BigDecimal hypotheticalOutrightLongPutDebitInr,
                           BigDecimal hypotheticalPutSpreadDebitInr,
                           BigDecimal hypotheticalPremiumSavingsInr,
                           BigDecimal maximumPutSpreadIntrinsicProtectionInr,
                           BigDecimal conditionalStressedIndexSettlement,
                           BigDecimal conditionalUnhedgedFutureStressPnlInr,
                           BigDecimal conditionalPutSpreadFutureStressPnlInr,
                           BigDecimal conditionalPutSpreadFutureAtZeroPnlInr,
                           BigDecimal conditionalOutrightPutFutureAtZeroPnlInr,
                           int hypotheticalPutSpreadLots,
                           boolean originalIndexShockPersistsToFutureExpiryAssumption) {
        public boolean executable(){return false;}
        public boolean orderPlaced(){return false;}
        public boolean paperTradeAuthorized(){return false;}
        public boolean liveTradeAuthorized(){return false;}
    }

    public static Decision evaluate(BrokerLongFuture broker,FutureContract future,
                                    OptionContract higherBoughtPut,
                                    OptionContract lowerWrittenPut,
                                    OptionContract referenceLongCall,
                                    Snapshot snapshot,List<CompletedClose> history,
                                    Rights rights,Policy policy,Instant asOf) {
        Objects.requireNonNull(asOf,"asOf");
        if(rights==null||!rights.brokerConfirmedExistingLongFuture()
                ||!rights.licensedOriginalNiftyCloses()
                ||!rights.licensedListedNiftyOptionChain()
                ||!rights.licensedOriginalOptionIVAndDelta()
                ||!rights.licensedListedFutureMarket()
                ||!rights.licensedOfficialNseInstrumentMaster())
            return blocked(Status.BLOCKED_RIGHTS,"SKEW_ORIGINAL_SOURCE_RIGHTS_UNVERIFIED",asOf);

        if(!validPolicy(policy)
                ||!validFuture(broker,future,policy,asOf)
                ||!validOptions(higherBoughtPut,lowerWrittenPut,referenceLongCall,
                        future,snapshot,policy,asOf)
                ||!validSnapshot(snapshot,future,policy,asOf)
                ||broker.brokerFirstKnownAt().isAfter(snapshot.exchangeAt())
                ||!validHistory(history,snapshot,policy))
            return blocked(Status.BLOCKED_DATA,"SKEW_ORIGINAL_BROKER_OR_MARKET_INVALID",asOf);

        int lots=broker.verifiedLongLots();
        if(!validLeg(snapshot.longHigherPut(),higherBoughtPut,snapshot,policy,asOf)
                ||!validLeg(snapshot.shortLowerPut(),lowerWrittenPut,snapshot,policy,asOf)
                ||!validLeg(snapshot.referenceOtmCall(),referenceLongCall,snapshot,policy,asOf)
                ||snapshot.longHigherPut().originalAskDepthLots()<lots
                ||snapshot.shortLowerPut().originalBidDepthLots()<lots)
            return blocked(Status.BLOCKED_DATA,"SKEW_ORIGINAL_IV_OR_LIQUIDITY_INVALID",asOf);

        BigDecimal deltaDiff=snapshot.longHigherPut().originalDelta().abs()
                .subtract(snapshot.referenceOtmCall().originalDelta(),MC).abs();
        if(deltaDiff.compareTo(policy.maximumAbsDeltaMismatch())>0)
            return blocked(Status.BLOCKED_DATA,"SKEW_REFERENCE_DELTA_MISMATCH",asOf);

        BigDecimal volSkew=snapshot.longHigherPut().originalAnnualizedIvPercent()
                .subtract(snapshot.referenceOtmCall().originalAnnualizedIvPercent(),MC);
        if(volSkew.compareTo(policy.minimumPutOverCallIvVolPoints())<0)
            return measure(Status.NO_HEDGE,"SKEW_NOT_ABOVE_SOURCE_THRESHOLD",asOf,
                    history.size(),lots,volSkew,null,null,null,null,null,
                    null,null,null,null,null,0);

        BigDecimal worstReturn=BigDecimal.ZERO;
        for(int i=1;i<history.size();i++){
            BigDecimal r=history.get(i).close().divide(history.get(i-1).close(),MC)
                    .subtract(BigDecimal.ONE,MC);
            if(r.compareTo(worstReturn)<0)worstReturn=r;
        }
        BigDecimal observedDrop=worstReturn.negate().multiply(BPS,MC);
        if(observedDrop.compareTo(BigDecimal.valueOf(policy.minimumObservedIndexDownBps()))<0)
            return measure(Status.NO_HEDGE,"SKEW_NO_ORIGINAL_DOWNWARD_STRESS",asOf,
                    history.size(),lots,volSkew,observedDrop,null,null,null,null,
                    null,null,null,null,null,0);

        BigDecimal units=BigDecimal.valueOf((long)lots*future.officialLot());
        BigDecimal outrightDebit=effectiveBuy(snapshot.longHigherPut().ask(),
                policy.buySlippageBps()).multiply(units,MC).add(policy.oneLegFeeInr(),MC);
        BigDecimal spreadDebit=effectiveBuy(snapshot.longHigherPut().ask(),
                policy.buySlippageBps()).subtract(effectiveSell(snapshot.shortLowerPut().bid(),
                policy.sellSlippageBps()),MC).multiply(units,MC)
                .add(policy.twoLegFeeInr(),MC);
        BigDecimal savings=outrightDebit.subtract(spreadDebit,MC);
        BigDecimal width=higherBoughtPut.strike().subtract(lowerWrittenPut.strike(),MC)
                .multiply(units,MC);
        if(spreadDebit.signum()<=0||spreadDebit.compareTo(width)>=0
                ||spreadDebit.compareTo(policy.maximumSpreadDebitInr())>0
                ||savings.signum()<=0)
            return measure(Status.NO_HEDGE,"SKEW_SPREAD_NOT_AFFORDABLE_OR_NOT_SAVING_PREMIUM",
                    asOf,history.size(),lots,volSkew,observedDrop,outrightDebit,
                    spreadDebit,savings,width,null,null,null,null,null,0);

        // The worst historically OBSERVED daily NIFTY index return is assumed
        // (without evidence) to persist to the FUTURE/option common expiry.
        BigDecimal stressedIndex=snapshot.genuineIndexSpot()
                .multiply(BigDecimal.ONE.add(worstReturn,MC),MC);
        BigDecimal baseFuturePnl=stressedIndex
                .subtract(broker.brokerAverageEntryPriceInr(),MC).multiply(units,MC);
        BigDecimal spreadIntrinsic=intrinsic(higherBoughtPut,stressedIndex)
                .subtract(intrinsic(lowerWrittenPut,stressedIndex),MC)
                .multiply(units,MC);
        BigDecimal stressHedged=baseFuturePnl.add(spreadIntrinsic,MC)
                .subtract(spreadDebit,MC);
        // The modeled hedge must improve this stressed outcome *after*
        // premium/fees, not merely have high IV skew.
        if(stressHedged.compareTo(baseFuturePnl)<=0)
            return measure(Status.NO_HEDGE,"SKEW_NO_NET_STRESS_PROTECTION_AFTER_DEBIT",
                    asOf,history.size(),lots,volSkew,observedDrop,outrightDebit,
                    spreadDebit,savings,width,stressedIndex,baseFuturePnl,stressHedged,
                    null,null,0);

        BigDecimal futureAtIndexZero=broker.brokerAverageEntryPriceInr()
                .negate().multiply(units,MC);
        BigDecimal atZeroSpread=futureAtIndexZero.add(width,MC)
                .subtract(spreadDebit,MC);
        BigDecimal atZeroOutright=futureAtIndexZero.add(
                higherBoughtPut.strike().multiply(units,MC),MC)
                .subtract(outrightDebit,MC);
        if(atZeroSpread.compareTo(atZeroOutright)>=0)
            return blocked(Status.BLOCKED_DATA,"SKEW_TAIL_PROTECTION_INVARIANT",asOf);

        return measure(Status.RESEARCH_CANDIDATE,
                "SKEW_ORIGINAL_IV_SPREAD_HYPOTHETICAL_EXPIRY_ONLY",asOf,
                history.size(),lots,volSkew,observedDrop,outrightDebit,
                spreadDebit,savings,width,stressedIndex,baseFuturePnl,
                stressHedged,atZeroSpread,atZeroOutright,lots);
    }
    private static boolean validPolicy(Policy p){
        return p!=null&&between(p.minimumCompletedCloses(),35,252)
                &&between(p.maxQuoteAgeSeconds(),30,600)
                &&between(p.maxQuoteSkewSeconds(),0,120)
                &&between(p.maxOptionSpreadBps(),1,5000)
                &&between(p.maxFutureSpreadBps(),1,2000)
                &&p.minimumOriginalOpenInterestLots()>=1
                &&between(p.maximumIvPercent(),30,500)
                &&between(p.maximumVerifiedLots(),1,100)
                &&between(p.minDteDays(),3,30)
                &&between(p.maxDteDays(),7,90)
                &&p.minDteDays()<p.maxDteDays()
                &&between(p.minimumPutWingWidthPoints(),1,10000)
                &&positive(p.maximumAbsDeltaMismatch())
                &&p.maximumAbsDeltaMismatch().compareTo(BigDecimal.ONE)<0
                &&positive(p.minimumPutOverCallIvVolPoints())
                &&p.minimumPutOverCallIvVolPoints().compareTo(
                        BigDecimal.valueOf(100))<0
                &&between(p.minimumObservedIndexDownBps(),20,5000)
                &&between(p.buySlippageBps(),0,1000)
                &&between(p.sellSlippageBps(),0,1000)
                &&positive(p.oneLegFeeInr())&&positive(p.twoLegFeeInr())
                &&positive(p.maximumSpreadDebitInr());
    }
    private static boolean validFuture(BrokerLongFuture broker,FutureContract future,
                                       Policy p,Instant asOf){
        if(broker==null||future==null||!future.sourceListed()
                ||!key(future.key())||!INDEX.equals(future.index())
                ||future.listedFrom()==null||future.expiry()==null
                ||future.listedFrom().isAfter(asOf.atZone(IST).toLocalDate())
                ||future.officialLot()<=0
                ||broker.brokerFirstKnownAt()==null
                ||broker.brokerFirstKnownAt().isAfter(asOf)
                ||!broker.brokerVerified()
                ||!future.key().equals(broker.key())
                ||broker.verifiedLongLots()<1
                ||broker.verifiedLongLots()>p.maximumVerifiedLots()
                ||!positive(broker.brokerAverageEntryPriceInr()))return false;
        long dte=ChronoUnit.DAYS.between(asOf.atZone(IST).toLocalDate(),future.expiry());
        return dte>=p.minDteDays()&&dte<=p.maxDteDays();
    }
    private static boolean validOptions(OptionContract high,OptionContract low,
                                        OptionContract call,FutureContract future,
                                        Snapshot snapshot,Policy p,Instant asOf){
        if(snapshot==null||!positive(snapshot.genuineIndexSpot())
                ||!validOption(high,future,"PE",asOf)
                ||!validOption(low,future,"PE",asOf)
                ||!validOption(call,future,"CE",asOf)
                ||high.key().equals(low.key())||low.key().equals(call.key())
                ||high.key().equals(call.key()))return false;
        return low.strike().compareTo(high.strike())<0
                &&high.strike().subtract(low.strike(),MC)
                    .compareTo(BigDecimal.valueOf(p.minimumPutWingWidthPoints()))>=0
                &&high.strike().compareTo(snapshot.genuineIndexSpot())<0
                &&call.strike().compareTo(snapshot.genuineIndexSpot())>0;
    }
    private static boolean validOption(OptionContract opt,FutureContract future,
                                       String type,Instant now){
        return opt!=null&&opt.sourceListed()&&key(opt.key())
                &&INDEX.equals(opt.index())&&type.equals(opt.type())
                &&positive(opt.strike())&&positive(opt.tick())
                &&opt.strike().remainder(opt.tick()).signum()==0
                &&opt.officialLot()==future.officialLot()
                &&opt.listedFrom()!=null
                &&!opt.listedFrom().isAfter(now.atZone(IST).toLocalDate())
                &&future.expiry().equals(opt.expiry());
    }
    private static boolean validSnapshot(Snapshot s,FutureContract future,
                                         Policy p,Instant now){
        if(s==null||!s.sourceVerified()||!provider(s.provider())
                ||!INDEX.equals(s.indexKey())
                ||!future.key().equals(s.futureKey())
                ||!positive(s.genuineIndexSpot())
                ||s.exchangeAt()==null||s.firstKnownAt()==null
                ||s.firstKnownAt().isBefore(s.exchangeAt())
                ||s.firstKnownAt().isAfter(now)
                ||Duration.between(s.exchangeAt(),now).compareTo(
                        Duration.ofSeconds(p.maxQuoteAgeSeconds()))>0
                ||!spread(s.genuineFutureBid(),s.genuineFutureAsk(),
                        p.maxFutureSpreadBps()))return false;
        var z=s.exchangeAt().atZone(IST);
        return z.getDayOfWeek().getValue()<=5
                &&!z.toLocalTime().isBefore(OPEN)
                &&!z.toLocalTime().isAfter(CLOSE);
    }
    private static boolean validLeg(Quote q,OptionContract opt,Snapshot s,
                                    Policy p,Instant now){
        if(q==null||!q.sourceVerified()
                ||!opt.key().equals(q.key())||!s.provider().equals(q.provider())
                ||!spread(q.bid(),q.ask(),p.maxOptionSpreadBps())
                ||q.originalDelta()==null
                ||!positive(q.originalAnnualizedIvPercent())
                ||q.originalAnnualizedIvPercent().compareTo(
                        BigDecimal.valueOf(p.maximumIvPercent()))>0
                ||q.originalOpenInterestLots()<p.minimumOriginalOpenInterestLots()
                ||q.exchangeAt()==null||q.firstKnownAt()==null
                ||q.firstKnownAt().isBefore(q.exchangeAt())
                ||q.firstKnownAt().isAfter(s.firstKnownAt())
                ||q.firstKnownAt().isAfter(now)
                ||Duration.between(q.exchangeAt(),now).compareTo(
                        Duration.ofSeconds(p.maxQuoteAgeSeconds()))>0
                ||Math.abs(Duration.between(q.exchangeAt(),s.exchangeAt()).toSeconds())
                        >p.maxQuoteSkewSeconds())return false;
        BigDecimal d=q.originalDelta();
        return "CE".equals(opt.type())
                ?d.signum()>0&&d.compareTo(BigDecimal.ONE)<0
                :d.signum()<0&&d.compareTo(BigDecimal.ONE.negate())>0;
    }
    private static boolean validHistory(List<CompletedClose> history,Snapshot s,Policy p){
        if(history==null||history.size()<p.minimumCompletedCloses()
                ||history.size()>512)return false;
        LocalDate previous=null;
        Set<LocalDate> seen=new HashSet<>();
        for(CompletedClose bar:history){
            if(bar==null||!bar.sourceVerified()||bar.session()==null
                    ||bar.session().getDayOfWeek().getValue()>5
                    ||!seen.add(bar.session())
                    ||!s.provider().equals(bar.provider())||!positive(bar.close())
                    ||bar.exchangeCloseAt()==null||bar.firstKnownAt()==null)return false;
            Instant close=bar.session().atTime(CLOSE).atZone(IST).toInstant();
            if(!close.equals(bar.exchangeCloseAt())
                    ||bar.firstKnownAt().isBefore(close)
                    ||bar.firstKnownAt().isAfter(close.plus(Duration.ofHours(36)))
                    ||!bar.firstKnownAt().isBefore(s.exchangeAt())
                    ||previous!=null&&(ChronoUnit.DAYS.between(previous,bar.session())<1
                    ||ChronoUnit.DAYS.between(previous,bar.session())>8))return false;
            previous=bar.session();
        }
        long lag=ChronoUnit.DAYS.between(previous,s.exchangeAt().atZone(IST).toLocalDate());
        return lag>=1&&lag<=5;
    }
    private static boolean spread(BigDecimal bid,BigDecimal ask,int maximumBps){
        if(!positive(bid)||!positive(ask)||ask.compareTo(bid)<0)return false;
        BigDecimal mid=bid.add(ask,MC).divide(BigDecimal.valueOf(2),MC);
        return ask.subtract(bid,MC).multiply(BPS,MC).divide(mid,MC)
                .compareTo(BigDecimal.valueOf(maximumBps))<=0;
    }
    private static BigDecimal effectiveBuy(BigDecimal ask,int bps){
        return ask.multiply(BPS.add(BigDecimal.valueOf(bps),MC).divide(BPS,MC),MC);
    }
    private static BigDecimal effectiveSell(BigDecimal bid,int bps){
        return bid.multiply(BPS.subtract(BigDecimal.valueOf(bps),MC).divide(BPS,MC),MC);
    }
    private static BigDecimal intrinsic(OptionContract put,BigDecimal settlement){
        return put.strike().subtract(settlement,MC).max(BigDecimal.ZERO);
    }
    private static boolean provider(String p){
        return "UPSTOX_ANALYTICS".equals(p)||"ANGEL_ONE_SMARTAPI".equals(p);
    }
    private static boolean key(String k){
        return k!=null&&k.startsWith("NSE_FO|")
                &&k.substring(7).matches("[A-Za-z0-9._-]{1,64}");
    }
    private static boolean positive(BigDecimal v){return v!=null&&v.signum()>0;}
    private static boolean between(int n,int a,int b){return n>=a&&n<=b;}
    private static BigDecimal rounded(BigDecimal x){
        return x==null?null:x.setScale(4,RoundingMode.HALF_UP);
    }
    private static Decision blocked(Status status,String reason,Instant asOf){
        return measure(status,reason,asOf,0,0,null,null,null,null,null,
                null,null,null,null,null,null,0);
    }
    private static Decision measure(Status status,String reason,Instant asOf,
                                    int sessions,int existingLots,BigDecimal skew,
                                    BigDecimal down,BigDecimal outright,BigDecimal spread,
                                    BigDecimal savings,BigDecimal maxIntrinsic,
                                    BigDecimal shocked,BigDecimal unhedged,
                                    BigDecimal hedged,BigDecimal atZeroSpread,
                                    BigDecimal atZeroOutright,int chosenLots){
        return new Decision(status,reason,STRATEGY_ID,asOf,sessions,existingLots,
                rounded(skew),rounded(down),rounded(outright),
                rounded(spread),rounded(savings),rounded(maxIntrinsic),
                rounded(shocked),rounded(unhedged),rounded(hedged),
                rounded(atZeroSpread),rounded(atZeroOutright),
                chosenLots,true);
    }
}
