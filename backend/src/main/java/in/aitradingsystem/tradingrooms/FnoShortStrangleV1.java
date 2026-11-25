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
 * STR-195 — research-only short strangle: SELL OTM higher-strike CE and lower-strike PE, same expiry/lot.
 * Uncovered OTM short CE creates UNBOUNDED theoretical upside loss.
 * Lower short PE has substantial but finite zero-index downside loss.
 * Credit is neither a margin budget nor a bound on losses.
 * Strictly non-executable; naked option writing is never authorized.
 */
public final class FnoShortStrangleV1 {
    public static final String STRATEGY_ID = "strategy.STR-195";
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal BPS = new BigDecimal("10000");
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime NSE_CLOSE = LocalTime.of(15, 30);
    private static final Duration MAX_QUOTE_AGE = Duration.ofSeconds(180);
    private static final Duration MAX_SKEW = Duration.ofSeconds(90);
    private FnoShortStrangleV1() {}

    public enum Status { BLOCKED_RIGHTS, BLOCKED_DATA, NO_SIGNAL, RESEARCH_CANDIDATE }

    public record Contract(String key, String underlyingKey, String optionType,
                           BigDecimal strike, BigDecimal tick, LocalDate listedFrom,
                           LocalDate expiry, int lotSize, boolean sourceVerified) {}
    public record CompletedClose(LocalDate session, String provider, BigDecimal close,
                                 Instant exchangeCloseAt, Instant firstKnownAt,
                                 boolean verified) {}
    public record Quote(String provider, String key, BigDecimal bid, BigDecimal ask,
                        BigDecimal impliedVolatilityPercent, long bidDepthLots,
                        long openInterestLots, Instant exchangeAt, Instant firstKnownAt) {}
    public record Rights(boolean indexResearch, boolean optionChainResearch,
                         boolean instrumentDiscoveryResearch, boolean impliedVolatilityResearch) {}
    /** IV values are annualized percentages (20 means 20%, not 0.20). */
    public record Assumptions(int sellSlippageBps, int maxSpreadBps,
                              long minBidDepthLots, long minOiLots,
                              int maxOtmDistanceBps, int maxIvPercent,
                              BigDecimal allInFeesPerLotInr, BigDecimal researchCapitalInr,
                              int maxCollectedCreditBpsOfCapital, int maxExpectedMoveOverCreditBps) {}
    public record Decision(Status status, String reasonCode, String strategyId,
                           String callKey, String putKey, Instant asOf,
                           BigDecimal netCollectedCreditPerLotInr,
                           BigDecimal theoreticalMaximumDownsideExpiryLossInr,
                           BigDecimal lowerExpiryBreakeven, BigDecimal upperExpiryBreakeven,
                           BigDecimal expectedOneSigmaMovePerUnit,
                           BigDecimal expectedMoveOverCreditBps,
                           boolean unlimitedTheoreticalUpsideLoss) {
        public boolean executable() { return false; }
        public boolean orderPlaced() { return false; }
        public boolean liveTradeAuthorized() { return false; }
    }

    public static Decision evaluate(Contract call, Contract put,
                                    List<CompletedClose> completedDailyIndexCloses,
                                    Quote underlying, Quote callQuote, Quote putQuote,
                                    Rights rights, Assumptions a, Instant asOf) {
        Objects.requireNonNull(asOf, "asOf");
        if (rights == null || !rights.indexResearch() || !rights.optionChainResearch()
                || !rights.instrumentDiscoveryResearch() || !rights.impliedVolatilityResearch())
            return blocked(Status.BLOCKED_RIGHTS, "SHORT_STRANGLE_RIGHTS_UNVERIFIED", asOf);
        if (!contractsValid(call, put, asOf) || !assumptionsValid(a))
            return blocked(Status.BLOCKED_DATA, "SHORT_STRANGLE_CONTRACT_OR_COST_INVALID", asOf);
        if (!historyValid(completedDailyIndexCloses, asOf))
            return blocked(Status.BLOCKED_DATA, "SHORT_STRANGLE_HISTORY_INVALID", asOf);
        if (!quotesValid(call, put, completedDailyIndexCloses.getLast(),
                underlying, callQuote, putQuote, a, asOf))
            return blocked(Status.BLOCKED_DATA, "SHORT_STRANGLE_QUOTES_OR_IV_INVALID", asOf);

        // Sell both legs at the actual bid minus modeled slippage, not ask/mid.
        BigDecimal effectiveCallBid = callQuote.bid()
            .multiply(BPS.subtract(BigDecimal.valueOf(a.sellSlippageBps()), MC), MC).divide(BPS, MC);
        BigDecimal effectivePutBid = putQuote.bid()
            .multiply(BPS.subtract(BigDecimal.valueOf(a.sellSlippageBps()), MC), MC).divide(BPS, MC);
        BigDecimal lot = BigDecimal.valueOf(call.lotSize());
        BigDecimal credit = effectiveCallBid.add(effectivePutBid, MC).multiply(lot, MC)
            .subtract(a.allInFeesPerLotInr(), MC);
        if (credit.signum() <= 0)
            return decision(Status.NO_SIGNAL, "SHORT_STRANGLE_AFTER_COST_CREDIT_NONPOSITIVE",
                call, put, asOf, credit, null, null, null, null);
        BigDecimal creditCap = a.researchCapitalInr()
            .multiply(BigDecimal.valueOf(a.maxCollectedCreditBpsOfCapital()), MC).divide(BPS, MC);
        if (credit.compareTo(creditCap) > 0)
            return decision(Status.NO_SIGNAL, "SHORT_STRANGLE_CREDIT_SANITY_CAP_EXCEEDED",
                call, put, asOf, credit, null, null, null, null);
        // One-sigma IV move is a crude heuristic, NOT a loss limit or forecast.
        long dte = ChronoUnit.DAYS.between(asOf.atZone(IST).toLocalDate(), call.expiry());
        BigDecimal averageIv = callQuote.impliedVolatilityPercent()
            .add(putQuote.impliedVolatilityPercent(), MC).divide(BigDecimal.valueOf(2), MC);
        BigDecimal expectedMove = underlying.bid().multiply(averageIv, MC)
            .divide(BigDecimal.valueOf(100), MC)
            .multiply(BigDecimal.valueOf(StrictMath.sqrt(dte/365.0)), MC);
        BigDecimal creditUnit = credit.divide(lot, MC);
        BigDecimal moveOverCreditBps = expectedMove.multiply(BPS, MC).divide(creditUnit, MC);
        BigDecimal lower = put.strike().subtract(creditUnit, MC);
        BigDecimal upper = call.strike().add(creditUnit, MC);
        if (moveOverCreditBps.compareTo(BigDecimal.valueOf(a.maxExpectedMoveOverCreditBps())) > 0)
            return decision(Status.NO_SIGNAL, "SHORT_STRANGLE_IV_MOVE_EXCEEDS_CREDIT",
                call, put, asOf, credit, expectedMove, moveOverCreditBps, lower, upper);

        // At zero index price, the put intrinsic is the LOWER PE strike * lot.
        // Above the HIGHER CE strike, the short CE causes UNBOUNDED theoretical loss.
        // Candidate status can NEVER authorize PAPER or LIVE option writing.
        return decision(Status.RESEARCH_CANDIDATE, "SHORT_STRANGLE_UNBOUNDED_RISK_RESEARCH_ONLY",
            call, put, asOf, credit, expectedMove, moveOverCreditBps, lower, upper);
    }

    private static boolean contractsValid(Contract c, Contract p, Instant at) {
        if (c == null || p == null || !c.sourceVerified() || !p.sourceVerified()
                || !"CE".equals(c.optionType()) || !"PE".equals(p.optionType())
                || !FnoLongOptionCrossoverV1.UNDERLYING.equals(c.underlyingKey())
                || !c.underlyingKey().equals(p.underlyingKey())
                || !key(c.key()) || !key(p.key()) || c.key().equals(p.key())
                || !positive(c.strike()) || c.strike().compareTo(p.strike()) <= 0
                || !positive(c.tick()) || !positive(p.tick())
                || c.strike().remainder(c.tick()).signum()!=0
                || p.strike().remainder(p.tick()).signum()!=0
                || c.lotSize() < 1 || c.lotSize()!=p.lotSize()
                || c.expiry()==null || !c.expiry().equals(p.expiry())
                || c.listedFrom()==null || p.listedFrom()==null
                || c.listedFrom().isAfter(at.atZone(IST).toLocalDate())
                || p.listedFrom().isAfter(at.atZone(IST).toLocalDate())) return false;
        long dte=ChronoUnit.DAYS.between(at.atZone(IST).toLocalDate(),c.expiry());
        return dte>=3 && dte<=45;
    }
    private static boolean historyValid(List<CompletedClose> bars, Instant at) {
        if (bars==null || bars.size()<35 || bars.size()>512) return false;
        LocalDate prior=null;
        String provider=null;
        for (var b:bars) {
            if(b==null || !b.verified() || b.session()==null
                    || b.session().getDayOfWeek().getValue()>5
                    || !source(b.provider()) || !positive(b.close())
                    || b.exchangeCloseAt()==null || b.firstKnownAt()==null) return false;
            Instant close=b.session().atTime(NSE_CLOSE).atZone(IST).toInstant();
            if(!close.equals(b.exchangeCloseAt()) || b.firstKnownAt().isBefore(close)
                    || b.firstKnownAt().isAfter(close.plus(Duration.ofHours(36)))
                    || b.firstKnownAt().isAfter(at)
                    || provider!=null && !provider.equals(b.provider())) return false;
            if(prior!=null) {
                long gap=ChronoUnit.DAYS.between(prior,b.session());
                if(gap<1 || gap>5) return false;
            }
            prior=b.session(); provider=b.provider();
        }
        long days=ChronoUnit.DAYS.between(prior,at.atZone(IST).toLocalDate());
        return days>=0 && days<=4;
    }
    private static boolean quotesValid(Contract c, Contract p, CompletedClose bar,
                                       Quote spot, Quote cq, Quote pq,
                                       Assumptions a, Instant at) {
        if (spot==null || cq==null || pq==null
                || !bar.provider().equals(spot.provider())
                || !spot.provider().equals(cq.provider())
                || !spot.provider().equals(pq.provider())
                || !c.underlyingKey().equals(spot.key())
                || !c.key().equals(cq.key()) || !p.key().equals(pq.key())
                || !validMarketQuote(spot,1,1,Integer.MAX_VALUE)
                || !validMarketQuote(cq,a.minBidDepthLots(),a.minOiLots(),a.maxSpreadBps())
                || !validMarketQuote(pq,a.minBidDepthLots(),a.minOiLots(),a.maxSpreadBps())
                || !validTime(spot.exchangeAt(),spot.firstKnownAt(),at)
                || !validTime(cq.exchangeAt(),cq.firstKnownAt(),at)
                || !validTime(pq.exchangeAt(),pq.firstKnownAt(),at)
                || bar.firstKnownAt().isAfter(spot.exchangeAt())
                || bar.firstKnownAt().isAfter(cq.exchangeAt())
                || bar.firstKnownAt().isAfter(pq.exchangeAt())
                || !ivValid(cq.impliedVolatilityPercent(),a.maxIvPercent())
                || !ivValid(pq.impliedVolatilityPercent(),a.maxIvPercent())) return false;
        Instant min=spot.exchangeAt(),max=spot.exchangeAt();
        for(Instant t:List.of(cq.exchangeAt(),pq.exchangeAt())) {
            if(t.isBefore(min)) min=t;
            if(t.isAfter(max)) max=t;
        }
        if(Duration.between(min,max).compareTo(MAX_SKEW)>0) return false;
        BigDecimal callDistance = c.strike().subtract(spot.bid(),MC)
            .multiply(BPS,MC).divide(spot.bid(),MC);
        BigDecimal putDistance = spot.bid().subtract(p.strike(),MC)
            .multiply(BPS,MC).divide(spot.bid(),MC);
        return callDistance.signum()>0 && putDistance.signum()>0
            && callDistance.compareTo(BigDecimal.valueOf(a.maxOtmDistanceBps()))<=0
            && putDistance.compareTo(BigDecimal.valueOf(a.maxOtmDistanceBps()))<=0;
    }
    private static boolean validMarketQuote(Quote q,long depth,long oi,int spreadLimit) {
        if(!positive(q.bid()) || !positive(q.ask()) || q.ask().compareTo(q.bid())<0
                || q.bidDepthLots()<depth || q.openInterestLots()<oi) return false;
        BigDecimal mid=q.bid().add(q.ask(),MC).divide(BigDecimal.valueOf(2),MC);
        BigDecimal spread=q.ask().subtract(q.bid(),MC).multiply(BPS,MC).divide(mid,MC);
        return spread.compareTo(BigDecimal.valueOf(spreadLimit))<=0;
    }
    private static boolean ivValid(BigDecimal iv,int maxIv) {
        return positive(iv) && iv.compareTo(BigDecimal.valueOf(maxIv))<=0;
    }
    private static boolean validTime(Instant exchange, Instant observed, Instant at) {
        if(exchange==null || observed==null || exchange.isAfter(observed)
                || observed.isAfter(at) || exchange.isAfter(at)
                || Duration.between(exchange,at).compareTo(MAX_QUOTE_AGE)>0) return false;
        var z=exchange.atZone(IST);var local=z.toLocalTime();
        return z.getDayOfWeek().getValue()<=5
            && !local.isBefore(LocalTime.of(9,15)) && !local.isAfter(NSE_CLOSE);
    }
    private static boolean assumptionsValid(Assumptions a) {
        return a!=null && between(a.sellSlippageBps(),0,1000)
            && between(a.maxSpreadBps(),1,3000)
            && a.minBidDepthLots()>=1 && a.minOiLots()>=1
            && between(a.maxOtmDistanceBps(),1,2000)
            && between(a.maxIvPercent(),5,500)
            && positive(a.allInFeesPerLotInr())
            && positive(a.researchCapitalInr())
            && between(a.maxCollectedCreditBpsOfCapital(),100,10000)
            && between(a.maxExpectedMoveOverCreditBps(),100,100000);
    }
    private static boolean source(String v) {
        return "UPSTOX_ANALYTICS".equals(v) || "ANGEL_ONE_SMARTAPI".equals(v);
    }
    private static boolean key(String v) {
        return v!=null && v.matches("NSE_FO\\|[A-Za-z0-9._-]{1,64}");
    }
    private static boolean positive(BigDecimal v){return v!=null && v.signum()>0;}
    private static boolean between(int v,int lo,int hi){return v>=lo && v<=hi;}
    private static BigDecimal rounded(BigDecimal v) {
        return v==null?null:v.setScale(4,RoundingMode.HALF_UP);
    }
    private static Decision decision(Status st,String reason,Contract c,Contract p,
                                     Instant at,BigDecimal credit,BigDecimal move,
                                     BigDecimal ratio,BigDecimal lower,BigDecimal upper) {
        BigDecimal worstDownsideLoss = credit==null ? null
            : p.strike().multiply(BigDecimal.valueOf(p.lotSize()), MC)
                .subtract(credit, MC).max(BigDecimal.ZERO);
        return new Decision(st,reason,STRATEGY_ID,c.key(),p.key(),at,rounded(credit),
            rounded(worstDownsideLoss),rounded(lower),rounded(upper),rounded(move),rounded(ratio),true);
    }
    private static Decision blocked(Status st,String reason,Instant at) {
        return new Decision(st,reason,STRATEGY_ID,null,null,at,
            null,null,null,null,null,null,true);
    }
}
