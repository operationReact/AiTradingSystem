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
 * STR-180 — research-only expiry basis reversion, not an arbitrage order.
 *
 * <p>Five source-verified completed-session, SAME single-stock NSE_EQ spot /
 * NSE_FO futures closing quote pairs are compared against a time-decaying
 * financing/dividend fair-basis estimate. A candidate requires the signed
 * carry-adjusted residual to stay on the same side of theoretical fair value
 * and shrink across at least three steps, including the final two.
 *
 * <p>Unlike STR-170's single instant cash-and-carry assessment, this observes
 * longitudinal convergence; it never models realized P&L or fillability.
 * Missing original publication times, rights, settlement/dividend evidence or
 * contract continuity fail closed. No broker, orders, API, or database access.
 */
public final class FnoExpiryBasisReversionV1 {
    public static final String STRATEGY_ID = "strategy.STR-180";
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal BPS = new BigDecimal("10000");
    private static final BigDecimal DAYS_IN_YEAR = new BigDecimal("365");
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    // Both legs' near-close L1 quotes are measured in the COMMON cash session
    // 15:25–15:30 window. They are not the later futures settlement price.
    private static final LocalTime CASH_CLOSE = LocalTime.of(15, 30);
    private static final LocalDate FUTURES_CLOSE_SHIFT_AUG = LocalDate.of(2026, 8, 3);
    private static final LocalDate FUTURES_CLOSE_SHIFT_DEC = LocalDate.of(2026, 12, 7);
    private static final int SESSIONS = 5;
    private static final Duration MAX_PUBLICATION_LAG = Duration.ofHours(36);
    private static final Duration MAX_CLOSE_WINDOW = Duration.ofMinutes(5);
    private static final Duration MAX_PAIR_SKEW = Duration.ofMinutes(2);

    private FnoExpiryBasisReversionV1() {}

    public enum Status { BLOCKED_RIGHTS, BLOCKED_DATA, NO_SIGNAL, RESEARCH_CANDIDATE }
    public enum Pattern { NONE, ABOVE_FAIR_CONVERGING, BELOW_FAIR_CONVERGING }

    /** This version excludes index-spot substitution and reverse stock borrow. */
    public record Contract(String spotKey, String futuresKey, String futuresUnderlyingSpotKey,
                           LocalDate listedFrom, LocalDate expiry, int lotSize,
                           boolean contractVerified, boolean deliverableSpotVerified) {}

    /** Verified exchange closing quote; prices are INR per underlying share. */
    public record Quote(BigDecimal bid, BigDecimal ask, Instant exchangeAt,
                        Instant firstObservedAt) {}

    /**
     * Both first-finalized timestamps are the ORIGINAL provider publication
     * instants for finalized session-close representations, never download time.
     */
    public record CompletedPair(LocalDate exchangeSession, boolean sessionVerified,
                                String spotKey, String futuresKey, String provider,
                                Quote spot, Quote future,
                                boolean spotFinalVerified, boolean futureFinalVerified,
                                Instant spotFinalFirstKnownAt,
                                Instant futureFinalFirstKnownAt) {}

    /** Market-data research permission is not order, display or training rights. */
    public record SourceRights(boolean spotResearchVerified,
                               boolean futuresResearchVerified,
                               boolean contractDiscoveryVerified) {}

    /**
     * Annual signed rates in bps. Cost is an illustrative, independently
     * verified two-leg round trip; never a broker commitment or future P&L.
     */
    public record Assumptions(BigDecimal financingAnnualBps,
                              BigDecimal dividendAnnualBps,
                              BigDecimal allInRoundTripCostsBps,
                              int minConvergingSteps,
                              int minFirstToLastConvergenceBps,
                              int minRemainingAfterCostsBps,
                              int maxEachLegSpreadBps,
                              int maxSessionMidMoveBps,
                              boolean dividendEventsVerified,
                              boolean corporateActionsVerified,
                              boolean physicalDeliveryReviewed,
                              boolean costsReviewed) {}

    public record Decision(Status status, Pattern pattern, String reasonCode,
                           String strategyId, Instant evaluatedAt, String futuresKey,
                           String provider, LocalDate expiry, int completedSessions,
                           int narrowingSteps, BigDecimal initialResidualBps,
                           BigDecimal latestResidualBps, BigDecimal convergenceBps,
                           BigDecimal afterCostResidualBps) {
        public boolean executable() { return false; }
        public boolean orderPlaced() { return false; }
        public boolean liveTradeAuthorized() { return false; }
    }

    public static Decision evaluate(Contract contract, List<CompletedPair> pairs,
                                    SourceRights rights, Assumptions assumptions, Instant asOf) {
        Objects.requireNonNull(asOf, "asOf");
        if (rights == null || !rights.spotResearchVerified()
                || !rights.futuresResearchVerified() || !rights.contractDiscoveryVerified()) {
            return blocked(Status.BLOCKED_RIGHTS, "EXPIRY_BASIS_SOURCE_RIGHTS_MISSING", asOf);
        }
        if (!validContract(contract, asOf)) {
            return blocked(Status.BLOCKED_DATA, "EXPIRY_BASIS_CONTRACT_INVALID", asOf);
        }
        if (pairs == null || pairs.size() != SESSIONS || !validAssumptions(assumptions)) {
            return blocked(Status.BLOCKED_DATA, "EXPIRY_BASIS_HISTORY_OR_COSTS_INVALID", asOf);
        }

        LocalDate previousDate = null;
        String provider = null;
        BigDecimal initial = null;
        BigDecimal previous = null;
        BigDecimal previousSpotMid = null;
        BigDecimal previousFutureMid = null;
        int narrowing = 0;
        int finalConsecutiveNarrowing = 0;
        for (CompletedPair pair : pairs) {
            if (!validPair(pair, contract, asOf, assumptions.maxEachLegSpreadBps())) {
                return blocked(Status.BLOCKED_DATA, "EXPIRY_BASIS_FINAL_PAIR_INVALID", asOf);
            }
            if (provider != null && !provider.equals(pair.provider())) {
                return blocked(Status.BLOCKED_DATA, "EXPIRY_BASIS_PROVIDER_CHANGED", asOf);
            }
            if (previousDate != null) {
                long gap = ChronoUnit.DAYS.between(previousDate, pair.exchangeSession());
                if (gap < 1 || gap > 5) {
                    return blocked(Status.BLOCKED_DATA, "EXPIRY_BASIS_SESSION_SEQUENCE_INVALID",
                        asOf);
                }
            }

            BigDecimal spotMid = mid(pair.spot());
            BigDecimal futureMid = mid(pair.future());
            if (previousSpotMid != null) {
                BigDecimal spotMove = bps(
                    spotMid.subtract(previousSpotMid, MC).abs(), previousSpotMid);
                BigDecimal futureMove = bps(
                    futureMid.subtract(previousFutureMid, MC).abs(), previousFutureMid);
                if (spotMove.compareTo(
                        BigDecimal.valueOf(assumptions.maxSessionMidMoveBps())) > 0
                        || futureMove.compareTo(
                        BigDecimal.valueOf(assumptions.maxSessionMidMoveBps())) > 0) {
                    return blocked(Status.BLOCKED_DATA,
                        "EXPIRY_BASIS_PRICE_OUTLIER", asOf);
                }
            }

            long remainingDays = ChronoUnit.DAYS.between(
                pair.exchangeSession(), contract.expiry());
            BigDecimal expectedCarryBps = assumptions.financingAnnualBps()
                .subtract(assumptions.dividendAnnualBps(), MC)
                .multiply(BigDecimal.valueOf(remainingDays), MC)
                .divide(DAYS_IN_YEAR, MC);
            BigDecimal observedBasisBps = bps(
                futureMid.subtract(spotMid, MC), spotMid);
            BigDecimal residual = observedBasisBps.subtract(expectedCarryBps, MC);
            if (residual.signum() == 0 || initial != null
                    && residual.signum() != initial.signum()) {
                return measured(Status.NO_SIGNAL, Pattern.NONE,
                    "EXPIRY_BASIS_RESIDUAL_CROSSED_FAIR",
                    contract, provider == null ? pair.provider() : provider,
                    asOf, 0, initial, residual, null, null);
            }

            if (initial == null) initial = residual;
            if (previous != null) {
                if (residual.abs().compareTo(previous.abs()) < 0) {
                    narrowing++;
                    finalConsecutiveNarrowing++;
                } else {
                    finalConsecutiveNarrowing = 0;
                }
            }
            previous = residual;
            previousDate = pair.exchangeSession();
            previousSpotMid = spotMid;
            previousFutureMid = futureMid;
            provider = pair.provider();
        }

        long lastAgeDays = ChronoUnit.DAYS.between(
            previousDate, asOf.atZone(IST).toLocalDate());
        if (lastAgeDays < 0 || lastAgeDays > 5) {
            return blocked(Status.BLOCKED_DATA, "EXPIRY_BASIS_LAST_SESSION_STALE", asOf);
        }

        BigDecimal convergence = initial.abs().subtract(previous.abs(), MC);
        BigDecimal afterCosts = previous.abs()
            .subtract(assumptions.allInRoundTripCostsBps(), MC);
        boolean trendConfirmed = narrowing >= assumptions.minConvergingSteps()
            && finalConsecutiveNarrowing >= 2;
        boolean enoughCompression = convergence.compareTo(
            BigDecimal.valueOf(assumptions.minFirstToLastConvergenceBps())) >= 0;
        boolean enoughResidual = afterCosts.compareTo(
            BigDecimal.valueOf(assumptions.minRemainingAfterCostsBps())) > 0;
        boolean passes = trendConfirmed && enoughCompression && enoughResidual;
        Pattern pattern = !passes ? Pattern.NONE
            : initial.signum() > 0 ? Pattern.ABOVE_FAIR_CONVERGING
            : Pattern.BELOW_FAIR_CONVERGING;
        String reason = !trendConfirmed
            ? "EXPIRY_BASIS_NARROWING_NOT_CONFIRMED"
            : !enoughCompression ? "EXPIRY_BASIS_TOTAL_CONVERGENCE_TOO_SMALL"
            : !enoughResidual ? "EXPIRY_BASIS_COST_ADJUSTED_RESIDUAL_TOO_SMALL"
            : pattern == Pattern.ABOVE_FAIR_CONVERGING
                ? "EXPIRY_BASIS_ABOVE_FAIR_CONVERGENCE_OBSERVED"
                : "EXPIRY_BASIS_BELOW_FAIR_CONVERGENCE_OBSERVED";
        return measured(passes ? Status.RESEARCH_CANDIDATE : Status.NO_SIGNAL,
            pattern, reason, contract, provider, asOf, narrowing,
            initial, previous, convergence, afterCosts);
    }

    private static boolean validContract(Contract c, Instant asOf) {
        if (c == null || !c.contractVerified() || !c.deliverableSpotVerified()
                || c.spotKey() == null
                || !c.spotKey().matches("NSE_EQ\\|[A-Za-z0-9._-]{1,64}")
                || c.futuresKey() == null
                || !c.futuresKey().matches("NSE_FO\\|[A-Za-z0-9._-]{1,64}")
                || !c.spotKey().equals(c.futuresUnderlyingSpotKey())
                || c.listedFrom() == null || c.expiry() == null
                || c.listedFrom().isAfter(c.expiry())
                || c.lotSize() < 1 || c.lotSize() > 100000) return false;
        long days = ChronoUnit.DAYS.between(
            asOf.atZone(IST).toLocalDate(), c.expiry());
        // Expiry pressure is interesting, but a physical delivery cutoff may
        // already make the trade infeasible. Research only at DTE 3..14.
        return days >= 3 && days <= 14;
    }

    private static boolean validPair(CompletedPair p, Contract c, Instant asOf,
                                     int maxSpreadBps) {
        if (p == null || !p.sessionVerified() || !p.spotFinalVerified()
                || !p.futureFinalVerified() || p.exchangeSession() == null
                || p.exchangeSession().getDayOfWeek().getValue() > 5
                || p.exchangeSession().isBefore(c.listedFrom())
                || !p.exchangeSession().isBefore(c.expiry())
                || !c.spotKey().equals(p.spotKey())
                || !c.futuresKey().equals(p.futuresKey())
                || !("UPSTOX_ANALYTICS".equals(p.provider())
                    || "ANGEL_ONE_SMARTAPI".equals(p.provider()))
                || !validQuote(p.spot(), p.exchangeSession(), asOf, maxSpreadBps)
                || !validQuote(p.future(), p.exchangeSession(), asOf, maxSpreadBps)
                || p.spotFinalFirstKnownAt() == null
                || p.futureFinalFirstKnownAt() == null)
            return false;
        Instant spotClose = cashClose(p.exchangeSession());
        Instant derivativesClose = futuresClose(p.exchangeSession());
        return Duration.between(p.spot().exchangeAt(),
                p.future().exchangeAt()).abs().compareTo(MAX_PAIR_SKEW) <= 0
            && !p.spotFinalFirstKnownAt().isBefore(spotClose)
            && !p.futureFinalFirstKnownAt().isBefore(derivativesClose)
            && !p.spotFinalFirstKnownAt().isBefore(p.spot().firstObservedAt())
            && !p.futureFinalFirstKnownAt().isBefore(p.future().firstObservedAt())
            && !p.spotFinalFirstKnownAt().isAfter(asOf)
            && !p.futureFinalFirstKnownAt().isAfter(asOf)
            && !p.spotFinalFirstKnownAt().isAfter(spotClose.plus(MAX_PUBLICATION_LAG))
            && !p.futureFinalFirstKnownAt().isAfter(derivativesClose.plus(MAX_PUBLICATION_LAG));
    }

    private static boolean validQuote(Quote q, LocalDate day, Instant asOf,
                                      int maxSpreadBps) {
        if (q == null || !positive(q.bid()) || !positive(q.ask())
                || q.ask().compareTo(q.bid()) < 0
                || q.exchangeAt() == null || q.firstObservedAt() == null)
            return false;
        Instant close = cashClose(day);
        if (q.exchangeAt().isBefore(close.minus(MAX_CLOSE_WINDOW))
                || q.exchangeAt().isAfter(close)
                || q.exchangeAt().isAfter(q.firstObservedAt())
                || q.firstObservedAt().isAfter(asOf)
                || q.firstObservedAt().isAfter(close.plus(MAX_PUBLICATION_LAG))) return false;
        return bps(q.ask().subtract(q.bid(), MC), mid(q))
            .compareTo(BigDecimal.valueOf(maxSpreadBps)) <= 0;
    }

    static Instant cashClose(LocalDate day) {
        return day.atTime(CASH_CLOSE).atZone(IST).toInstant();
    }

    static Instant futuresClose(LocalDate day) {
        LocalTime close = day.isBefore(FUTURES_CLOSE_SHIFT_AUG) ? LocalTime.of(15, 30)
            : day.isBefore(FUTURES_CLOSE_SHIFT_DEC) ? LocalTime.of(15, 40)
            : LocalTime.of(15, 45);
        return day.atTime(close).atZone(IST).toInstant();
    }

    private static boolean validAssumptions(Assumptions a) {
        return a != null && bound(a.financingAnnualBps(), 0, 5000)
            && bound(a.dividendAnnualBps(), 0, 10000)
            && bound(a.allInRoundTripCostsBps(), 1, 2000)
            && between(a.minConvergingSteps(), 3, 4)
            && between(a.minFirstToLastConvergenceBps(), 1, 5000)
            && between(a.minRemainingAfterCostsBps(), 1, 3000)
            && between(a.maxEachLegSpreadBps(), 1, 500)
            && between(a.maxSessionMidMoveBps(), 100, 10000)
            && a.dividendEventsVerified() && a.corporateActionsVerified()
            && a.physicalDeliveryReviewed() && a.costsReviewed();
    }

    private static boolean positive(BigDecimal x) {
        return x != null && x.signum() > 0;
    }
    private static boolean between(int x, int low, int high) {
        return x >= low && x <= high;
    }
    private static boolean bound(BigDecimal x, int low, int high) {
        return x != null && x.compareTo(BigDecimal.valueOf(low)) >= 0
            && x.compareTo(BigDecimal.valueOf(high)) <= 0;
    }
    private static BigDecimal mid(Quote q) {
        return q.bid().add(q.ask(), MC).divide(BigDecimal.valueOf(2), MC);
    }
    private static BigDecimal bps(BigDecimal a, BigDecimal b) {
        return a.multiply(BPS, MC).divide(b, MC);
    }
    private static BigDecimal rounded(BigDecimal n) {
        return n == null ? null : n.setScale(4, RoundingMode.HALF_UP);
    }
    private static Decision measured(Status status, Pattern pattern, String reason,
                                     Contract c, String provider, Instant now,
                                     int narrowing, BigDecimal start, BigDecimal end,
                                     BigDecimal compression, BigDecimal afterCosts) {
        return new Decision(status, pattern, reason, STRATEGY_ID, now,
            c.futuresKey(), provider, c.expiry(), SESSIONS, narrowing,
            rounded(start), rounded(end), rounded(compression), rounded(afterCosts));
    }
    private static Decision blocked(Status status, String reason, Instant now) {
        return new Decision(status, Pattern.NONE, reason, STRATEGY_ID, now,
            null, null, null, 0, 0, null, null, null, null);
    }
}
