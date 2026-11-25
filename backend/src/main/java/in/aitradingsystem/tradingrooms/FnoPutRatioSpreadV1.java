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
import java.util.List;
import java.util.Objects;

/**
 * STR-191: research-only 1x2 put ratio spread.
 * Long one higher-strike PE; short TWO lower-strike PE lots.
 * Extra short put has severe downside exposure but the equity index cannot
 * fall below zero, so the expiry loss is finite and analytically bounded.
 * The maximum loss is not a guarantee about liquidations or broker fills.
 */
public final class FnoPutRatioSpreadV1 {
    public static final String STRATEGY_ID = "strategy.STR-191";
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal BPS = new BigDecimal("10000");
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime CLOSE = LocalTime.of(15, 30);
    private static final Duration MAX_AGE = Duration.ofSeconds(180);
    private static final Duration MAX_SKEW = Duration.ofSeconds(90);
    private static final Duration MAX_PUBLISHED_LAG = Duration.ofHours(36);
    private FnoPutRatioSpreadV1() {}

    public enum Status { BLOCKED_RIGHTS, BLOCKED_DATA, NO_SIGNAL, RESEARCH_CANDIDATE }

    public record Contract(String key, String underlying, String type, BigDecimal strike,
                           BigDecimal tick, LocalDate listedFrom, LocalDate expiry,
                           int lotSize, boolean verified) {}
    public record Bar(LocalDate session, String provider, BigDecimal close,
                      Instant exchangeCloseAt, Instant firstKnownAt, boolean verified) {}
    public record Quote(String provider, String key, BigDecimal bid, BigDecimal ask,
                        long bidDepthLots, long askDepthLots, long oiLots,
                        Instant exchangeAt, Instant firstKnownAt) {}
    public record Rights(boolean indexResearch, boolean optionChainResearch,
                         boolean instrumentResearch) {}
    /** Stress is illustrative; true worst expiry loss is separately computed at S=0 or S>=Klong. */
    public record Assumptions(int buySlippageBps, int sellSlippageBps,
                              int maxOptionSpreadBps, long minBidAskDepthLots,
                              long minOiLots, int maxStrikeDistanceBps,
                              BigDecimal totalFeesPerRatioLotInr,
                              BigDecimal researchCapitalInr, int maxLossAllocationBps,
                              BigDecimal downsideStressUnderlyingPrice,
                              int minPeakRewardBpsOfMaxLoss) {}
    public record Decision(Status status, String reasonCode, String strategyId,
                           String longPutKey, String shortPutKey, int longLots,
                           int shortLots, Instant asOf,
                           BigDecimal netDebitPerUnit,
                           BigDecimal expiryPeakProfitPerRatioLotInr,
                           BigDecimal hypotheticalDownsideStressPnlInr,
                           BigDecimal theoreticalMaximumExpiryLossInr,
                           boolean unlimitedTheoreticalDownsideLoss) {
        public boolean executable() { return false; }
        public boolean orderPlaced() { return false; }
        public boolean liveTradeAuthorized() { return false; }
    }

    public static Decision evaluate(Contract higherLongPut, Contract lowerShortPut,
                                    List<Bar> completedDailyIndexCloses, Quote index,
                                    Quote longPut, Quote shortPut, Rights rights,
                                    Assumptions a, Instant asOf) {
        Objects.requireNonNull(asOf, "asOf");
        if (rights == null || !rights.indexResearch() || !rights.optionChainResearch()
                || !rights.instrumentResearch())
            return blocked(Status.BLOCKED_RIGHTS, "PUT_RATIO_RIGHTS_MISSING", asOf);
        if (!contractsValid(higherLongPut, lowerShortPut, asOf) || !assumptionsValid(a))
            return blocked(Status.BLOCKED_DATA, "PUT_RATIO_CONTRACT_OR_COST_INVALID", asOf);
        if (!historyValid(completedDailyIndexCloses, asOf))
            return blocked(Status.BLOCKED_DATA, "PUT_RATIO_HISTORY_INVALID", asOf);
        if (!quotesValid(higherLongPut, lowerShortPut, completedDailyIndexCloses.getLast(),
                index, longPut, shortPut, a, asOf))
            return blocked(Status.BLOCKED_DATA, "PUT_RATIO_QUOTES_OR_ASOF_INVALID", asOf);

        var bars = new ArrayList<FnoLongOptionCrossoverV1.CompletedBar>();
        for (var b : completedDailyIndexCloses)
            bars.add(new FnoLongOptionCrossoverV1.CompletedBar(
                b.exchangeCloseAt(), b.firstKnownAt(), b.close()));
        var crossover = FnoLongOptionCrossoverV1.evaluate(
            FnoLongOptionCrossoverV1.UNDERLYING, true, bars, asOf);
        if (crossover.status() == FnoLongOptionCrossoverV1.Status.BLOCKED_DATA)
            return blocked(Status.BLOCKED_DATA, "PUT_RATIO_TREND_INPUT_INVALID", asOf);
        if (crossover.status() != FnoLongOptionCrossoverV1.Status.RESEARCH_CANDIDATE
                || !"strategy.STR-182".equals(crossover.strategyId()))
            return measured(Status.NO_SIGNAL, "PUT_RATIO_NEW_BEARISH_CROSSOVER_ABSENT",
                higherLongPut, lowerShortPut, asOf, null, null, null, null);

        BigDecimal longBuy = longPut.ask().multiply(
            BPS.add(BigDecimal.valueOf(a.buySlippageBps()), MC), MC).divide(BPS, MC);
        BigDecimal shortSell = shortPut.bid().multiply(
            BPS.subtract(BigDecimal.valueOf(a.sellSlippageBps()), MC), MC).divide(BPS, MC);
        BigDecimal netDebit = longBuy.subtract(shortSell.multiply(BigDecimal.valueOf(2), MC), MC);
        BigDecimal lot = BigDecimal.valueOf(higherLongPut.lotSize());
        BigDecimal netCost = netDebit.multiply(lot, MC).add(a.totalFeesPerRatioLotInr(), MC);
        BigDecimal peak = payoffAt(lowerShortPut.strike(), higherLongPut,
            lowerShortPut, lot, netCost);
        BigDecimal zeroSpotPnl = payoffAt(BigDecimal.ZERO, higherLongPut,
            lowerShortPut, lot, netCost);
        BigDecimal aboveLongStrikePnl = netCost.negate();
        BigDecimal trueWorstExpiryPnl = zeroSpotPnl.min(aboveLongStrikePnl);
        BigDecimal trueMaximumLoss = trueWorstExpiryPnl.min(BigDecimal.ZERO).negate();
        BigDecimal stressPnl = payoffAt(a.downsideStressUnderlyingPrice(),
            higherLongPut, lowerShortPut, lot, netCost);
        BigDecimal allowedMaximumLoss = a.researchCapitalInr()
            .multiply(BigDecimal.valueOf(a.maxLossAllocationBps()), MC)
            .divide(BPS, MC);

        if (peak.signum() <= 0)
            return measured(Status.NO_SIGNAL, "PUT_RATIO_PEAK_PROFIT_NOT_POSITIVE",
                higherLongPut, lowerShortPut, asOf, netDebit, peak, stressPnl, trueMaximumLoss);
        if (a.downsideStressUnderlyingPrice().compareTo(lowerShortPut.strike()) >= 0)
            return measured(Status.NO_SIGNAL, "PUT_RATIO_DOWNSIDE_STRESS_NOT_BELOW_SHORT_STRIKE",
                higherLongPut, lowerShortPut, asOf, netDebit, peak, stressPnl, trueMaximumLoss);
        if (trueMaximumLoss.compareTo(allowedMaximumLoss) > 0)
            return measured(Status.NO_SIGNAL, "PUT_RATIO_MAXIMUM_LOSS_BUDGET_EXCEEDED",
                higherLongPut, lowerShortPut, asOf, netDebit, peak, stressPnl, trueMaximumLoss);
        if (trueMaximumLoss.signum() == 0 || peak.multiply(BPS, MC).divide(trueMaximumLoss, MC)
                .compareTo(BigDecimal.valueOf(a.minPeakRewardBpsOfMaxLoss())) < 0)
            return measured(Status.NO_SIGNAL, "PUT_RATIO_MAXIMUM_LOSS_REWARD_INADEQUATE",
                higherLongPut, lowerShortPut, asOf, netDebit, peak, stressPnl, trueMaximumLoss);

        // The extra short put can cause SEVERE downside loss. Unlike extra
        // short calls, an index cannot drop below zero, so the expiry loss
        // bound is finite. It is not a broker margin or liquidation guarantee.
        return measured(Status.RESEARCH_CANDIDATE, "PUT_RATIO_FINITE_DOWNWARD_TAIL_RESEARCH_ONLY",
            higherLongPut, lowerShortPut, asOf, netDebit, peak, stressPnl, trueMaximumLoss);
    }

    private static BigDecimal payoffAt(BigDecimal spot, Contract longP, Contract shortP,
                                       BigDecimal lot, BigDecimal cost) {
        BigDecimal longIntrinsic = longP.strike().subtract(spot, MC).max(BigDecimal.ZERO);
        BigDecimal shortIntrinsic = shortP.strike().subtract(spot, MC).max(BigDecimal.ZERO);
        return longIntrinsic.subtract(shortIntrinsic.multiply(BigDecimal.valueOf(2), MC), MC)
            .multiply(lot, MC).subtract(cost, MC);
    }

    private static boolean contractsValid(Contract l, Contract h, Instant at) {
        if (l == null || h == null || !l.verified() || !h.verified()
                || !"PE".equals(l.type()) || !"PE".equals(h.type())
                || !FnoLongOptionCrossoverV1.UNDERLYING.equals(l.underlying())
                || !l.underlying().equals(h.underlying())
                || !optionKey(l.key()) || !optionKey(h.key()) || l.key().equals(h.key())
                || !positive(l.strike()) || !positive(h.strike())
                || l.strike().compareTo(h.strike()) <= 0
                || !positive(l.tick()) || !positive(h.tick())
                || l.strike().remainder(l.tick()).signum() != 0
                || h.strike().remainder(h.tick()).signum() != 0
                || l.lotSize() < 1 || l.lotSize() != h.lotSize()
                || l.expiry() == null || !l.expiry().equals(h.expiry())
                || l.listedFrom() == null || h.listedFrom() == null
                || l.listedFrom().isAfter(at.atZone(IST).toLocalDate())
                || h.listedFrom().isAfter(at.atZone(IST).toLocalDate())) return false;
        long days = ChronoUnit.DAYS.between(at.atZone(IST).toLocalDate(), l.expiry());
        return days >= 3 && days <= 45;
    }
    private static boolean historyValid(List<Bar> bars, Instant at) {
        if (bars == null || bars.size() < 35 || bars.size() > 512) return false;
        LocalDate previous = null;
        String provider = null;
        for (var b : bars) {
            if (b == null || !b.verified() || b.session() == null
                    || b.session().getDayOfWeek().getValue() > 5
                    || !provider(b.provider()) || !positive(b.close())
                    || b.exchangeCloseAt() == null || b.firstKnownAt() == null) return false;
            Instant close = b.session().atTime(CLOSE).atZone(IST).toInstant();
            if (!b.exchangeCloseAt().equals(close) || b.firstKnownAt().isBefore(close)
                    || b.firstKnownAt().isAfter(close.plus(MAX_PUBLISHED_LAG))
                    || b.firstKnownAt().isAfter(at)
                    || provider != null && !provider.equals(b.provider())) return false;
            if (previous != null) {
                long gap = ChronoUnit.DAYS.between(previous, b.session());
                if (gap < 1 || gap > 5) return false;
            }
            previous = b.session();
            provider = b.provider();
        }
        long age = ChronoUnit.DAYS.between(previous, at.atZone(IST).toLocalDate());
        return age >= 0 && age <= 4;
    }
    private static boolean quotesValid(Contract l, Contract h, Bar bar, Quote index,
                                       Quote longQ, Quote shortQ, Assumptions a, Instant at) {
        if (index == null || longQ == null || shortQ == null
                || !bar.provider().equals(index.provider())
                || !index.provider().equals(longQ.provider())
                || !index.provider().equals(shortQ.provider())
                || !l.underlying().equals(index.key())
                || !l.key().equals(longQ.key()) || !h.key().equals(shortQ.key())
                || !validQuote(index, 1, 1, Integer.MAX_VALUE)
                || !validQuote(longQ, a.minBidAskDepthLots(), a.minOiLots(), a.maxOptionSpreadBps())
                || !validQuote(shortQ, 2*a.minBidAskDepthLots(), a.minOiLots(), a.maxOptionSpreadBps())
                || shortQ.bidDepthLots() < 2
                || !validTime(index.exchangeAt(),index.firstKnownAt(),at)
                || !validTime(longQ.exchangeAt(),longQ.firstKnownAt(),at)
                || !validTime(shortQ.exchangeAt(),shortQ.firstKnownAt(),at)
                || bar.firstKnownAt().isAfter(index.exchangeAt())
                || bar.firstKnownAt().isAfter(longQ.exchangeAt())
                || bar.firstKnownAt().isAfter(shortQ.exchangeAt())) return false;
        Instant min = index.exchangeAt(), max = index.exchangeAt();
        for (var t : List.of(longQ.exchangeAt(),shortQ.exchangeAt())) {
            if (t.isBefore(min)) min=t;
            if (t.isAfter(max)) max=t;
        }
        if (Duration.between(min,max).compareTo(MAX_SKEW)>0) return false;
        BigDecimal spot=index.bid();
        BigDecimal lower=l.strike().subtract(spot,MC).abs().multiply(BPS,MC).divide(spot,MC);
        BigDecimal upper=h.strike().subtract(spot,MC).abs().multiply(BPS,MC).divide(spot,MC);
        return lower.compareTo(BigDecimal.valueOf(a.maxStrikeDistanceBps()))<=0
            && upper.compareTo(BigDecimal.valueOf(a.maxStrikeDistanceBps()))<=0;
    }
    private static boolean validQuote(Quote q,long depth,long oi,int maxSpread) {
        if (!positive(q.bid()) || !positive(q.ask()) || q.ask().compareTo(q.bid())<0
                || q.bidDepthLots()<depth || q.askDepthLots()<depth || q.oiLots()<oi) return false;
        BigDecimal mid=q.bid().add(q.ask(),MC).divide(BigDecimal.valueOf(2),MC);
        return q.ask().subtract(q.bid(),MC).multiply(BPS,MC).divide(mid,MC)
            .compareTo(BigDecimal.valueOf(maxSpread))<=0;
    }
    private static boolean validTime(Instant exchange,Instant known,Instant at) {
        if(exchange==null || known==null || exchange.isAfter(known)
                || known.isAfter(at) || exchange.isAfter(at)
                || Duration.between(exchange,at).compareTo(MAX_AGE)>0) return false;
        var z=exchange.atZone(IST);
        return z.getDayOfWeek().getValue()<=5
            && !z.toLocalTime().isBefore(LocalTime.of(9,15))
            && !z.toLocalTime().isAfter(CLOSE);
    }
    private static boolean assumptionsValid(Assumptions a) {
        return a!=null && between(a.buySlippageBps(),0,1000)
            && between(a.sellSlippageBps(),0,1000)
            && between(a.maxOptionSpreadBps(),1,3000)
            && a.minBidAskDepthLots()>=1 && a.minBidAskDepthLots()<=100000
            && a.minOiLots()>=1 && between(a.maxStrikeDistanceBps(),1,3000)
            && positive(a.totalFeesPerRatioLotInr())
            && positive(a.researchCapitalInr())
            && between(a.maxLossAllocationBps(),100,10000)
            && positive(a.downsideStressUnderlyingPrice())
            && between(a.minPeakRewardBpsOfMaxLoss(),100,100000);
    }
    private static boolean optionKey(String v) {
        return v!=null && v.matches("NSE_FO\\|[A-Za-z0-9._-]{1,64}");
    }
    private static boolean provider(String v) {
        return "UPSTOX_ANALYTICS".equals(v)||"ANGEL_ONE_SMARTAPI".equals(v);
    }
    private static boolean positive(BigDecimal v){return v!=null && v.signum()>0;}
    private static boolean between(int v,int lo,int hi){return v>=lo && v<=hi;}
    private static BigDecimal round(BigDecimal v){return v==null?null:v.setScale(4,RoundingMode.HALF_UP);}
    private static Decision measured(Status status,String reason,Contract l,Contract h,
                                     Instant at,BigDecimal debit,BigDecimal peak,
                                     BigDecimal stress,BigDecimal stressLoss){
        return new Decision(status,reason,STRATEGY_ID,l.key(),h.key(),1,2,at,
            round(debit),round(peak),round(stress),round(stressLoss),false);
    }
    private static Decision blocked(Status status,String reason,Instant at){
        return new Decision(status,reason,STRATEGY_ID,null,null,1,2,at,
            null,null,null,null,false);
    }
}
