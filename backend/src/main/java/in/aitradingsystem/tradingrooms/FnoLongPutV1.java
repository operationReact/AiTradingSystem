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
 * STR-182: deterministic, research-only NIFTY Long Put (PE) classifier.
 * A completed EMA8/EMA21 bearish crossover is necessary but not sufficient:
 * one verified put contract and synchronized, recent quotes must support a
 * cost-aware hypothetical *expiry* payoff. Not a forecast or order proposal.
 * The adapter must independently attest true source rights and publication times.
 */
public final class FnoLongPutV1 {
    public static final String STRATEGY_ID = "strategy.STR-182";
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal BPS = new BigDecimal("10000");
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime OPEN = LocalTime.of(9, 15);
    private static final LocalTime CLOSE = LocalTime.of(15, 30);
    private static final Duration MAX_PUBLICATION_LAG = Duration.ofHours(36);
    private static final Duration MAX_QUOTE_AGE = Duration.ofSeconds(180);
    private static final Duration MAX_LEG_SKEW = Duration.ofSeconds(90);
    private FnoLongPutV1() {}

    public enum Status { BLOCKED_RIGHTS, BLOCKED_DATA, NO_SIGNAL, RESEARCH_CANDIDATE }

    public record Contract(String instrumentKey, String underlyingKey, String optionType,
                           BigDecimal strike, BigDecimal tickSize, LocalDate listedFrom,
                           LocalDate expiry, int lotSize, boolean contractVerified) {}

    /** Each completed close and its original first availability must be supplied. */
    public record CompletedClose(LocalDate session, String provider,
                                 Instant exchangeCloseAt, Instant firstKnownAt,
                                 BigDecimal close, boolean sessionVerified) {}

    public record UnderlyingQuote(String provider, String instrumentKey, BigDecimal price,
                                  Instant exchangeAt, Instant firstKnownAt) {}

    /** Premium in INR per unit; depth and OI expressed in lots, not shares. */
    public record PutQuote(String provider, String instrumentKey, BigDecimal bid,
                            BigDecimal ask, long askDepthLots, long openInterestLots,
                            Instant exchangeAt, Instant firstKnownAt) {}

    /** Research permissions are separate from display, retention, training and orders. */
    public record SourceRights(boolean underlyingResearchVerified,
                               boolean optionChainResearchVerified,
                               boolean contractDiscoveryVerified) {}

    /** Explicit scenario, NOT a predicted move. Fees include estimated Indian taxes. */
    public record Assumptions(int hypotheticalDownsideAtExpiryBps,
                              int buySlippageBps, BigDecimal allInFeesPerLotInr,
                              int minScenarioRewardRiskBps, int maxOptionSpreadBps,
                              int maxStrikeDistanceBps, long minAskDepthLots,
                              long minOpenInterestLots, BigDecimal researchCapitalInr,
                              int maxCapitalAllocationBps) {}

    public record Decision(Status status, String reasonCode, String strategyId,
                           String optionKey, String provider, LocalDate expiry,
                           Instant evaluatedAt, BigDecimal breakEvenAtExpiry,
                           BigDecimal maxModelLossPerLotInr,
                           BigDecimal hypotheticalExpiryPnlPerLotInr,
                           BigDecimal hypotheticalRewardRiskBps) {
        public boolean executable() { return false; }
        public boolean orderPlaced() { return false; }
        public boolean liveTradeAuthorized() { return false; }
    }

    public static Decision evaluate(Contract contract, List<CompletedClose> completedCloses,
                                    UnderlyingQuote underlying, PutQuote put,
                                    SourceRights rights, Assumptions assumptions,
                                    Instant asOf) {
        Objects.requireNonNull(asOf, "asOf");
        if (rights == null || !rights.underlyingResearchVerified()
                || !rights.optionChainResearchVerified()
                || !rights.contractDiscoveryVerified()) {
            return blocked(Status.BLOCKED_RIGHTS, "LONG_PUT_SOURCE_RIGHTS_UNVERIFIED", asOf);
        }
        if (!validContract(contract, asOf) || !validAssumptions(assumptions)) {
            return blocked(Status.BLOCKED_DATA, "LONG_PUT_CONTRACT_OR_ASSUMPTIONS_INVALID", asOf);
        }
        if (!validCloses(completedCloses, asOf)) {
            return blocked(Status.BLOCKED_DATA, "LONG_PUT_COMPLETED_CANDLES_INVALID", asOf);
        }
        String provider = completedCloses.getFirst().provider();
        if (!validQuotes(contract, underlying, put, provider, assumptions, asOf)
                || completedCloses.getLast().firstKnownAt().isAfter(underlying.exchangeAt())
                || completedCloses.getLast().firstKnownAt().isAfter(put.exchangeAt())) {
            return blocked(Status.BLOCKED_DATA, "LONG_PUT_QUOTE_OR_ASOF_INVALID", asOf);
        }

        var crossoverBars = new ArrayList<FnoLongOptionCrossoverV1.CompletedBar>();
        for (CompletedClose c : completedCloses) {
            crossoverBars.add(new FnoLongOptionCrossoverV1.CompletedBar(
                c.exchangeCloseAt(), c.firstKnownAt(), c.close()));
        }
        var trend = FnoLongOptionCrossoverV1.evaluate(
            FnoLongOptionCrossoverV1.UNDERLYING, true, crossoverBars, asOf);
        if (trend.status() == FnoLongOptionCrossoverV1.Status.BLOCKED_DATA) {
            return blocked(Status.BLOCKED_DATA, "LONG_PUT_UNDERLYING_CROSSOVER_DATA_INVALID", asOf);
        }
        if (trend.status() != FnoLongOptionCrossoverV1.Status.RESEARCH_CANDIDATE
                || !STRATEGY_ID.equals(trend.strategyId())) {
            return measured(Status.NO_SIGNAL, "LONG_PUT_NEW_BEARISH_CROSSOVER_ABSENT",
                contract, provider, asOf, null, null, null, null);
        }

        // The payoff is a scenario AT EXPIRY, not an option price forecast
        // during its lifetime. A long put's theoretical loss is capped at
        // the paid premium plus transaction taxes/fees; fillability is unknown.
        BigDecimal entry = put.ask().multiply(
            BPS.add(BigDecimal.valueOf(assumptions.buySlippageBps()), MC), MC)
            .divide(BPS, MC);
        BigDecimal feePerUnit = assumptions.allInFeesPerLotInr()
            .divide(BigDecimal.valueOf(contract.lotSize()), MC);
        BigDecimal breakeven = contract.strike().subtract(entry, MC).subtract(feePerUnit, MC);
        BigDecimal maxLoss = entry.multiply(BigDecimal.valueOf(contract.lotSize()), MC)
            .add(assumptions.allInFeesPerLotInr(), MC);
        BigDecimal allowedCapital = assumptions.researchCapitalInr()
            .multiply(BigDecimal.valueOf(assumptions.maxCapitalAllocationBps()), MC)
            .divide(BPS, MC);
        if (maxLoss.compareTo(allowedCapital) > 0) {
            return measured(Status.NO_SIGNAL, "LONG_PUT_PREMIUM_EXCEEDS_RESEARCH_BUDGET",
                contract, provider, asOf, breakeven, maxLoss, null, null);
        }
        BigDecimal target = underlying.price()
            .multiply(BPS.subtract(BigDecimal.valueOf(
                assumptions.hypotheticalDownsideAtExpiryBps()), MC), MC)
                .divide(BPS, MC);
        BigDecimal intrinsic = contract.strike().subtract(target, MC).max(BigDecimal.ZERO);
        BigDecimal hypotheticalPnl = intrinsic.multiply(
            BigDecimal.valueOf(contract.lotSize()), MC).subtract(maxLoss, MC);
        BigDecimal ratio = hypotheticalPnl.multiply(BPS, MC).divide(maxLoss, MC);
        boolean passes = hypotheticalPnl.signum() > 0 && ratio.compareTo(
            BigDecimal.valueOf(assumptions.minScenarioRewardRiskBps())) >= 0;
        return measured(passes ? Status.RESEARCH_CANDIDATE : Status.NO_SIGNAL,
            passes ? "LONG_PUT_BEARISH_SCENARIO_RESEARCH_ONLY"
                   : "LONG_PUT_EXPIRY_SCENARIO_AFTER_COSTS_INSUFFICIENT",
            contract, provider, asOf, breakeven, maxLoss, hypotheticalPnl, ratio);
    }

    private static boolean validContract(Contract c, Instant asOf) {
        if (c == null || !c.contractVerified() || !"PE".equals(c.optionType())
                || !FnoLongOptionCrossoverV1.UNDERLYING.equals(c.underlyingKey())
                || c.instrumentKey() == null
                || !c.instrumentKey().matches("NSE_FO\\|[A-Za-z0-9._-]{1,64}")
                || !positive(c.strike()) || !positive(c.tickSize())
                || c.strike().remainder(c.tickSize()).signum() != 0
                || c.lotSize() < 1 || c.lotSize() > 100000
                || c.listedFrom() == null || c.expiry() == null
                || c.listedFrom().isAfter(asOf.atZone(IST).toLocalDate())) return false;
        long dte = ChronoUnit.DAYS.between(asOf.atZone(IST).toLocalDate(), c.expiry());
        return dte >= 3 && dte <= 45 && !c.expiry().isBefore(c.listedFrom());
    }

    private static boolean validCloses(List<CompletedClose> bars, Instant asOf) {
        if (bars == null || bars.size() < 35 || bars.size() > 512) return false;
        LocalDate previous = null;
        String provider = null;
        for (CompletedClose b : bars) {
            if (b == null || !b.sessionVerified() || !supportedProvider(b.provider())
                    || b.session() == null || b.session().getDayOfWeek().getValue() > 5
                    || !positive(b.close()) || b.exchangeCloseAt() == null
                    || b.firstKnownAt() == null) return false;
            Instant close = b.session().atTime(CLOSE).atZone(IST).toInstant();
            if (!b.exchangeCloseAt().equals(close) || b.firstKnownAt().isBefore(close)
                    || b.firstKnownAt().isAfter(close.plus(MAX_PUBLICATION_LAG))
                    || b.firstKnownAt().isAfter(asOf) ||
                    (provider != null && !provider.equals(b.provider()))) return false;
            if (previous != null) {
                long gap = ChronoUnit.DAYS.between(previous, b.session());
                if (gap < 1 || gap > 5) return false;
            }
            provider = b.provider();
            previous = b.session();
        }
        return ChronoUnit.DAYS.between(previous, asOf.atZone(IST).toLocalDate()) <= 4;
    }

    private static boolean validQuotes(Contract c, UnderlyingQuote u, PutQuote q,
                                       String provider, Assumptions a, Instant asOf) {
        if (u == null || q == null || !provider.equals(u.provider())
                || !provider.equals(q.provider())
                || !c.underlyingKey().equals(u.instrumentKey())
                || !c.instrumentKey().equals(q.instrumentKey())
                || !positive(u.price()) || !positive(q.bid()) || !positive(q.ask())
                || q.ask().compareTo(q.bid()) < 0 || q.askDepthLots() < a.minAskDepthLots()
                || q.openInterestLots() < a.minOpenInterestLots()
                || !validTimestamp(u.exchangeAt(), u.firstKnownAt(), asOf)
                || !validTimestamp(q.exchangeAt(), q.firstKnownAt(), asOf)
                || Duration.between(u.exchangeAt(), q.exchangeAt()).abs()
                    .compareTo(MAX_LEG_SKEW) > 0) return false;
        BigDecimal mid = q.ask().add(q.bid(), MC).divide(BigDecimal.valueOf(2), MC);
        BigDecimal spread = q.ask().subtract(q.bid(), MC).multiply(BPS, MC)
            .divide(mid, MC);
        BigDecimal strikeDistance = c.strike().subtract(u.price(), MC).abs()
            .multiply(BPS, MC).divide(u.price(), MC);
        return spread.compareTo(BigDecimal.valueOf(a.maxOptionSpreadBps())) <= 0
            && strikeDistance.compareTo(BigDecimal.valueOf(a.maxStrikeDistanceBps())) <= 0;
    }

    private static boolean validTimestamp(Instant at, Instant seen, Instant asOf) {
        if (at == null || seen == null || at.isAfter(seen) || seen.isAfter(asOf)
                || Duration.between(at, asOf).isNegative()
                || Duration.between(at, asOf).compareTo(MAX_QUOTE_AGE) > 0) return false;
        var local = at.atZone(IST);
        LocalTime time = local.toLocalTime();
        return local.getDayOfWeek().getValue() <= 5 && !time.isBefore(OPEN)
            && !time.isAfter(CLOSE);
    }

    private static boolean validAssumptions(Assumptions a) {
        return a != null && between(a.hypotheticalDownsideAtExpiryBps(), 100, 3000)
            && between(a.buySlippageBps(), 0, 1000)
            && a.allInFeesPerLotInr() != null && a.allInFeesPerLotInr().signum() > 0
            && a.allInFeesPerLotInr().compareTo(BigDecimal.valueOf(100000)) <= 0
            && between(a.minScenarioRewardRiskBps(), 1000, 50000)
            && between(a.maxOptionSpreadBps(), 1, 3000)
            && between(a.maxStrikeDistanceBps(), 1, 3000)
            && a.minAskDepthLots() >= 1 && a.minOpenInterestLots() >= 1
            && positive(a.researchCapitalInr())
            && between(a.maxCapitalAllocationBps(), 100, 10000);
    }
    private static boolean positive(BigDecimal x) { return x != null && x.signum() > 0; }
    private static boolean between(int n, int lo, int hi) { return n >= lo && n <= hi; }
    private static boolean supportedProvider(String p) {
        return "UPSTOX_ANALYTICS".equals(p) || "ANGEL_ONE_SMARTAPI".equals(p);
    }
    private static BigDecimal rounded(BigDecimal x) {
        return x == null ? null : x.setScale(4, RoundingMode.HALF_UP);
    }
    private static Decision measured(Status status, String reason, Contract c,
                                     String provider, Instant asOf,
                                     BigDecimal breakEven, BigDecimal maxLoss,
                                     BigDecimal pnl, BigDecimal ratio) {
        return new Decision(status, reason, STRATEGY_ID, c.instrumentKey(), provider,
            c.expiry(), asOf, rounded(breakEven), rounded(maxLoss),
            rounded(pnl), rounded(ratio));
    }
    private static Decision blocked(Status status, String reason, Instant asOf) {
        return new Decision(status, reason, STRATEGY_ID, null, null, null,
            asOf, null, null, null, null);
    }
}
