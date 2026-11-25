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
 * STR-187: research-only Protective Put on an already-owned, physically matching
 * deliverable equity. This is NOT a naked long put, nor an index option
 * misrepresented as a hedge against an unrelated stock.
 *
 * One confirmed lot of underlying shares and one corresponding listed PE lot.
 * The downside scenario is illustrative at expiry, not a forecast or trade.
 */
public final class FnoProtectivePutV1 {
    public static final String STRATEGY_ID = "strategy.STR-187";
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal BPS = new BigDecimal("10000");
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime CLOSE = LocalTime.of(15,30);
    private static final Duration MAX_QUOTE_AGE = Duration.ofSeconds(180);
    private static final Duration MAX_SKEW = Duration.ofSeconds(90);
    private static final Duration MAX_PUBLICATION_LAG = Duration.ofHours(36);
    private FnoProtectivePutV1() {}

    public enum Status { BLOCKED_RIGHTS, BLOCKED_DATA, NO_SIGNAL, RESEARCH_CANDIDATE }

    /** Verified equity contract must match the actual shares held, not an index proxy. */
    public record PutContract(String instrumentKey, String underlyingKey, String optionType,
                              BigDecimal strike, BigDecimal tickSize, LocalDate listedFrom,
                              LocalDate expiry, int lotSize, boolean verified,
                              boolean physicalDeliveryMatchVerified) {}

    /** Broker-derived owned shares; no user-typed, paper or presumed holdings. */
    public record Holding(String broker, String underlyingKey, int settledOwnedShares,
                          BigDecimal averageAcquisitionCost, Instant sourceObservedAt,
                          boolean brokerReconciled, boolean deliverableVerified) {}

    public record CompletedClose(LocalDate session, String provider, BigDecimal close,
                                 Instant exchangeCloseAt, Instant firstKnownAt,
                                 boolean sessionVerified) {}

    public record EquityQuote(String provider, String underlyingKey, BigDecimal price,
                              Instant exchangeAt, Instant firstKnownAt) {}

    public record PutQuote(String provider, String instrumentKey, BigDecimal bid,
                           BigDecimal ask, long askDepthLots, long openInterestLots,
                           Instant exchangeAt, Instant firstKnownAt) {}

    public record SourceRights(boolean equityResearch, boolean optionChainResearch,
                               boolean instrumentResearch, boolean holdingsResearch) {}

    /** Costs cover one put purchase and assumed expiry/settlement as appropriate. */
    public record Assumptions(int hypotheticalDownsideAtExpiryBps, int drawdownTriggerBps,
                              int buySlippageBps, int maxSpreadBps,
                              long minAskDepthLots, long minOpenInterestLots,
                              int maxStrikeDistanceBps, BigDecimal allInFeesPerLotInr,
                              BigDecimal researchCapitalInr, int maxPremiumAllocationBps,
                              int minHedgeBenefitPerLotInr) {}

    public record Decision(Status status, String reasonCode, String strategyId,
                           String underlyingKey, String putKey, Instant asOf,
                           BigDecimal currentHoldingValue, BigDecimal putPremiumAndFees,
                           BigDecimal hypotheticalUnhedgedExpiryValue,
                           BigDecimal hypotheticalProtectedExpiryValue,
                           BigDecimal hypotheticalHedgeBenefit,
                           BigDecimal hypotheticalProtectedExpiryFloor) {
        public boolean executable() { return false; }
        public boolean orderPlaced() { return false; }
        public boolean liveTradeAuthorized() { return false; }
    }

    public static Decision evaluate(PutContract contract, Holding holding,
                                    List<CompletedClose> bars, EquityQuote equity,
                                    PutQuote put, SourceRights rights,
                                    Assumptions a, Instant asOf) {
        Objects.requireNonNull(asOf, "asOf");
        if (rights == null || !rights.equityResearch() || !rights.optionChainResearch()
                || !rights.instrumentResearch() || !rights.holdingsResearch())
            return blocked(Status.BLOCKED_RIGHTS, "PROTECTIVE_PUT_RIGHTS_UNVERIFIED", asOf);
        if (!validContractAndHolding(contract, holding, asOf) || !validAssumptions(a))
            return blocked(Status.BLOCKED_DATA, "PROTECTIVE_PUT_OWNERSHIP_CONTRACT_INVALID", asOf);
        if (!validBars(bars, equity, asOf))
            return blocked(Status.BLOCKED_DATA, "PROTECTIVE_PUT_COMPLETED_HISTORY_INVALID", asOf);
        if (!validQuotes(contract, holding, bars.getLast(), equity, put, a, asOf))
            return blocked(Status.BLOCKED_DATA, "PROTECTIVE_PUT_QUOTES_ASOF_INVALID", asOf);

        // Peak of preceding 20 completed sessions, excluding last: no lookahead.
        BigDecimal peak = BigDecimal.ZERO;
        for (int i = bars.size()-21; i < bars.size()-1; i++)
            peak = peak.max(bars.get(i).close());
        BigDecimal last = bars.getLast().close();
        BigDecimal drawdownBps = peak.subtract(last, MC).multiply(BPS, MC)
            .divide(peak, MC);
        if (drawdownBps.compareTo(BigDecimal.valueOf(a.drawdownTriggerBps())) < 0)
            return noSignal("PROTECTIVE_PUT_DRAWDOWN_TRIGGER_ABSENT", contract, asOf);

        BigDecimal lots = BigDecimal.valueOf(contract.lotSize());
        BigDecimal paidPerShare = put.ask()
            .multiply(BPS.add(BigDecimal.valueOf(a.buySlippageBps()), MC), MC)
            .divide(BPS, MC);
        BigDecimal costs = paidPerShare.multiply(lots, MC)
            .add(a.allInFeesPerLotInr(), MC);
        BigDecimal budget = a.researchCapitalInr()
            .multiply(BigDecimal.valueOf(a.maxPremiumAllocationBps()), MC)
            .divide(BPS, MC);
        if (costs.compareTo(budget) > 0)
            return noSignal("PROTECTIVE_PUT_PREMIUM_BUDGET_EXCEEDED", contract, asOf);

        BigDecimal expirySpot = equity.price()
            .multiply(BPS.subtract(BigDecimal.valueOf(
                a.hypotheticalDownsideAtExpiryBps()), MC), MC).divide(BPS, MC);
        BigDecimal unhedged = expirySpot.multiply(lots, MC);
        BigDecimal putIntrinsic = contract.strike().subtract(expirySpot, MC)
            .max(BigDecimal.ZERO).multiply(lots, MC);
        BigDecimal protectedValue = unhedged.add(putIntrinsic, MC)
            .subtract(costs, MC);
        BigDecimal benefit = protectedValue.subtract(unhedged, MC);
        BigDecimal floor = contract.strike().multiply(lots, MC).subtract(costs, MC);

        if (benefit.compareTo(BigDecimal.valueOf(a.minHedgeBenefitPerLotInr())) < 0)
            return measured(Status.NO_SIGNAL, "PROTECTIVE_PUT_DOWNSIDE_SCENARIO_BENEFIT_TOO_LOW",
                contract, asOf, equity.price().multiply(lots, MC), costs,
                unhedged, protectedValue, benefit, floor);

        return measured(Status.RESEARCH_CANDIDATE, "PROTECTIVE_PUT_RESEARCH_ONLY",
            contract, asOf, equity.price().multiply(lots, MC), costs,
            unhedged, protectedValue, benefit, floor);
    }

    private static boolean validContractAndHolding(PutContract c, Holding h, Instant at) {
        if (c == null || h == null || !c.verified() || !c.physicalDeliveryMatchVerified()
                || !"PE".equals(c.optionType()) || !key(c.instrumentKey())
                || !equityKey(c.underlyingKey()) || !c.underlyingKey().equals(h.underlyingKey())
                || !provider(h.broker()) || !h.brokerReconciled() || !h.deliverableVerified()
                || h.settledOwnedShares() != c.lotSize() || h.settledOwnedShares() < 1
                || !positive(h.averageAcquisitionCost()) || !positive(c.strike())
                || !positive(c.tickSize()) || c.strike().remainder(c.tickSize()).signum() != 0
                || c.listedFrom() == null || c.expiry() == null || h.sourceObservedAt() == null
                || h.sourceObservedAt().isAfter(at)
                || Duration.between(h.sourceObservedAt(), at).compareTo(Duration.ofMinutes(5)) > 0
                || c.listedFrom().isAfter(at.atZone(IST).toLocalDate()))
            return false;
        long dte = ChronoUnit.DAYS.between(at.atZone(IST).toLocalDate(), c.expiry());
        return dte >= 3 && dte <= 45;
    }

    private static boolean validBars(List<CompletedClose> bars, EquityQuote equity, Instant at) {
        if (bars == null || bars.size() < 35 || bars.size() > 512) return false;
        LocalDate previous = null;
        String provider = null;
        for (var b : bars) {
            if (b == null || !b.sessionVerified() || b.session() == null
                    || b.session().getDayOfWeek().getValue() > 5 || !positive(b.close())
                    || !provider(b.provider()) || b.exchangeCloseAt() == null
                    || b.firstKnownAt() == null) return false;
            Instant close = b.session().atTime(CLOSE).atZone(IST).toInstant();
            if (!close.equals(b.exchangeCloseAt()) || b.firstKnownAt().isBefore(close)
                    || b.firstKnownAt().isAfter(close.plus(MAX_PUBLICATION_LAG))
                    || b.firstKnownAt().isAfter(at)
                    || provider != null && !provider.equals(b.provider())) return false;
            if (previous != null) {
                long days = ChronoUnit.DAYS.between(previous, b.session());
                if (days < 1 || days > 5) return false;
            }
            previous = b.session();
            provider = b.provider();
        }
        long age = ChronoUnit.DAYS.between(previous, at.atZone(IST).toLocalDate());
        return age >= 0 && age <= 4 && equity != null && provider.equals(equity.provider());
    }

    private static boolean validQuotes(PutContract c, Holding h, CompletedClose last,
                                       EquityQuote u, PutQuote q, Assumptions a, Instant at) {
        if (u == null || q == null || !c.underlyingKey().equals(u.underlyingKey())
                || !c.instrumentKey().equals(q.instrumentKey())
                || !u.provider().equals(q.provider()) || !u.provider().equals(h.broker())
                || !positive(u.price()) || !positive(q.bid()) || !positive(q.ask())
                || q.ask().compareTo(q.bid()) < 0
                || q.askDepthLots() < a.minAskDepthLots()
                || q.openInterestLots() < a.minOpenInterestLots()
                || !validTimestamp(u.exchangeAt(), u.firstKnownAt(), at)
                || !validTimestamp(q.exchangeAt(), q.firstKnownAt(), at)
                || last.firstKnownAt().isAfter(u.exchangeAt())
                || last.firstKnownAt().isAfter(q.exchangeAt()))
            return false;
        Duration skew = Duration.between(u.exchangeAt(), q.exchangeAt()).abs();
        if (skew.compareTo(MAX_SKEW) > 0) return false;
        BigDecimal mid = q.bid().add(q.ask(), MC).divide(BigDecimal.valueOf(2), MC);
        BigDecimal spread = q.ask().subtract(q.bid(), MC).multiply(BPS, MC).divide(mid, MC);
        BigDecimal distance = c.strike().subtract(u.price(), MC).abs()
            .multiply(BPS, MC).divide(u.price(), MC);
        return spread.compareTo(BigDecimal.valueOf(a.maxSpreadBps())) <= 0
            && distance.compareTo(BigDecimal.valueOf(a.maxStrikeDistanceBps())) <= 0;
    }

    private static boolean validTimestamp(Instant exchange, Instant observed, Instant at) {
        if (exchange == null || observed == null || exchange.isAfter(observed)
                || observed.isAfter(at) || exchange.isAfter(at)
                || Duration.between(exchange, at).compareTo(MAX_QUOTE_AGE) > 0) return false;
        var ist = exchange.atZone(IST);
        var clock = ist.toLocalTime();
        return ist.getDayOfWeek().getValue() <= 5
            && !clock.isBefore(LocalTime.of(9,15)) && !clock.isAfter(CLOSE);
    }

    private static boolean validAssumptions(Assumptions a) {
        return a != null && between(a.hypotheticalDownsideAtExpiryBps(), 100, 9500)
            && between(a.drawdownTriggerBps(), 100, 5000)
            && between(a.buySlippageBps(), 0, 1000)
            && between(a.maxSpreadBps(), 1, 3000)
            && a.minAskDepthLots() >= 1 && a.minOpenInterestLots() >= 1
            && between(a.maxStrikeDistanceBps(), 1, 2000)
            && positive(a.allInFeesPerLotInr())
            && positive(a.researchCapitalInr())
            && between(a.maxPremiumAllocationBps(), 100, 10000)
            && a.minHedgeBenefitPerLotInr() > 0;
    }

    private static boolean key(String v) {
        return v != null && v.matches("NSE_FO\\|[A-Za-z0-9._-]{1,64}");
    }
    private static boolean equityKey(String v) {
        return v != null && v.matches("NSE_EQ\\|[A-Za-z0-9._-]{1,64}");
    }
    private static boolean provider(String v) {
        return "UPSTOX_ANALYTICS".equals(v) || "ANGEL_ONE_SMARTAPI".equals(v);
    }
    private static boolean positive(BigDecimal v) { return v != null && v.signum() > 0; }
    private static boolean between(int v, int low, int high) { return v >= low && v <= high; }

    private static Decision noSignal(String code, PutContract c, Instant at) {
        return measured(Status.NO_SIGNAL, code, c, at,null,null,null,null,null,null);
    }
    private static Decision measured(Status status, String code, PutContract c, Instant at,
                                     BigDecimal current, BigDecimal cost, BigDecimal bare,
                                     BigDecimal hedged, BigDecimal benefit, BigDecimal floor) {
        return new Decision(status,code,STRATEGY_ID,c.underlyingKey(),c.instrumentKey(),
            at,rounded(current),rounded(cost),rounded(bare),rounded(hedged),
            rounded(benefit),rounded(floor));
    }
    private static BigDecimal rounded(BigDecimal n) {
        return n == null ? null : n.setScale(4, RoundingMode.HALF_UP);
    }
    private static Decision blocked(Status status,String code,Instant at) {
        return new Decision(status,code,STRATEGY_ID,null,null,at,
            null,null,null,null,null,null);
    }
}
