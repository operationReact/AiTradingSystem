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
 * STR-186: deterministic, two-leg NIFTY Bear Call Credit Spread research ONLY.
 * Sell lower-strike CE and buy higher-strike CE, SAME verified expiry/lot.
 * No order, margin authorization, fill assumption, or broker-side execution.
 * Net credit/maximum theoretical gain and loss are hypothetical at expiry.
 */
public final class FnoBearCallCreditSpreadV1 {
    public static final String STRATEGY_ID = "strategy.STR-186";
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal BPS = new BigDecimal("10000");
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime SESSION_OPEN = LocalTime.of(9, 15);
    private static final LocalTime SESSION_CLOSE = LocalTime.of(15, 30);
    private static final Duration MAX_QUOTE_AGE = Duration.ofSeconds(180);
    private static final Duration MAX_SKEW = Duration.ofSeconds(90);
    private static final Duration MAX_PUBLISHED_LAG = Duration.ofHours(36);
    private FnoBearCallCreditSpreadV1() {}

    public enum Status { BLOCKED_RIGHTS, BLOCKED_DATA, NO_SIGNAL, RESEARCH_CANDIDATE }

    public record Contract(String instrumentKey, String underlyingKey, String optionType,
                           BigDecimal strike, BigDecimal tickSize, LocalDate listedFrom,
                           LocalDate expiry, int lotSize, boolean contractVerified) {}

    public record CompletedClose(LocalDate session, String provider, Instant exchangeCloseAt,
                                 Instant firstKnownAt, BigDecimal close, boolean verified) {}

    public record UnderlyingQuote(String provider, String key, BigDecimal price,
                                  Instant exchangeAt, Instant firstKnownAt) {}

    /** Bid/ask INR per option unit, bid/ask-side sizes and OI in lots. */
    public record OptionQuote(String provider, String key, BigDecimal bid, BigDecimal ask,
                              long bidDepthLots, long askDepthLots, long oiLots,
                              Instant exchangeAt, Instant firstKnownAt) {}

    public record SourceRights(boolean indexResearch, boolean optionChainResearch,
                               boolean instrumentDiscoveryResearch) {}

    /** Fee is combined BOTH legs, estimated taxes/slippage/levies in INR/lot. */
    public record Assumptions(int buySlippageBps, int sellSlippageBps,
                              BigDecimal allInRoundTripFeesPerSpreadLotInr,
                              int maximumEachLegSpreadBps, long minBidAskDepthLots,
                              long minOpenInterestLots, int minMaxRewardRiskBps,
                              BigDecimal researchCapitalInr, int maxAllocationBps,
                              int maxStrikeDistanceBps) {}

    public record Decision(Status status, String reasonCode, String strategyId,
                           String longCallKey, String shortCallKey, Instant asOf,
                           BigDecimal creditPerUnit, BigDecimal breakEvenAtExpiry,
                           BigDecimal maxLossPerSpreadLotInr,
                           BigDecimal maxGainPerSpreadLotInr,
                           BigDecimal maxRewardRiskBps) {
        public boolean executable() { return false; }
        public boolean orderPlaced() { return false; }
        public boolean liveTradeAuthorized() { return false; }
    }

    public static Decision evaluate(Contract lower, Contract upper,
                                    List<CompletedClose> completedCloses,
                                    UnderlyingQuote underlying, OptionQuote longQuote,
                                    OptionQuote shortQuote, SourceRights rights,
                                    Assumptions a, Instant asOf) {
        Objects.requireNonNull(asOf, "asOf");
        if (rights == null || !rights.indexResearch() || !rights.optionChainResearch()
                || !rights.instrumentDiscoveryResearch())
            return blocked(Status.BLOCKED_RIGHTS, "BEAR_CALL_CREDIT_SPREAD_RIGHTS_MISSING", asOf);
        if (!validContracts(lower, upper, asOf) || !validAssumptions(a))
            return blocked(Status.BLOCKED_DATA, "BEAR_CALL_CREDIT_SPREAD_CONTRACT_OR_COST_INVALID", asOf);
        if (!validHistory(completedCloses, asOf))
            return blocked(Status.BLOCKED_DATA, "BEAR_CALL_CREDIT_SPREAD_HISTORY_INVALID", asOf);

        String provider = completedCloses.getFirst().provider();
        if (!validQuotes(lower, upper, underlying, longQuote, shortQuote, provider, a, asOf)
                || completedCloses.getLast().firstKnownAt().isAfter(underlying.exchangeAt())
                || completedCloses.getLast().firstKnownAt().isAfter(longQuote.exchangeAt())
                || completedCloses.getLast().firstKnownAt().isAfter(shortQuote.exchangeAt()))
            return blocked(Status.BLOCKED_DATA, "BEAR_CALL_CREDIT_SPREAD_QUOTES_OR_ASOF_INVALID", asOf);

        var candles = new ArrayList<FnoLongOptionCrossoverV1.CompletedBar>();
        for (var b : completedCloses)
            candles.add(new FnoLongOptionCrossoverV1.CompletedBar(
                b.exchangeCloseAt(), b.firstKnownAt(), b.close()));
        var direction = FnoLongOptionCrossoverV1.evaluate(
            FnoLongOptionCrossoverV1.UNDERLYING, true, candles, asOf);
        if (direction.status() == FnoLongOptionCrossoverV1.Status.BLOCKED_DATA)
            return blocked(Status.BLOCKED_DATA, "BEAR_CALL_CREDIT_SPREAD_TREND_INPUT_INVALID", asOf);
        if (direction.status() != FnoLongOptionCrossoverV1.Status.RESEARCH_CANDIDATE
                || !"strategy.STR-182".equals(direction.strategyId()))
            return noSignal("BEAR_CALL_CREDIT_SPREAD_NEW_BEARISH_CROSSOVER_ABSENT", lower, upper, asOf);

        // Sell lower-strike CE at bid minus slippage; buy higher-strike CE
        // at ask plus slippage. These are hypothetical quotes, not fills.
        BigDecimal longPaid = longQuote.ask()
            .multiply(BPS.add(BigDecimal.valueOf(a.buySlippageBps()), MC), MC)
            .divide(BPS, MC);
        BigDecimal shortReceived = shortQuote.bid()
            .multiply(BPS.subtract(BigDecimal.valueOf(a.sellSlippageBps()), MC), MC)
            .divide(BPS, MC);
        BigDecimal credit = shortReceived.subtract(longPaid, MC);
        BigDecimal width = upper.strike().subtract(lower.strike(), MC);
        if (credit.signum() <= 0 || credit.compareTo(width) >= 0)
            return noSignal("BEAR_CALL_CREDIT_SPREAD_CREDIT_NOT_ECONOMIC", lower, upper, asOf);
        BigDecimal feesPerUnit = a.allInRoundTripFeesPerSpreadLotInr()
            .divide(BigDecimal.valueOf(lower.lotSize()), MC);
        BigDecimal breakeven = lower.strike().add(credit, MC).subtract(feesPerUnit, MC);
        BigDecimal maxLoss = width.subtract(credit, MC)
            .multiply(BigDecimal.valueOf(lower.lotSize()), MC)
            .add(a.allInRoundTripFeesPerSpreadLotInr(), MC);
        BigDecimal maxGain = credit.multiply(BigDecimal.valueOf(lower.lotSize()), MC)
            .subtract(a.allInRoundTripFeesPerSpreadLotInr(), MC);
        BigDecimal budget = a.researchCapitalInr()
            .multiply(BigDecimal.valueOf(a.maxAllocationBps()), MC)
            .divide(BPS, MC);
        if (maxLoss.compareTo(budget) > 0)
            return measured(Status.NO_SIGNAL, "BEAR_CALL_CREDIT_SPREAD_BUDGET_EXCEEDED",
                lower, upper, asOf, credit, breakeven, maxLoss, maxGain, null);
        BigDecimal rr = maxGain.multiply(BPS, MC).divide(maxLoss, MC);
        boolean passes = maxGain.signum() > 0
            && rr.compareTo(BigDecimal.valueOf(a.minMaxRewardRiskBps())) >= 0;
        return measured(passes ? Status.RESEARCH_CANDIDATE : Status.NO_SIGNAL,
            passes ? "BEAR_CALL_CREDIT_SPREAD_RESEARCH_SETUP_ONLY"
                   : "BEAR_CALL_CREDIT_SPREAD_AFTER_COST_MAX_REWARD_TOO_LOW",
            lower, upper, asOf, credit, breakeven, maxLoss, maxGain, rr);
    }

    private static boolean validContracts(Contract l, Contract h, Instant asOf) {
        if (l == null || h == null || !l.contractVerified() || !h.contractVerified()
                || !"CE".equals(l.optionType()) || !"CE".equals(h.optionType())
                || !FnoLongOptionCrossoverV1.UNDERLYING.equals(l.underlyingKey())
                || !l.underlyingKey().equals(h.underlyingKey())
                || !key(l.instrumentKey()) || !key(h.instrumentKey())
                || l.instrumentKey().equals(h.instrumentKey())
                || !positive(l.strike()) || !positive(h.strike())
                || l.strike().compareTo(h.strike()) >= 0
                || !positive(l.tickSize()) || !positive(h.tickSize())
                || l.strike().remainder(l.tickSize()).signum() != 0
                || h.strike().remainder(h.tickSize()).signum() != 0
                || l.lotSize() < 1 || l.lotSize() != h.lotSize()
                || l.expiry() == null || !l.expiry().equals(h.expiry())
                || l.listedFrom() == null || h.listedFrom() == null
                || l.listedFrom().isAfter(asOf.atZone(IST).toLocalDate())
                || h.listedFrom().isAfter(asOf.atZone(IST).toLocalDate()))
            return false;
        long dte = ChronoUnit.DAYS.between(asOf.atZone(IST).toLocalDate(), l.expiry());
        return dte >= 3 && dte <= 45;
    }
    private static boolean validHistory(List<CompletedClose> bars, Instant asOf) {
        if (bars == null || bars.size() < 35 || bars.size() > 512) return false;
        LocalDate prior = null;
        String provider = null;
        for (var b : bars) {
            if (b == null || !b.verified() || !provider(b.provider())
                    || b.session() == null || b.session().getDayOfWeek().getValue() > 5
                    || !positive(b.close()) || b.exchangeCloseAt() == null
                    || b.firstKnownAt() == null) return false;
            Instant close = b.session().atTime(SESSION_CLOSE).atZone(IST).toInstant();
            if (!b.exchangeCloseAt().equals(close) || b.firstKnownAt().isBefore(close)
                    || b.firstKnownAt().isAfter(close.plus(MAX_PUBLISHED_LAG))
                    || b.firstKnownAt().isAfter(asOf)
                    || provider != null && !provider.equals(b.provider()))
                return false;
            if (prior != null) {
                long gap = ChronoUnit.DAYS.between(prior, b.session());
                if (gap < 1 || gap > 5) return false;
            }
            prior = b.session();
            provider = b.provider();
        }
        long age = ChronoUnit.DAYS.between(prior, asOf.atZone(IST).toLocalDate());
        return age >= 0 && age <= 4;
    }
    private static boolean validQuotes(Contract l, Contract h, UnderlyingQuote u,
                                       OptionQuote ql, OptionQuote qh, String provider,
                                       Assumptions a, Instant asOf) {
        if (u == null || ql == null || qh == null || !provider.equals(u.provider())
                || !provider.equals(ql.provider()) || !provider.equals(qh.provider())
                || !l.underlyingKey().equals(u.key()) || !h.instrumentKey().equals(ql.key())
                || !l.instrumentKey().equals(qh.key()) || !positive(u.price())
                || !validQuote(ql, a) || !validQuote(qh, a)
                || !timestamp(u.exchangeAt(), u.firstKnownAt(), asOf)
                || !timestamp(ql.exchangeAt(), ql.firstKnownAt(), asOf)
                || !timestamp(qh.exchangeAt(), qh.firstKnownAt(), asOf))
            return false;
        Instant first = u.exchangeAt().isBefore(ql.exchangeAt())
            ? u.exchangeAt() : ql.exchangeAt();
        first = first.isBefore(qh.exchangeAt()) ? first : qh.exchangeAt();
        Instant last = u.exchangeAt().isAfter(ql.exchangeAt())
            ? u.exchangeAt() : ql.exchangeAt();
        last = last.isAfter(qh.exchangeAt()) ? last : qh.exchangeAt();
        if (Duration.between(first, last).compareTo(MAX_SKEW) > 0) return false;
        BigDecimal distance1 = l.strike().subtract(u.price(), MC).abs()
            .multiply(BPS, MC).divide(u.price(), MC);
        BigDecimal distance2 = h.strike().subtract(u.price(), MC).abs()
            .multiply(BPS, MC).divide(u.price(), MC);
        return distance1.compareTo(BigDecimal.valueOf(a.maxStrikeDistanceBps())) <= 0
            && distance2.compareTo(BigDecimal.valueOf(a.maxStrikeDistanceBps())) <= 0;
    }
    private static boolean validQuote(OptionQuote q, Assumptions a) {
        if (!positive(q.bid()) || !positive(q.ask()) || q.ask().compareTo(q.bid()) < 0
                || q.askDepthLots() < a.minBidAskDepthLots()
                || q.bidDepthLots() < a.minBidAskDepthLots()
                || q.oiLots() < a.minOpenInterestLots()) return false;
        BigDecimal mid = q.bid().add(q.ask(), MC).divide(BigDecimal.valueOf(2), MC);
        BigDecimal spread = q.ask().subtract(q.bid(), MC).multiply(BPS, MC).divide(mid, MC);
        return spread.compareTo(BigDecimal.valueOf(a.maximumEachLegSpreadBps())) <= 0;
    }
    private static boolean timestamp(Instant at, Instant known, Instant asOf) {
        if (at == null || known == null || at.isAfter(known) || known.isAfter(asOf)
                || at.isAfter(asOf) || Duration.between(at, asOf).compareTo(MAX_QUOTE_AGE) > 0)
            return false;
        var zoned = at.atZone(IST);
        var time = zoned.toLocalTime();
        return zoned.getDayOfWeek().getValue() <= 5
            && !time.isBefore(SESSION_OPEN) && !time.isAfter(SESSION_CLOSE);
    }
    private static boolean validAssumptions(Assumptions a) {
        return a != null && between(a.buySlippageBps(), 0, 1000)
            && between(a.sellSlippageBps(), 0, 1000)
            && positive(a.allInRoundTripFeesPerSpreadLotInr())
            && a.allInRoundTripFeesPerSpreadLotInr().compareTo(BigDecimal.valueOf(100000)) <= 0
            && between(a.maximumEachLegSpreadBps(), 1, 3000)
            && a.minBidAskDepthLots() >= 1 && a.minOpenInterestLots() >= 1
            && between(a.minMaxRewardRiskBps(), 1000, 50000)
            && positive(a.researchCapitalInr()) && between(a.maxAllocationBps(), 100, 10000)
            && between(a.maxStrikeDistanceBps(), 1, 3000);
    }
    private static boolean provider(String s) {
        return "UPSTOX_ANALYTICS".equals(s) || "ANGEL_ONE_SMARTAPI".equals(s);
    }
    private static boolean key(String s) {
        return s != null && s.matches("NSE_FO\\|[A-Za-z0-9._-]{1,64}");
    }
    private static boolean positive(BigDecimal x) { return x != null && x.signum() > 0; }
    private static boolean between(int x, int a, int b) { return x >= a && x <= b; }
    private static BigDecimal rounded(BigDecimal x) {
        return x == null ? null : x.setScale(4, RoundingMode.HALF_UP);
    }
    private static Decision noSignal(String reason, Contract l, Contract h, Instant asOf) {
        return measured(Status.NO_SIGNAL, reason, l, h, asOf, null, null, null, null, null);
    }
    private static Decision measured(Status status, String reason, Contract l, Contract h,
                                     Instant asOf, BigDecimal credit, BigDecimal breakeven,
                                     BigDecimal maxLoss, BigDecimal maxGain, BigDecimal rr) {
        return new Decision(status, reason, STRATEGY_ID, h.instrumentKey(),
            l.instrumentKey(), asOf, rounded(credit), rounded(breakeven),
            rounded(maxLoss), rounded(maxGain), rounded(rr));
    }
    private static Decision blocked(Status status, String reason, Instant asOf) {
        return new Decision(status, reason, STRATEGY_ID, null, null, asOf,
            null, null, null, null, null);
    }
}
