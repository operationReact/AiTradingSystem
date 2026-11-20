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
import in.aitradingsystem.marketdata.FnoMarketDataProvider;
import java.util.List;
import java.util.Objects;

/**
 * STR-172: strictly as-of, research-only futures open-interest rollover detection.
 *
 * <p>A move of OI from the expiring contract to the next-listed contract is
 * an observation about positioning/liquidity, not a directional buy/sell signal,
 * a broker instruction, or a guaranteed profitable calendar spread.
 *
 * <p>Each observation must represent one COMPLETED exchange session with
 * independently observed near/far OI, daily volume and bid/ask. No market
 * data is fetched, stored, displayed, or licensed by this pure evaluator.
 */
public final class FnoFuturesRolloverSignalV1 {
    public static final String STRATEGY_ID = "strategy.STR-172";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime SESSION_CLOSE = LocalTime.of(15, 30);
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal BPS = new BigDecimal("10000");
    private static final Duration CROSS_LEG_SKEW = Duration.ofMinutes(2);
    private static final Duration CLOSING_TICK_WINDOW = Duration.ofMinutes(10);
    private static final long MAX_UNITS = 1_000_000_000_000L;

    private FnoFuturesRolloverSignalV1() {}

    public enum Status { BLOCKED_RIGHTS, BLOCKED_DATA, NO_SIGNAL, RESEARCH_CANDIDATE }

    public record Contract(String instrumentKey, String underlyingKey,
                           LocalDate expiry, int lotSize, boolean providerVerified) {}

    /** Quote time and first observation time are distinct; OI/volume are underlying units. */
    public record LegSnapshot(String instrumentKey, String source,
                              Instant exchangeAt, Instant observedAt,
                              long openInterestUnits, long sessionVolumeUnits,
                              BigDecimal bid, BigDecimal ask) {}

    public record CompletedSession(LocalDate sessionDate, boolean closeVerified,
                                   LegSnapshot near, LegSnapshot far) {}

    public record SourceRights(boolean nearResearchVerified, boolean farResearchVerified,
                               boolean contractDiscoveryVerified) {}

    /**
     * Each threshold is in basis points. Fees/slippage are assumed execution
     * costs for BOTH legs; spread friction is estimated separately from quotes.
     * The cost gate is only a research quality gate, not evidence of executable fill.
     */
    public record Thresholds(int minNearOiDeclineBps, int minFarOiIncreaseBps,
                             int minFarOiShareShiftBps, int minFarVolumeShareBps,
                             int twoLegFeesSlippageBps, int maxEstimatedFrictionBps) {}

    public record Decision(Status status, String reasonCode, String strategyId,
                           Instant evaluatedAt, LocalDate nearExpiry, LocalDate farExpiry,
                           BigDecimal nearOiDeclineBps, BigDecimal farOiIncreaseBps,
                           BigDecimal farOiShareShiftBps, BigDecimal farVolumeShareBps,
                           BigDecimal estimatedFrictionBps, int completedSessions) {
        public boolean executable() { return false; }
        public boolean orderPlaced() { return false; }
        public boolean directionalBuyOrSell() { return false; }
    }

    /**
     * Exactly three consecutive supplied completed sessions (no cherry-picked
     * individual intermediate observations) are used for a conservative
     * two-step OI migration confirmation. The data adapter must also verify
     * the market-session calendar, provider rights, instrument master and
     * exchange publication timestamps.
     */
    public static Decision evaluate(Contract near, Contract far,
                                    List<CompletedSession> sessions,
                                    SourceRights rights, Thresholds thresholds, Instant asOf) {
        Objects.requireNonNull(asOf, "asOf");
        if (rights == null || !rights.nearResearchVerified() || !rights.farResearchVerified()
                || !rights.contractDiscoveryVerified()) {
            return blocked(Status.BLOCKED_RIGHTS, "ROLLOVER_SOURCE_RIGHTS_NOT_VERIFIED", asOf);
        }
        if (!validContractPair(near, far, asOf)) {
            return blocked(Status.BLOCKED_DATA, "ROLLOVER_CONTRACT_PAIR_INVALID", asOf);
        }
        if (sessions == null || sessions.size() != 3 || !validThresholds(thresholds)) {
            return blocked(Status.BLOCKED_DATA, "ROLLOVER_HISTORY_OR_THRESHOLDS_INVALID", asOf);
        }
        String provider = null;
        LocalDate previous = null;
        for (CompletedSession session : sessions) {
            if (!validSession(session, near, far, asOf)) {
                return blocked(Status.BLOCKED_DATA, "ROLLOVER_SESSION_PROVENANCE_INVALID", asOf);
            }
            if (previous != null) {
                long days = ChronoUnit.DAYS.between(previous, session.sessionDate());
                if (days < 1 || days > 5) {
                    return blocked(Status.BLOCKED_DATA, "ROLLOVER_NONCONSECUTIVE_SESSIONS", asOf);
                }
            }
            String current = session.near().source();
            if (provider != null && !provider.equals(current)) {
                return blocked(Status.BLOCKED_DATA, "ROLLOVER_SOURCE_CHANGED", asOf);
            }
            previous = session.sessionDate();
            provider = current;
        }
        long age = ChronoUnit.DAYS.between(previous, asOf.atZone(IST).toLocalDate());
        if (age < 0 || age > 5) {
            return blocked(Status.BLOCKED_DATA, "ROLLOVER_LAST_SESSION_STALE", asOf);
        }
        CompletedSession first = sessions.get(0);
        CompletedSession second = sessions.get(1);
        CompletedSession last = sessions.get(2);
        if (first.near().openInterestUnits() == 0 || first.far().openInterestUnits() == 0
                || (last.near().openInterestUnits() == 0 && last.far().openInterestUnits() == 0)) {
            return blocked(Status.BLOCKED_DATA, "ROLLOVER_BASELINE_OR_FINAL_OPEN_INTEREST_MISSING", asOf);
        }

        BigDecimal nearDecline = ratioBps(
            BigDecimal.valueOf(first.near().openInterestUnits() - last.near().openInterestUnits()),
            BigDecimal.valueOf(first.near().openInterestUnits()));
        BigDecimal farIncrease = ratioBps(
            BigDecimal.valueOf(last.far().openInterestUnits() - first.far().openInterestUnits()),
            BigDecimal.valueOf(first.far().openInterestUnits()));
        BigDecimal initialFarShare = ratioBps(
            BigDecimal.valueOf(first.far().openInterestUnits()),
            BigDecimal.valueOf(first.far().openInterestUnits())
                .add(BigDecimal.valueOf(first.near().openInterestUnits())));
        BigDecimal finalFarShare = ratioBps(
            BigDecimal.valueOf(last.far().openInterestUnits()),
            BigDecimal.valueOf(last.far().openInterestUnits())
                .add(BigDecimal.valueOf(last.near().openInterestUnits())));
        BigDecimal shift = finalFarShare.subtract(initialFarShare, MC);
        long totalVolume = last.near().sessionVolumeUnits() + last.far().sessionVolumeUnits();
        BigDecimal farVolumeShare = totalVolume == 0 ? BigDecimal.ZERO
            : ratioBps(BigDecimal.valueOf(last.far().sessionVolumeUnits()),
                BigDecimal.valueOf(totalVolume));

        // Quoted L1 widths (half-spread per leg) + declared two-leg estimated
        // fees/slippage. This is NOT the futures term-structure basis, a fill,
        // or a complete brokerage/statutory-cost certification.
        BigDecimal nearWidth = halfSpreadBps(last.near());
        BigDecimal farWidth = halfSpreadBps(last.far());
        BigDecimal friction = nearWidth.add(farWidth, MC)
            .add(BigDecimal.valueOf(thresholds.twoLegFeesSlippageBps()), MC);

        boolean nearFellEachStep = first.near().openInterestUnits() > second.near().openInterestUnits()
            && second.near().openInterestUnits() > last.near().openInterestUnits();
        boolean farRoseEachStep = first.far().openInterestUnits() < second.far().openInterestUnits()
            && second.far().openInterestUnits() < last.far().openInterestUnits();
        boolean migration = nearFellEachStep && farRoseEachStep
            && nearDecline.compareTo(BigDecimal.valueOf(thresholds.minNearOiDeclineBps())) >= 0
            && farIncrease.compareTo(BigDecimal.valueOf(thresholds.minFarOiIncreaseBps())) >= 0
            && shift.compareTo(BigDecimal.valueOf(thresholds.minFarOiShareShiftBps())) >= 0
            && farVolumeShare.compareTo(BigDecimal.valueOf(thresholds.minFarVolumeShareBps())) >= 0;
        Status status = migration && friction.compareTo(
                BigDecimal.valueOf(thresholds.maxEstimatedFrictionBps())) <= 0
            ? Status.RESEARCH_CANDIDATE : Status.NO_SIGNAL;
        String reason = !migration ? "TWO_STEP_OI_MIGRATION_NOT_CONFIRMED"
            : status == Status.NO_SIGNAL ? "ROLLOVER_ESTIMATED_FRICTION_TOO_HIGH"
            : "NEAR_TO_NEXT_EXPIRY_OI_MIGRATION_OBSERVED";
        return new Decision(status, reason, STRATEGY_ID, asOf, near.expiry(), far.expiry(),
            rounded(nearDecline), rounded(farIncrease), rounded(shift),
            rounded(farVolumeShare), rounded(friction), sessions.size());
    }

    private static boolean validContractPair(Contract near, Contract far, Instant asOf) {
        if (!validContract(near) || !validContract(far)
                || near.instrumentKey().equals(far.instrumentKey())
                || !near.underlyingKey().equals(far.underlyingKey())
                || near.lotSize() != far.lotSize()
                || !near.expiry().isBefore(far.expiry())) return false;
        LocalDate day = asOf.atZone(IST).toLocalDate();
        long nearDays = ChronoUnit.DAYS.between(day, near.expiry());
        long farDays = ChronoUnit.DAYS.between(day, far.expiry());
        return nearDays >= 3 && nearDays <= 15 && farDays <= 100
            && farDays > nearDays;
    }

    private static boolean validContract(Contract c) {
        return c != null && c.providerVerified() && c.instrumentKey() != null
            && c.instrumentKey().matches("NSE_FO\\|[A-Za-z0-9._-]{1,64}")
            && c.underlyingKey() != null
            && c.underlyingKey().matches(
                "(?:NSE_EQ\\|[A-Za-z0-9._-]{1,64}|NSE_INDEX\\|[A-Za-z0-9 ._-]{1,64})")
            && c.lotSize() > 0 && c.lotSize() <= 100000 && c.expiry() != null;
    }

    private static boolean validSession(CompletedSession s, Contract near, Contract far,
                                        Instant asOf) {
        if (s == null || !s.closeVerified() || s.sessionDate() == null
                || s.sessionDate().getDayOfWeek().getValue() > 5) return false;
        Instant close = s.sessionDate().atTime(SESSION_CLOSE).atZone(IST).toInstant();
        return !close.isAfter(asOf)
            && validLeg(s.near(), near.instrumentKey(), s.sessionDate(), close, asOf)
            && validLeg(s.far(), far.instrumentKey(), s.sessionDate(), close, asOf)
            && s.near().source().equals(s.far().source())
            && Duration.between(s.near().exchangeAt(), s.far().exchangeAt())
                .abs().compareTo(CROSS_LEG_SKEW) <= 0;
    }

    private static boolean validLeg(LegSnapshot leg, String key, LocalDate date,
                                    Instant close, Instant asOf) {
        if (leg == null || !key.equals(leg.instrumentKey())
                || !FnoMarketDataProvider.isSessionHistorySource(leg.source())
                || leg.exchangeAt() == null || leg.observedAt() == null
                || leg.exchangeAt().isAfter(close)
                || leg.exchangeAt().isBefore(close.minus(CLOSING_TICK_WINDOW))
                || !date.equals(leg.exchangeAt().atZone(IST).toLocalDate())
                || leg.observedAt().isBefore(close) || leg.observedAt().isAfter(asOf)
                || leg.observedAt().isAfter(close.plus(Duration.ofDays(1)))
                || leg.openInterestUnits() < 0 || leg.openInterestUnits() > MAX_UNITS
                || leg.sessionVolumeUnits() < 0 || leg.sessionVolumeUnits() > MAX_UNITS
                || leg.bid() == null || leg.ask() == null || leg.bid().signum() <= 0
                || leg.ask().compareTo(leg.bid()) < 0) return false;
        return true;
    }

    private static boolean validThresholds(Thresholds t) {
        return t != null && within(t.minNearOiDeclineBps(), 1, 10000)
            && within(t.minFarOiIncreaseBps(), 1, 10000)
            && within(t.minFarOiShareShiftBps(), 1, 10000)
            && within(t.minFarVolumeShareBps(), 1, 10000)
            && within(t.twoLegFeesSlippageBps(), 1, 2000)
            && within(t.maxEstimatedFrictionBps(), 1, 2000)
            && t.maxEstimatedFrictionBps() >= t.twoLegFeesSlippageBps();
    }

    private static boolean within(int v, int min, int max) {
        return v >= min && v <= max;
    }

    private static BigDecimal ratioBps(BigDecimal numerator, BigDecimal denominator) {
        return numerator.multiply(BPS, MC).divide(denominator, MC);
    }

    private static BigDecimal halfSpreadBps(LegSnapshot leg) {
        BigDecimal mid = leg.bid().add(leg.ask(), MC).divide(BigDecimal.valueOf(2), MC);
        return leg.ask().subtract(leg.bid(), MC)
            .multiply(BPS, MC).divide(mid, MC).divide(BigDecimal.valueOf(2), MC);
    }

    private static BigDecimal rounded(BigDecimal n) {
        return n.setScale(4, RoundingMode.HALF_UP);
    }

    private static Decision blocked(Status status, String reason, Instant asOf) {
        return new Decision(status, reason, STRATEGY_ID, asOf, null, null,
            null, null, null, null, null, 0);
    }
}
