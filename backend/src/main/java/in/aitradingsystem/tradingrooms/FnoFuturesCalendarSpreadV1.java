package in.aitradingsystem.tradingrooms;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import in.aitradingsystem.marketdata.FnoMarketDataProvider;
import java.util.Objects;

/**
 * STR-171 — deterministic, research-only NSE futures calendar spread.
 *
 * <p>Compares two genuine exchange-listed futures contracts on the exact same
 * underlying, with distinct expiries, using separately observed as-of L1 quotes.
 * This is an indicative spread-versus-carry comparison, NOT risk-free arbitrage.
 * Both legs can face basis changes, separate margin/liquidity/settlement risks
 * and non-atomic fills. There are no broker/order/persistence integrations.
 *
 * <p>This evaluator intentionally accepts only canonical, independently
 * rights-verified market snapshots supplied by a protected backend adapter.
 * It does NOT activate a provider feed or grant any market-data use rights.
 */
public final class FnoFuturesCalendarSpreadV1 {
    public static final String STRATEGY_ID = "strategy.STR-171";
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final BigDecimal BPS = new BigDecimal("10000");
    private static final BigDecimal DAYS_IN_YEAR = new BigDecimal("365");
    private static final Duration MAX_AGE = Duration.ofSeconds(15);
    private static final Duration MAX_CROSS_LEG_SKEW = Duration.ofSeconds(10);

    private FnoFuturesCalendarSpreadV1() {}

    public enum Status { BLOCKED_RIGHTS, BLOCKED_DATA, NO_EDGE, RESEARCH_CANDIDATE }
    public enum Direction { NONE, LONG_NEAR_SHORT_FAR, SHORT_NEAR_LONG_FAR }

    /** Provider-verifiable futures contract reference; lot size is underlying units. */
    public record Contract(String instrumentKey, String underlyingKey,
                           LocalDate expiry, int lotSize, boolean providerVerified) {}

    /** Quote prices are INR per underlying unit, not the total contract value. */
    public record Quote(String instrumentKey, String source, BigDecimal bid, BigDecimal ask,
                        Instant exchangeAt, Instant observedAt) {}

    /**
     * Separate source-use entitlement per leg; a LIVE order or account F&O flag
     * is NOT inferred from these research permissions.
     */
    public record SourceRights(boolean nearResearchUseVerified,
                               boolean farResearchUseVerified,
                               boolean contractDiscoveryVerified,
                               boolean exchangeSessionVerified) {}

    /** Financing/dividend are annual bps; costs/hurdle are round-trip spread bps. */
    public record Assumptions(BigDecimal annualFundingBps, BigDecimal annualDividendYieldBps,
                              BigDecimal roundTripCostsBps, BigDecimal minNetEdgeBps) {}

    public record Decision(Status status, Direction direction, String reasonCode,
                           String strategyId, Instant asOf, LocalDate nearExpiry,
                           LocalDate farExpiry, int calendarDays, String source,
                           BigDecimal longNearShortFarNetBps, BigDecimal shortNearLongFarNetBps,
                           BigDecimal selectedNetBps, BigDecimal illustrativeNetPerMatchedLotInr) {
        /** An indicative edge never permits order execution. */
        public boolean executable() { return false; }
        public boolean orderPlaced() { return false; }
    }

    /**
     * All prices/timestamps/contract identities must be verified at or before asOf.
     * A positive result is a research hypothesis, never a simultaneous fill promise.
     */
    public static Decision evaluate(Contract near, Contract far, Quote nearQuote,
                                    Quote farQuote, SourceRights rights,
                                    Assumptions assumptions, Instant asOf) {
        Objects.requireNonNull(asOf, "asOf");
        if (rights == null || !rights.nearResearchUseVerified()
                || !rights.farResearchUseVerified() || !rights.contractDiscoveryVerified()) {
            return blocked(Status.BLOCKED_RIGHTS, "CALENDAR_SOURCE_RIGHTS_NOT_VERIFIED", asOf);
        }
        if (!rights.exchangeSessionVerified()) {
            return blocked(Status.BLOCKED_DATA, "FNO_EXCHANGE_SESSION_NOT_VERIFIED", asOf);
        }
        if (!validContracts(near, far, asOf)) {
            return blocked(Status.BLOCKED_DATA, "CALENDAR_CONTRACT_PAIR_INVALID", asOf);
        }
        if (!validQuote(nearQuote, near.instrumentKey(), asOf)
                || !validQuote(farQuote, far.instrumentKey(), asOf)) {
            return blocked(Status.BLOCKED_DATA, "CALENDAR_QUOTE_INVALID_OR_STALE", asOf);
        }
        if (!nearQuote.source().equals(farQuote.source())) {
            return blocked(Status.BLOCKED_DATA, "CALENDAR_QUOTE_SOURCE_MISMATCH", asOf);
        }
        if (Duration.between(nearQuote.exchangeAt(), farQuote.exchangeAt())
                .abs().compareTo(MAX_CROSS_LEG_SKEW) > 0) {
            return blocked(Status.BLOCKED_DATA, "CALENDAR_QUOTE_TIME_SKEW", asOf);
        }
        if (!validAssumptions(assumptions)) {
            return blocked(Status.BLOCKED_DATA, "CALENDAR_COST_ASSUMPTIONS_INVALID", asOf);
        }

        long days = ChronoUnit.DAYS.between(near.expiry(), far.expiry());
        BigDecimal fraction = BigDecimal.valueOf(days).divide(DAYS_IN_YEAR, MC);
        BigDecimal annualNetCarry = assumptions.annualFundingBps()
            .subtract(assumptions.annualDividendYieldBps(), MC).divide(BPS, MC);
        BigDecimal periodNetCarry = annualNetCarry.multiply(fraction, MC);
        BigDecimal costRate = assumptions.roundTripCostsBps().divide(BPS, MC);

        // Buy near at ASK, sell far at BID. Fair spread is an estimated
        // near-to-far financing/dividend relationship, not a certain settlement.
        BigDecimal nearPurchase = nearQuote.ask();
        BigDecimal expensiveFarNet = farQuote.bid().subtract(
            nearPurchase.multiply(BigDecimal.ONE.add(periodNetCarry, MC), MC), MC)
            .subtract(nearPurchase.multiply(costRate, MC), MC);
        BigDecimal expensiveFarBps = expensiveFarNet.multiply(BPS, MC)
            .divide(nearPurchase, MC);

        // Short near at BID, buy far at ASK. Both legs are futures; this does
        // NOT assume an overnight cash-equity short or stock-loan permission.
        BigDecimal nearSale = nearQuote.bid();
        BigDecimal cheapFarNet = nearSale.multiply(periodNetCarry, MC)
            .subtract(farQuote.ask().subtract(nearSale, MC), MC)
            .subtract(nearSale.multiply(costRate, MC), MC);
        BigDecimal cheapFarBps = cheapFarNet.multiply(BPS, MC)
            .divide(nearSale, MC);

        boolean expensive = expensiveFarBps.compareTo(assumptions.minNetEdgeBps()) > 0;
        boolean cheap = cheapFarBps.compareTo(assumptions.minNetEdgeBps()) > 0;
        if (!expensive && !cheap) {
            return new Decision(Status.NO_EDGE, Direction.NONE,
                "CALENDAR_EDGE_BELOW_COST_AND_HURDLE", STRATEGY_ID, asOf,
                near.expiry(), far.expiry(), Math.toIntExact(days), nearQuote.source(),
                decimal(expensiveFarBps), decimal(cheapFarBps), null, null);
        }

        boolean sellFar = expensive && (!cheap || expensiveFarBps.compareTo(cheapFarBps) >= 0);
        BigDecimal selectedNet = sellFar ? expensiveFarNet : cheapFarNet;
        BigDecimal selectedBps = sellFar ? expensiveFarBps : cheapFarBps;
        return new Decision(Status.RESEARCH_CANDIDATE,
            sellFar ? Direction.LONG_NEAR_SHORT_FAR : Direction.SHORT_NEAR_LONG_FAR,
            sellFar ? "FAR_FUTURE_RICH_RESEARCH_EDGE" : "FAR_FUTURE_CHEAP_RESEARCH_EDGE",
            STRATEGY_ID, asOf, near.expiry(), far.expiry(), Math.toIntExact(days),
            nearQuote.source(), decimal(expensiveFarBps), decimal(cheapFarBps),
            decimal(selectedBps), selectedNet.multiply(BigDecimal.valueOf(near.lotSize()), MC)
                .setScale(2, RoundingMode.HALF_UP));
    }

    private static boolean validContracts(Contract near, Contract far, Instant asOf) {
        if (near == null || far == null || !validContract(near) || !validContract(far)
                || near.instrumentKey().equals(far.instrumentKey())
                || !near.underlyingKey().equals(far.underlyingKey())
                || near.lotSize() != far.lotSize()
                || !near.expiry().isBefore(far.expiry())) return false;
        LocalDate today = asOf.atZone(IST).toLocalDate();
        long toNear = ChronoUnit.DAYS.between(today, near.expiry());
        long toFar = ChronoUnit.DAYS.between(today, far.expiry());
        long between = ChronoUnit.DAYS.between(near.expiry(), far.expiry());
        return toNear >= 3 && toFar <= 180 && between >= 1 && between <= 120;
    }

    private static boolean validContract(Contract c) {
        if (!c.providerVerified() || c.instrumentKey() == null
                || !c.instrumentKey().matches("NSE_FO\\|[A-Za-z0-9._-]{1,64}")
                || c.underlyingKey() == null
                || !c.underlyingKey().matches(
                    "(?:NSE_EQ\\|[A-Za-z0-9._-]{1,64}|NSE_INDEX\\|[A-Za-z0-9 ._-]{1,64})")
                || c.expiry() == null || c.lotSize() <= 0 || c.lotSize() > 100000) return false;
        return true;
    }

    private static boolean validQuote(Quote q, String expectedKey, Instant asOf) {
        return q != null && expectedKey.equals(q.instrumentKey())
            && FnoMarketDataProvider.isQuoteSource(q.source())
            && positive(q.bid()) && positive(q.ask()) && q.ask().compareTo(q.bid()) >= 0
            && q.exchangeAt() != null && q.observedAt() != null
            && !q.exchangeAt().isAfter(q.observedAt())
            && !q.observedAt().isAfter(asOf)
            && Duration.between(q.exchangeAt(), asOf).compareTo(MAX_AGE) <= 0
            && Duration.between(q.observedAt(), asOf).compareTo(MAX_AGE) <= 0;
    }

    private static boolean validAssumptions(Assumptions a) {
        return a != null && bounded(a.annualFundingBps(), 0, 5000)
            && bounded(a.annualDividendYieldBps(), 0, 10000)
            && bounded(a.roundTripCostsBps(), 1, 3000)
            && bounded(a.minNetEdgeBps(), 1, 2000);
    }

    private static boolean positive(BigDecimal x) { return x != null && x.signum() > 0; }
    private static boolean bounded(BigDecimal x, int min, int max) {
        return x != null && x.compareTo(BigDecimal.valueOf(min)) >= 0
            && x.compareTo(BigDecimal.valueOf(max)) <= 0;
    }
    private static BigDecimal decimal(BigDecimal x) {
        return x.setScale(4, RoundingMode.HALF_UP);
    }
    private static Decision blocked(Status status, String reason, Instant asOf) {
        return new Decision(status, Direction.NONE, reason, STRATEGY_ID,
            asOf, null, null, 0, null, null, null, null, null);
    }
}
