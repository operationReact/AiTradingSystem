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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * STR-211 (42/43): research-only Greeks exposure diagnostic for an
 * independently attested COMPLETE broker-held NIFTY options book.
 *
 * Model BUYING one existing-source-listed put or call at genuine ASK to reduce
 * the book's ABS(delta) while enforcing absolute gamma, vega and negative-theta
 * budgets. Does NOT claim to neutralize all Greeks, optimize realized P&L,
 * cover short option margin, or place a paper/live order.
 */
public final class FnoGreeksExposureRebalanceV1 {
    public static final String STRATEGY_ID="strategy.STR-211";
    public static final String INDEX="NSE_INDEX|Nifty 50";
    private static final MathContext MC=MathContext.DECIMAL128;
    private static final BigDecimal BPS=BigDecimal.valueOf(10000);
    private static final ZoneId IST=ZoneId.of("Asia/Kolkata");
    private static final LocalTime OPEN=LocalTime.of(9,15);
    private static final LocalTime CLOSE=LocalTime.of(15,30);

    private FnoGreeksExposureRebalanceV1() {}
    public enum Status {BLOCKED_RIGHTS,BLOCKED_DATA,NO_REBALANCE,RESEARCH_CANDIDATE}
    public record Rights(boolean brokerCompleteOptionsBookResearch,
                         boolean licensedOriginalNiftyCloses,
                         boolean licensedSourceListedOptions,
                         boolean licensedOriginalDeltaGamma,
                         boolean licensedOriginalVegaThetaIv,
                         boolean licensedOfficialInstrumentMaster) {}
    public record Contract(String key,String index,String type,BigDecimal strike,
                           BigDecimal tick,LocalDate listedFrom,LocalDate expiry,
                           int officialLot,boolean sourceListed) {}
    /** Signed contracts: + LONG, - SHORT, never a hypothetical opening order. */
    public record Position(Contract contract,int signedBrokerLots,
                           Instant brokerFirstKnownAt,boolean brokerVerified) {}
    public record BrokerBook(boolean brokerVerifiedCompleteNiftyOptionsBook,
                             Instant bookFirstKnownAt,List<Position> positions) {}
    /**
     * Delta: underlying-index units / underlying unit.
     * Gamma: delta change / one index point / underlying unit.
     * Vega: option premium INR / IV percentage point / underlying unit.
     * Theta: option premium INR / calendar day / underlying unit.
     */
    public record Quote(String key,String provider,BigDecimal bid,BigDecimal ask,
                        BigDecimal originalDelta,BigDecimal originalGamma,
                        BigDecimal originalVegaPerVolPoint,
                        BigDecimal originalThetaPerCalendarDay,
                        BigDecimal originalAnnualizedIvPercent,long openInterestLots,
                        long bidDepthLots,long askDepthLots,
                        Instant exchangeAt,Instant firstKnownAt,boolean sourceVerified) {}
    public record CompletedClose(LocalDate session,String provider,
                                 BigDecimal indexClose,Instant exchangeCloseAt,
                                 Instant firstKnownAt,boolean sourceVerified) {}
    public record Snapshot(String provider,String indexKey,BigDecimal indexSpot,
                           List<Quote> quotes,Instant exchangeAt,
                           Instant firstKnownAt,boolean sourceVerified) {}
    public record Policy(int minimumCompletedCloses,int maxQuoteAgeSeconds,
                         int maxSkewSeconds,int maxSpreadBps,long minOpenInterestLots,
                         int maxOriginalAnnualizedIvPercent,int maxExistingContracts,
                         int maxAbsExistingLotsPerContract,int maxBuyLots,
                         int minObservedAbsoluteIndexMoveBps,
                         BigDecimal minInitialAbsDeltaUnits,
                         BigDecimal maxResidualAbsDeltaUnits,
                         BigDecimal maxResidualAbsGammaUnitsPerPoint,
                         BigDecimal maxResidualAbsVegaInrPerIvPoint,
                         BigDecimal maxNegativeThetaInrPerDay,
                         int minAbsDeltaReductionBps,int buySlippageBps,
                         BigDecimal modeledPurchaseFeeInr,
                         BigDecimal maxPurchaseDebitInr) {}
    /**
     * This is solely original-snapshot option-Greeks arithmetic. No execution,
     * option repricing, short risk offset assurance, or actual P&L claims.
     */
    public record Decision(Status status,String reasonCode,String strategyId,Instant asOf,
                           int completedOriginalCloses,int verifiedExistingContractCount,
                           BigDecimal observedLargestAbsIndexDailyMoveBps,
                           BigDecimal originalNetDeltaUnits,
                           BigDecimal originalNetGammaUnitsPerIndexPoint,
                           BigDecimal originalNetVegaInrPerIvPoint,
                           BigDecimal originalNetThetaInrPerCalendarDay,
                           String hypotheticalBuyOptionKey,int hypotheticalLongBuyLots,
                           BigDecimal hypotheticalBuyPremiumAndFeesInr,
                           BigDecimal hypotheticalResidualDeltaUnits,
                           BigDecimal hypotheticalResidualGammaUnitsPerIndexPoint,
                           BigDecimal hypotheticalResidualVegaInrPerIvPoint,
                           BigDecimal hypotheticalResidualThetaInrPerCalendarDay,
                           BigDecimal hypotheticalAbsDeltaReductionBps,
                           boolean currentGreekLinearizationOnly) {
        public boolean executable(){return false;}
        public boolean orderPlaced(){return false;}
        public boolean paperTradeAuthorized(){return false;}
        public boolean liveTradeAuthorized(){return false;}
    }
    private record GreekVector(BigDecimal delta,BigDecimal gamma,
                               BigDecimal vega,BigDecimal theta) {
        private GreekVector add(GreekVector rhs){
            return new GreekVector(delta.add(rhs.delta,MC),gamma.add(rhs.gamma,MC),
                    vega.add(rhs.vega,MC),theta.add(rhs.theta,MC));
        }
        private GreekVector scale(BigDecimal units){
            return new GreekVector(delta.multiply(units,MC),gamma.multiply(units,MC),
                    vega.multiply(units,MC),theta.multiply(units,MC));
        }
    }
    private static final GreekVector ZERO=new GreekVector(
            BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO);

    public static Decision evaluate(BrokerBook book,List<Contract> buyCandidates,
                                    Snapshot snapshot,List<CompletedClose> history,
                                    Rights rights,Policy policy,Instant asOf) {
        Objects.requireNonNull(asOf,"asOf");
        if(rights==null||!rights.brokerCompleteOptionsBookResearch()
                ||!rights.licensedOriginalNiftyCloses()
                ||!rights.licensedSourceListedOptions()
                ||!rights.licensedOriginalDeltaGamma()
                ||!rights.licensedOriginalVegaThetaIv()
                ||!rights.licensedOfficialInstrumentMaster())
            return blocked(Status.BLOCKED_RIGHTS,"GREEKS_SOURCE_RIGHTS_UNVERIFIED",asOf);

        if(!validPolicy(policy)||!validSnapshot(snapshot,policy,asOf)
                ||!validBrokerBook(book,snapshot,policy)
                ||!validCandidates(buyCandidates,snapshot,policy,asOf)
                ||!validHistory(history,snapshot,policy))
            return blocked(Status.BLOCKED_DATA,"GREEKS_ORIGINAL_BOOK_OR_HISTORY_INVALID",asOf);

        Map<String,Quote> quotes=new HashMap<>();
        for(Quote q:snapshot.quotes()){
            if(q==null||quotes.putIfAbsent(q.key(),q)!=null)
                return blocked(Status.BLOCKED_DATA,"GREEKS_NULL_OR_DUPLICATE_QUOTE",asOf);
        }
        Set<String> requiredKeys=new HashSet<>();
        for(Position p:book.positions())requiredKeys.add(p.contract().key());
        for(Contract c:buyCandidates)requiredKeys.add(c.key());
        if(!quotes.keySet().equals(requiredKeys))
            return blocked(Status.BLOCKED_DATA,"GREEKS_INCOMPLETE_OR_EXTRA_QUOTES",asOf);

        GreekVector current=ZERO;
        for(Position p:book.positions()){
            Quote q=quotes.get(p.contract().key());
            if(!validQuote(q,p.contract(),snapshot,policy,asOf))
                return blocked(Status.BLOCKED_DATA,"GREEKS_EXISTING_ORIGINAL_QUOTE_INVALID",asOf);
            BigDecimal units=BigDecimal.valueOf(
                    (long)p.signedBrokerLots()*p.contract().officialLot());
            current=current.add(greeks(q).scale(units));
        }
        for(Contract c:buyCandidates){
            Quote q=quotes.get(c.key());
            if(!validQuote(q,c,snapshot,policy,asOf)
                    ||q.askDepthLots()<1)
                return blocked(Status.BLOCKED_DATA,"GREEKS_BUY_ORIGINAL_QUOTE_INVALID",asOf);
        }

        BigDecimal move=BigDecimal.ZERO;
        for(int i=1;i<history.size();i++){
            BigDecimal d=history.get(i).indexClose()
                    .divide(history.get(i-1).indexClose(),MC)
                    .subtract(BigDecimal.ONE,MC).abs().multiply(BPS,MC);
            move=move.max(d);
        }
        if(move.compareTo(BigDecimal.valueOf(policy.minObservedAbsoluteIndexMoveBps()))<0)
            return measure(Status.NO_REBALANCE,"GREEKS_NO_ORIGINAL_MARKET_MOVEMENT",
                    asOf,history.size(),book.positions().size(),move,current,
                    null,0,null,null,null);
        if(current.delta().abs().compareTo(policy.minInitialAbsDeltaUnits())<0)
            return measure(Status.NO_REBALANCE,"GREEKS_INITIAL_DELTA_WITHIN_TOLERANCE",
                    asOf,history.size(),book.positions().size(),move,current,
                    null,0,null,null,null);

        Contract selected=null;
        int selectedLots=0;
        BigDecimal selectedCost=null;
        GreekVector selectedResidual=null;
        BigDecimal bestAbs=current.delta().abs();
        for(Contract candidate:buyCandidates){
            Quote quote=quotes.get(candidate.key());
            int maximum=(int)Math.min((long)policy.maxBuyLots(),quote.askDepthLots());
            BigDecimal unitDebit=quote.ask()
                    .multiply(BPS.add(BigDecimal.valueOf(policy.buySlippageBps()))
                            .divide(BPS,MC),MC)
                    .multiply(BigDecimal.valueOf(candidate.officialLot()),MC);
            GreekVector oneLot=greeks(quote).scale(BigDecimal.valueOf(candidate.officialLot()));
            for(int n=1;n<=maximum;n++){
                BigDecimal premium=unitDebit.multiply(BigDecimal.valueOf(n),MC)
                        .add(policy.modeledPurchaseFeeInr(),MC);
                if(premium.compareTo(policy.maxPurchaseDebitInr())>0)break;
                GreekVector next=current.add(oneLot.scale(BigDecimal.valueOf(n)));
                // Never call an oversized gamma/vega/theta problem "rebalanced".
                if(next.delta().abs().compareTo(policy.maxResidualAbsDeltaUnits())>0
                        ||next.gamma().abs().compareTo(
                            policy.maxResidualAbsGammaUnitsPerPoint())>0
                        ||next.vega().abs().compareTo(
                            policy.maxResidualAbsVegaInrPerIvPoint())>0
                        ||next.theta().negate().max(BigDecimal.ZERO)
                            .compareTo(policy.maxNegativeThetaInrPerDay())>0)
                    continue;
                // Do not reverse the original delta sign; crossing through
                // zero can be a hedge, but model this conservatively as not
                // opening an opposite-direction exposure.
                if(current.delta().signum()!=0 && next.delta().signum()!=0
                        && current.delta().signum()!=next.delta().signum())continue;
                BigDecimal abs=next.delta().abs();
                if(abs.compareTo(bestAbs)<0 || (selected!=null && abs.compareTo(bestAbs)==0
                        && premium.compareTo(selectedCost)<0)){
                    selected=candidate;
                    selectedLots=n;
                    selectedCost=premium;
                    selectedResidual=next;
                    bestAbs=abs;
                }
            }
        }
        if(selected==null)
            return measure(Status.NO_REBALANCE,"GREEKS_NO_AFFORDABLE_SAFE_DELTA_IMPROVEMENT",
                    asOf,history.size(),book.positions().size(),move,current,
                    null,0,null,null,null);
        BigDecimal reduction=current.delta().abs().subtract(bestAbs,MC)
                .multiply(BPS,MC).divide(current.delta().abs(),MC);
        if(reduction.compareTo(BigDecimal.valueOf(policy.minAbsDeltaReductionBps()))<0)
            return measure(Status.NO_REBALANCE,"GREEKS_DELTA_REDUCTION_TOO_SMALL",
                    asOf,history.size(),book.positions().size(),move,current,
                    null,0,null,null,null);
        return measure(Status.RESEARCH_CANDIDATE,"GREEKS_HYPOTHETICAL_BUY_ONLY_REBALANCE",
                asOf,history.size(),book.positions().size(),move,current,
                selected.key(),selectedLots,selectedCost,selectedResidual,reduction);
    }

    private static boolean validPolicy(Policy p){
        return p!=null&&between(p.minimumCompletedCloses(),35,252)
                &&between(p.maxQuoteAgeSeconds(),30,600)
                &&between(p.maxSkewSeconds(),0,120)
                &&between(p.maxSpreadBps(),1,5000)
                &&p.minOpenInterestLots()>=1
                &&between(p.maxOriginalAnnualizedIvPercent(),30,1000)
                &&between(p.maxExistingContracts(),1,30)
                &&between(p.maxAbsExistingLotsPerContract(),1,500)
                &&between(p.maxBuyLots(),1,100)
                &&between(p.minObservedAbsoluteIndexMoveBps(),1,3000)
                &&positive(p.minInitialAbsDeltaUnits())
                &&positive(p.maxResidualAbsDeltaUnits())
                &&positive(p.maxResidualAbsGammaUnitsPerPoint())
                &&positive(p.maxResidualAbsVegaInrPerIvPoint())
                &&positive(p.maxNegativeThetaInrPerDay())
                &&between(p.minAbsDeltaReductionBps(),1,9900)
                &&between(p.buySlippageBps(),0,1000)
                &&positive(p.modeledPurchaseFeeInr())
                &&positive(p.maxPurchaseDebitInr());
    }
    private static boolean validSnapshot(Snapshot s,Policy p,Instant now){
        if(s==null||!s.sourceVerified()||!provider(s.provider())
                ||!INDEX.equals(s.indexKey())||!positive(s.indexSpot())
                ||s.exchangeAt()==null||s.firstKnownAt()==null
                ||s.firstKnownAt().isBefore(s.exchangeAt())
                ||s.firstKnownAt().isAfter(now)
                ||Duration.between(s.exchangeAt(),now).compareTo(
                        Duration.ofSeconds(p.maxQuoteAgeSeconds()))>0
                ||s.quotes()==null||s.quotes().isEmpty())return false;
        var exchange=s.exchangeAt().atZone(IST);
        return exchange.getDayOfWeek().getValue()<=5
                &&!exchange.toLocalTime().isBefore(OPEN)
                &&!exchange.toLocalTime().isAfter(CLOSE);
    }
    private static boolean validBrokerBook(BrokerBook book,Snapshot snapshot,Policy p){
        if(book==null||!book.brokerVerifiedCompleteNiftyOptionsBook()
                ||book.bookFirstKnownAt()==null||book.positions()==null
                ||book.positions().isEmpty()
                ||book.positions().size()>p.maxExistingContracts()
                ||book.bookFirstKnownAt().isAfter(snapshot.exchangeAt()))return false;
        Set<String> keys=new HashSet<>();
        for(Position pos:book.positions()){
            if(pos==null||pos.contract()==null||!pos.brokerVerified()
                    ||!validContract(pos.contract(),snapshot.exchangeAt())
                    ||!keys.add(pos.contract().key())
                    ||pos.signedBrokerLots()==0||pos.signedBrokerLots()==Integer.MIN_VALUE
                    ||Math.abs(pos.signedBrokerLots())>p.maxAbsExistingLotsPerContract()
                    ||pos.brokerFirstKnownAt()==null
                    ||pos.brokerFirstKnownAt().isAfter(book.bookFirstKnownAt()))
                return false;
        }
        return true;
    }
    private static boolean validCandidates(List<Contract> candidates,Snapshot s,
                                           Policy p,Instant now){
        if(candidates==null||candidates.size()!=2
                ||candidates.get(0)==null||candidates.get(1)==null)return false;
        Set<String> keys=new HashSet<>();
        for(Contract c:candidates){
            if(!validContract(c,now)||!keys.add(c.key())
                    ||ChronoUnit.DAYS.between(now.atZone(IST).toLocalDate(),c.expiry())<7
                    ||ChronoUnit.DAYS.between(now.atZone(IST).toLocalDate(),c.expiry())>45
                    ||("PE".equals(c.type())&&c.strike().compareTo(s.indexSpot())>=0)
                    ||("CE".equals(c.type())&&c.strike().compareTo(s.indexSpot())<=0))
                return false;
        }
        Contract first=candidates.get(0),second=candidates.get(1);
        return (("PE".equals(first.type()) && "CE".equals(second.type()))
                || ("CE".equals(first.type()) && "PE".equals(second.type())))
                && first.expiry().equals(second.expiry())
                && first.officialLot()==second.officialLot();
    }
    private static boolean validContract(Contract c,Instant now){
        return c!=null && c.sourceListed() && key(c.key())
                && INDEX.equals(c.index())
                && ("CE".equals(c.type()) || "PE".equals(c.type()))
                &&positive(c.strike())&&positive(c.tick())
                &&c.strike().remainder(c.tick()).signum()==0
                &&c.listedFrom()!=null
                &&!c.listedFrom().isAfter(now.atZone(IST).toLocalDate())
                &&c.expiry()!=null
                &&c.expiry().isAfter(now.atZone(IST).toLocalDate())
                &&c.officialLot()>0;
    }
    private static boolean validQuote(Quote q,Contract c,Snapshot s,
                                      Policy p,Instant at){
        if(q==null||!q.sourceVerified()||!c.key().equals(q.key())
                ||!s.provider().equals(q.provider())
                ||!positive(q.bid())||!positive(q.ask())
                ||q.ask().compareTo(q.bid())<0
                ||q.originalDelta()==null||q.originalGamma()==null
                ||q.originalVegaPerVolPoint()==null
                ||q.originalThetaPerCalendarDay()==null
                ||q.originalAnnualizedIvPercent()==null
                ||!positive(q.originalGamma())
                ||!positive(q.originalVegaPerVolPoint())
                ||q.originalThetaPerCalendarDay().signum()>=0
                ||!positive(q.originalAnnualizedIvPercent())
                ||q.originalAnnualizedIvPercent().compareTo(
                        BigDecimal.valueOf(p.maxOriginalAnnualizedIvPercent()))>0
                ||q.openInterestLots()<p.minOpenInterestLots()
                ||q.exchangeAt()==null||q.firstKnownAt()==null
                ||q.firstKnownAt().isBefore(q.exchangeAt())
                ||q.firstKnownAt().isAfter(s.firstKnownAt())
                ||q.firstKnownAt().isAfter(at)
                ||Duration.between(q.exchangeAt(),at).compareTo(
                        Duration.ofSeconds(p.maxQuoteAgeSeconds()))>0
                ||Math.abs(Duration.between(q.exchangeAt(),s.exchangeAt()).toSeconds())
                        >p.maxSkewSeconds())return false;
        BigDecimal delta=q.originalDelta();
        if(("CE".equals(c.type())&&(delta.signum()<=0
                ||delta.compareTo(BigDecimal.ONE)>=0))
                ||("PE".equals(c.type())&&(delta.signum()>=0
                ||delta.compareTo(BigDecimal.ONE.negate())<=0)))return false;
        BigDecimal mid=q.ask().add(q.bid(),MC).divide(BigDecimal.valueOf(2),MC);
        return q.ask().subtract(q.bid(),MC).multiply(BPS,MC).divide(mid,MC)
                .compareTo(BigDecimal.valueOf(p.maxSpreadBps()))<=0;
    }
    private static boolean validHistory(List<CompletedClose> history,Snapshot s,Policy p){
        if(history==null||history.size()<p.minimumCompletedCloses()
                ||history.size()>512)return false;
        LocalDate previous=null;
        for(CompletedClose bar:history){
            if(bar==null||!bar.sourceVerified()||bar.session()==null
                    ||bar.session().getDayOfWeek().getValue()>5
                    ||!s.provider().equals(bar.provider())
                    ||!positive(bar.indexClose())
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
    private static GreekVector greeks(Quote q){
        return new GreekVector(q.originalDelta(),q.originalGamma(),
                q.originalVegaPerVolPoint(),q.originalThetaPerCalendarDay());
    }
    private static boolean provider(String p){
        return "UPSTOX_ANALYTICS".equals(p)||"ANGEL_ONE_SMARTAPI".equals(p);
    }
    private static boolean key(String key){
        return key!=null&&key.startsWith("NSE_FO|")
                &&key.substring(7).matches("[A-Za-z0-9._-]{1,64}");
    }
    private static boolean positive(BigDecimal b){return b!=null&&b.signum()>0;}
    private static boolean between(int n,int lo,int hi){return n>=lo&&n<=hi;}
    private static BigDecimal rounded(BigDecimal b){
        return b==null?null:b.setScale(4,RoundingMode.HALF_UP);
    }
    private static Decision blocked(Status status,String code,Instant at){
        return measure(status,code,at,0,0,null,ZERO,null,0,null,null,null);
    }
    private static Decision measure(Status status,String code,Instant at,
                                    int closes,int count,BigDecimal observed,
                                    GreekVector current,String key,int lots,
                                    BigDecimal debit,GreekVector residual,
                                    BigDecimal improvement){
        return new Decision(status,code,STRATEGY_ID,at,closes,count,
                rounded(observed),rounded(current.delta()),rounded(current.gamma()),
                rounded(current.vega()),rounded(current.theta()),
                key,lots,rounded(debit),
                residual==null?null:rounded(residual.delta()),
                residual==null?null:rounded(residual.gamma()),
                residual==null?null:rounded(residual.vega()),
                residual==null?null:rounded(residual.theta()),
                rounded(improvement),true);
    }
}
