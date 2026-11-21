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
 * STR-189 — owned-share equity collar research ONLY.
 * Retain an already-settled share lot, buy matching lower-strike PE,
 * sell matching higher-strike CE. Never creates an order.
 * Three-leg expiry payoff is hypothetical and requires both options to fill.
 */
public final class FnoEquityCollarV1 {
    public static final String STRATEGY_ID = "strategy.STR-189";
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal BPS = new BigDecimal("10000");
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime CLOSE = LocalTime.of(15, 30);
    private static final Duration QUOTE_MAX_AGE = Duration.ofSeconds(180);
    private static final Duration MAX_LEG_SKEW = Duration.ofSeconds(90);
    private static final Duration BAR_PUBLISH_MAX_LAG = Duration.ofHours(36);
    private FnoEquityCollarV1() {}

    public enum Status { BLOCKED_RIGHTS, BLOCKED_DATA, NO_SIGNAL, RESEARCH_CANDIDATE }

    /** Original listed NSE equity option identity and physical delivery association. */
    public record Contract(String key, String underlying, String type,
                           BigDecimal strike, BigDecimal tick,
                           LocalDate listedFrom, LocalDate expiry,
                           int lotSize, boolean sourceVerified,
                           boolean matchingPhysicalDeliveryVerified) {}

    public record Holding(String provider, String equityKey, int settledDeliverableShares,
                          BigDecimal averageAcquisitionCost, Instant observedAt,
                          boolean brokerReconciled, boolean sharesUnrestricted) {}

    public record Bar(LocalDate session, String provider, BigDecimal close,
                      Instant exchangeCloseAt, Instant firstKnownAt,
                      boolean completedSessionVerified) {}

    public record Quote(String provider, String key, BigDecimal bid, BigDecimal ask,
                        long bidDepthLots, long askDepthLots, long oiLots,
                        Instant exchangeAt, Instant firstKnownAt) {}

    public record Rights(boolean equityResearch, boolean optionChainResearch,
                         boolean instrumentResearch, boolean holdingsResearch) {}

    /** Nonzero modeled combined fees: no tax-free or atomic fill assumption. */
    public record Assumptions(int downsideScenarioBps, int drawdownTriggerBps,
                              int putBuySlippageBps, int callSellSlippageBps,
                              int maxEachOptionSpreadBps, long minBidAskDepthLots,
                              long minOpenInterestLots, int maxStrikeDistanceBps,
                              BigDecimal allInFeesPerCollarLotInr,
                              BigDecimal researchCapitalInr, int maxStockNotionalBps,
                              int maxNetDebitBpsOfCapital) {}

    public record Decision(Status status, String reasonCode, String strategyId,
                           String underlyingKey, String longPutKey,
                           String shortCallKey, Instant asOf,
                           BigDecimal netDebitAfterCostsPerLot,
                           BigDecimal hypotheticalEquityOnlyExpiryValue,
                           BigDecimal hypotheticalCollarExpiryValue,
                           BigDecimal theoreticalExpiryFloorValue,
                           BigDecimal theoreticalExpiryCeilingValue) {
        public boolean executable() { return false; }
        public boolean orderPlaced() { return false; }
        public boolean liveTradeAuthorized() { return false; }
    }

    public static Decision evaluate(Contract put, Contract call, Holding holding,
                                    List<Bar> completedDailyEquityCloses,
                                    Quote equity, Quote putMarket, Quote callMarket,
                                    Rights rights, Assumptions a, Instant asOf) {
        Objects.requireNonNull(asOf, "asOf");
        if (rights == null || !rights.equityResearch() || !rights.optionChainResearch()
                || !rights.instrumentResearch() || !rights.holdingsResearch())
            return blocked(Status.BLOCKED_RIGHTS, "COLLAR_RIGHTS_UNVERIFIED", asOf);
        if (!contractsAndSharesValid(put, call, holding, asOf) || !assumptionsValid(a))
            return blocked(Status.BLOCKED_DATA, "COLLAR_CONTRACT_SHARES_INVALID", asOf);
        if (!historyValid(completedDailyEquityCloses, equity, asOf))
            return blocked(Status.BLOCKED_DATA, "COLLAR_COMPLETED_EQUITY_HISTORY_INVALID", asOf);
        if (!quotesValid(put, call, holding, completedDailyEquityCloses.getLast(),
                equity, putMarket, callMarket, a, asOf))
            return blocked(Status.BLOCKED_DATA, "COLLAR_QUOTES_OR_ASOF_INVALID", asOf);

        // A point-in-time completed-session drawdown, not future historical prices.
        BigDecimal peak = BigDecimal.ZERO;
        for (int i = completedDailyEquityCloses.size() - 21;
             i < completedDailyEquityCloses.size() - 1; i++)
            peak = peak.max(completedDailyEquityCloses.get(i).close());
        BigDecimal last = completedDailyEquityCloses.getLast().close();
        BigDecimal drawdownBps = peak.subtract(last, MC).multiply(BPS, MC).divide(peak, MC);
        if (drawdownBps.compareTo(BigDecimal.valueOf(a.drawdownTriggerBps())) < 0)
            return measured(Status.NO_SIGNAL, "COLLAR_RISK_TRIGGER_ABSENT",
                put, call, asOf, null, null, null, null, null);

        BigDecimal lot = BigDecimal.valueOf(put.lotSize());
        BigDecimal paidPut = putMarket.ask().multiply(
            BPS.add(BigDecimal.valueOf(a.putBuySlippageBps()), MC), MC).divide(BPS, MC);
        BigDecimal receivedCall = callMarket.bid().multiply(
            BPS.subtract(BigDecimal.valueOf(a.callSellSlippageBps()), MC), MC).divide(BPS, MC);
        BigDecimal cost = paidPut.subtract(receivedCall, MC).multiply(lot, MC)
            .add(a.allInFeesPerCollarLotInr(), MC); // may be a net CREDIT
        BigDecimal capital = a.researchCapitalInr();
        BigDecimal maxStockNotional = capital.multiply(
            BigDecimal.valueOf(a.maxStockNotionalBps()), MC).divide(BPS, MC);
        BigDecimal currentStockNotional = equity.bid().multiply(lot, MC);
        if (currentStockNotional.compareTo(maxStockNotional) > 0)
            return measured(Status.NO_SIGNAL, "COLLAR_STOCK_NOTIONAL_EXCEEDS_BUDGET",
                put, call, asOf, cost, null, null, null, null);
        BigDecimal maxNetDebit = capital.multiply(
            BigDecimal.valueOf(a.maxNetDebitBpsOfCapital()), MC).divide(BPS, MC);
        if (cost.compareTo(maxNetDebit) > 0)
            return measured(Status.NO_SIGNAL, "COLLAR_COST_EXCEEDS_BUDGET",
                put, call, asOf, cost, null, null, null, null);

        BigDecimal scenarioSpot = equity.bid().multiply(
            BPS.subtract(BigDecimal.valueOf(a.downsideScenarioBps()), MC), MC).divide(BPS, MC);
        BigDecimal onlyStock = scenarioSpot.multiply(lot, MC);
        BigDecimal expiryCollar = scenarioSpot.max(put.strike()).min(call.strike())
            .multiply(lot, MC).subtract(cost, MC);
        BigDecimal floor = put.strike().multiply(lot, MC).subtract(cost, MC);
        BigDecimal ceiling = call.strike().multiply(lot, MC).subtract(cost, MC);
        return measured(Status.RESEARCH_CANDIDATE, "COLLAR_RESEARCH_ONLY",
            put, call, asOf, cost, onlyStock, expiryCollar, floor, ceiling);
    }

    private static boolean contractsAndSharesValid(Contract p, Contract c, Holding h, Instant at) {
        if (p == null || c == null || h == null || !p.sourceVerified() || !c.sourceVerified()
                || !p.matchingPhysicalDeliveryVerified() || !c.matchingPhysicalDeliveryVerified()
                || !"PE".equals(p.type()) || !"CE".equals(c.type())
                || !optionKey(p.key()) || !optionKey(c.key()) || p.key().equals(c.key())
                || !equityKey(p.underlying()) || !p.underlying().equals(c.underlying())
                || !p.underlying().equals(h.equityKey())
                || !positive(p.strike()) || !positive(c.strike())
                || p.strike().compareTo(c.strike()) >= 0
                || !positive(p.tick()) || !positive(c.tick())
                || p.strike().remainder(p.tick()).signum() != 0
                || c.strike().remainder(c.tick()).signum() != 0
                || p.lotSize() < 1 || p.lotSize() != c.lotSize()
                || h.settledDeliverableShares() != p.lotSize()
                || !positive(h.averageAcquisitionCost()) || !h.brokerReconciled()
                || !h.sharesUnrestricted() || !provider(h.provider())
                || h.observedAt() == null || h.observedAt().isAfter(at)
                || Duration.between(h.observedAt(), at).compareTo(Duration.ofMinutes(5)) > 0
                || p.expiry() == null || !p.expiry().equals(c.expiry())
                || p.listedFrom() == null || c.listedFrom() == null
                || p.listedFrom().isAfter(at.atZone(IST).toLocalDate())
                || c.listedFrom().isAfter(at.atZone(IST).toLocalDate())) return false;
        long dte = ChronoUnit.DAYS.between(at.atZone(IST).toLocalDate(), p.expiry());
        return dte >= 3 && dte <= 45;
    }

    private static boolean historyValid(List<Bar> bars, Quote equity, Instant at) {
        if (bars == null || bars.size() < 35 || bars.size() > 512 || equity == null)
            return false;
        LocalDate prior = null;
        String provider = null;
        for (Bar b : bars) {
            if (b == null || !b.completedSessionVerified() || b.session() == null
                    || b.session().getDayOfWeek().getValue() > 5
                    || !provider(b.provider()) || !positive(b.close())
                    || b.exchangeCloseAt() == null || b.firstKnownAt() == null)
                return false;
            Instant close = b.session().atTime(CLOSE).atZone(IST).toInstant();
            if (!b.exchangeCloseAt().equals(close) || b.firstKnownAt().isBefore(close)
                    || b.firstKnownAt().isAfter(close.plus(BAR_PUBLISH_MAX_LAG))
                    || b.firstKnownAt().isAfter(at)
                    || provider != null && !provider.equals(b.provider())) return false;
            if (prior != null) {
                long gap = ChronoUnit.DAYS.between(prior, b.session());
                if (gap < 1 || gap > 5) return false;
            }
            prior = b.session();
            provider = b.provider();
        }
        long ageDays = ChronoUnit.DAYS.between(prior, at.atZone(IST).toLocalDate());
        return ageDays >= 0 && ageDays <= 4 && provider.equals(equity.provider());
    }

    private static boolean quotesValid(Contract p, Contract c, Holding h, Bar last,
                                       Quote eq, Quote pq, Quote cq, Assumptions a, Instant at) {
        if (eq == null || pq == null || cq == null
                || !h.provider().equals(eq.provider())
                || !h.provider().equals(pq.provider()) || !h.provider().equals(cq.provider())
                || !h.equityKey().equals(eq.key())
                || !p.key().equals(pq.key()) || !c.key().equals(cq.key())
                || !validQuote(eq, 1, 1, Integer.MAX_VALUE)
                || !validQuote(pq, a.minBidAskDepthLots(), a.minOpenInterestLots(),
                    a.maxEachOptionSpreadBps())
                || !validQuote(cq, a.minBidAskDepthLots(), a.minOpenInterestLots(),
                    a.maxEachOptionSpreadBps())
                || !validTime(eq.exchangeAt(), eq.firstKnownAt(), at)
                || !validTime(pq.exchangeAt(), pq.firstKnownAt(), at)
                || !validTime(cq.exchangeAt(), cq.firstKnownAt(), at)
                || last.firstKnownAt().isAfter(eq.exchangeAt())
                || last.firstKnownAt().isAfter(pq.exchangeAt())
                || last.firstKnownAt().isAfter(cq.exchangeAt())) return false;
        Instant first = eq.exchangeAt();
        Instant lastAt = eq.exchangeAt();
        for (var t : List.of(pq.exchangeAt(), cq.exchangeAt())) {
            if (t.isBefore(first)) first = t;
            if (t.isAfter(lastAt)) lastAt = t;
        }
        if (Duration.between(first, lastAt).compareTo(MAX_LEG_SKEW) > 0) return false;
        BigDecimal spot = eq.bid();
        BigDecimal pd = p.strike().subtract(spot, MC).abs().multiply(BPS, MC).divide(spot, MC);
        BigDecimal cd = c.strike().subtract(spot, MC).abs().multiply(BPS, MC).divide(spot, MC);
        return pd.compareTo(BigDecimal.valueOf(a.maxStrikeDistanceBps())) <= 0
            && cd.compareTo(BigDecimal.valueOf(a.maxStrikeDistanceBps())) <= 0;
    }

    private static boolean validQuote(Quote q, long depth, long oi, int maxSpreadBps) {
        if (!positive(q.bid()) || !positive(q.ask()) || q.ask().compareTo(q.bid()) < 0
                || q.bidDepthLots() < depth || q.askDepthLots() < depth
                || q.oiLots() < oi) return false;
        BigDecimal mid = q.bid().add(q.ask(), MC).divide(BigDecimal.valueOf(2), MC);
        BigDecimal spread = q.ask().subtract(q.bid(), MC).multiply(BPS, MC).divide(mid, MC);
        return spread.compareTo(BigDecimal.valueOf(maxSpreadBps)) <= 0;
    }

    private static boolean validTime(Instant exchangeAt, Instant firstKnownAt, Instant at) {
        if (exchangeAt == null || firstKnownAt == null || exchangeAt.isAfter(firstKnownAt)
                || firstKnownAt.isAfter(at) || exchangeAt.isAfter(at)
                || Duration.between(exchangeAt, at).compareTo(QUOTE_MAX_AGE) > 0) return false;
        var ist = exchangeAt.atZone(IST);
        var local = ist.toLocalTime();
        return ist.getDayOfWeek().getValue() <= 5
            && !local.isBefore(LocalTime.of(9, 15)) && !local.isAfter(CLOSE);
    }

    private static boolean assumptionsValid(Assumptions a) {
        return a != null && between(a.downsideScenarioBps(), 0, 9500)
            && between(a.drawdownTriggerBps(), 100, 5000)
            && between(a.putBuySlippageBps(), 0, 1000)
            && between(a.callSellSlippageBps(), 0, 1000)
            && between(a.maxEachOptionSpreadBps(), 1, 3000)
            && a.minBidAskDepthLots() >= 1 && a.minOpenInterestLots() >= 1
            && between(a.maxStrikeDistanceBps(), 1, 3000)
            && positive(a.allInFeesPerCollarLotInr())
            && positive(a.researchCapitalInr())
            && between(a.maxStockNotionalBps(), 100, 10000)
            && between(a.maxNetDebitBpsOfCapital(), 100, 10000);
    }

    private static boolean optionKey(String v) {
        return v != null && v.matches("NSE_FO\\|[A-Za-z0-9._-]{1,64}");
    }
    private static boolean equityKey(String v) {
        return v != null && v.matches("NSE_EQ\\|[A-Za-z0-9._-]{1,64}");
    }
    private static boolean provider(String v) {
        return "UPSTOX_ANALYTICS".equals(v) || "ANGEL_ONE_SMARTAPI".equals(v);
    }
    private static boolean positive(BigDecimal v) { return v != null && v.signum() > 0; }
    private static boolean between(int v, int min, int max) { return v >= min && v <= max; }
    private static BigDecimal rounded(BigDecimal v) {
        return v == null ? null : v.setScale(4, RoundingMode.HALF_UP);
    }
    private static Decision measured(Status status, String reason, Contract p, Contract c,
                                     Instant at, BigDecimal cost, BigDecimal bare,
                                     BigDecimal collar, BigDecimal floor, BigDecimal ceiling) {
        return new Decision(status, reason, STRATEGY_ID, p.underlying(), p.key(), c.key(),
            at, rounded(cost), rounded(bare), rounded(collar), rounded(floor), rounded(ceiling));
    }
    private static Decision blocked(Status status, String reason, Instant at) {
        return new Decision(status, reason, STRATEGY_ID, null, null, null,
            at, null, null, null, null, null);
    }
}
