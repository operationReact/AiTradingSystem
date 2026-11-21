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
 * STR-173 — deterministic, research-only, same-contract futures open-interest trend.
 *
 * <p>Measures a sustained change in open interest in ONE source-listed NSE
 * futures contract across five completed exchange sessions. Rising/falling OI
 * is a positioning observation, NOT bullish/bearish price action, an order, or
 * proof of predictive profitability. Price/OI divergence is a separate STR-174.
 *
 * <p>Source rights, genuine contract identity, normalized OI/volume units,
 * completed-session provenance and publication times must be verified by an
 * upstream authorized adapter; this pure evaluator connects to no broker.
 */
public final class FnoFuturesOpenInterestTrendV1 {
    public static final String STRATEGY_ID = "strategy.STR-173";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    // NSE equity derivatives normal close changed on 2026-08-03 and 2026-12-07.
    // The >=7-day DTE contract rule avoids December expiry-day special hours.
    private static final LocalDate AUGUST_2026_CLOSE_CHANGE = LocalDate.of(2026, 8, 3);
    private static final LocalDate DECEMBER_2026_CLOSE_CHANGE = LocalDate.of(2026, 12, 7);
    private static final LocalTime PRE_AUGUST_CLOSE = LocalTime.of(15, 30);
    private static final LocalTime AUGUST_CLOSE = LocalTime.of(15, 40);
    private static final LocalTime DECEMBER_CLOSE = LocalTime.of(15, 45);
    private static final Duration CLOSING_WINDOW = Duration.ofMinutes(2);
    private static final Duration MAX_PUBLICATION_LAG = Duration.ofHours(36);
    private static final BigDecimal BPS = new BigDecimal("10000");
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final long MAX_UNITS = 1_000_000_000_000L;
    private static final int REQUIRED_SESSIONS = 5;

    private FnoFuturesOpenInterestTrendV1() {}

    public enum Status { BLOCKED_RIGHTS, BLOCKED_DATA, NO_SIGNAL, RESEARCH_CANDIDATE }
    public enum Trend { NONE, RISING_OPEN_INTEREST, FALLING_OPEN_INTEREST }

    public record Contract(String instrumentKey, String underlyingKey, LocalDate expiry,
                           int lotSize, boolean providerVerified) {}

    /**
     * Normalized underlying units, not a mixture of lots and shares. observedAt
     * is the actual timestamp when finalized OI was first available, not when a
     * historical row was subsequently imported.
     */
    public record CompletedSession(LocalDate exchangeDate, boolean sessionVerified,
                                   String instrumentKey, String source,
                                   Instant exchangeAt, Instant observedAt,
                                   long openInterestUnits, long tradedVolumeUnits) {}

    /** Research data-use rights are independent of account trading permissions. */
    public record SourceRights(boolean researchUseVerified,
                               boolean contractDiscoveryVerified) {}

    /** All cutoffs are explicit bps except minConfirmingSteps (out of four). */
    public record Thresholds(int minCumulativeOiChangeBps, int minConfirmingSteps,
                             int minEachSessionVolumeToOiBps, int maxOneStepOiChangeBps) {}

    public record Decision(Status status, Trend trend, String reasonCode,
                           String strategyId, Instant evaluatedAt, String instrumentKey,
                           LocalDate expiry, String source, int completedSessions,
                           int increasingSteps, int decreasingSteps,
                           BigDecimal cumulativeOiChangeBps,
                           BigDecimal minimumVolumeToOiBps) {
        public boolean executable() { return false; }
        public boolean orderPlaced() { return false; }
        public boolean directionalBuyOrSell() { return false; }
    }

    /**
     * Only five ordered, session-complete observations on the identical contract
     * may be compared. An apparent rollover to a different expiry MUST NOT be
     * folded into this trend; STR-172 covers expiry migration separately.
     */
    public static Decision evaluate(Contract contract, List<CompletedSession> sessions,
                                    SourceRights rights, Thresholds thresholds, Instant asOf) {
        Objects.requireNonNull(asOf, "asOf");
        if (rights == null || !rights.researchUseVerified()
                || !rights.contractDiscoveryVerified()) {
            return blocked(Status.BLOCKED_RIGHTS, "OI_SOURCE_RIGHTS_NOT_VERIFIED", asOf);
        }
        if (!validContract(contract, asOf)) {
            return blocked(Status.BLOCKED_DATA, "OI_CONTRACT_IDENTITY_OR_EXPIRY_INVALID", asOf);
        }
        if (sessions == null || sessions.size() != REQUIRED_SESSIONS
                || !validThresholds(thresholds)) {
            return blocked(Status.BLOCKED_DATA, "OI_HISTORY_OR_THRESHOLDS_INVALID", asOf);
        }
        LocalDate previousDate = null;
        String provider = null;
        for (CompletedSession session : sessions) {
            if (!validSession(session, contract.instrumentKey(), asOf)) {
                return blocked(Status.BLOCKED_DATA, "OI_COMPLETED_SESSION_INVALID", asOf);
            }
            if (previousDate != null) {
                long gap = ChronoUnit.DAYS.between(previousDate, session.exchangeDate());
                // A verified exchange-session calendar remains an adapter
                // obligation; no holiday or consecutive-session assumption.
                if (gap < 1 || gap > 5) {
                    return blocked(Status.BLOCKED_DATA, "OI_SESSION_SEQUENCE_INVALID", asOf);
                }
            }
            if (provider != null && !provider.equals(session.source())) {
                return blocked(Status.BLOCKED_DATA, "OI_SOURCE_CHANGED", asOf);
            }
            previousDate = session.exchangeDate();
            provider = session.source();
        }
        long lastAge = ChronoUnit.DAYS.between(previousDate, asOf.atZone(IST).toLocalDate());
        if (lastAge < 0 || lastAge > 5) {
            return blocked(Status.BLOCKED_DATA, "OI_LAST_SESSION_STALE", asOf);
        }

        int increases = 0;
        int decreases = 0;
        BigDecimal minParticipation = null;
        CompletedSession previous = null;
        for (CompletedSession current : sessions) {
            BigDecimal participation = bps(
                BigDecimal.valueOf(current.tradedVolumeUnits()),
                BigDecimal.valueOf(current.openInterestUnits()));
            minParticipation = minParticipation == null
                ? participation : minParticipation.min(participation);
            if (previous != null) {
                long oldOi = previous.openInterestUnits();
                long newOi = current.openInterestUnits();
                if (newOi > oldOi) increases++;
                if (newOi < oldOi) decreases++;
                BigDecimal stepChange = bps(
                    BigDecimal.valueOf(newOi).subtract(BigDecimal.valueOf(oldOi)).abs(),
                    BigDecimal.valueOf(oldOi));
                if (stepChange.compareTo(
                        BigDecimal.valueOf(thresholds.maxOneStepOiChangeBps())) > 0) {
                    return blocked(Status.BLOCKED_DATA, "OI_STEP_CHANGE_OUTLIER", asOf);
                }
            }
            previous = current;
        }
        BigDecimal cumulative = bps(
            BigDecimal.valueOf(sessions.getLast().openInterestUnits())
                .subtract(BigDecimal.valueOf(sessions.getFirst().openInterestUnits())),
            BigDecimal.valueOf(sessions.getFirst().openInterestUnits()));
        BigDecimal floor = BigDecimal.valueOf(thresholds.minCumulativeOiChangeBps());
        boolean increasing = increases >= thresholds.minConfirmingSteps()
            && decreases == 0 && cumulative.compareTo(floor) >= 0;
        boolean decreasing = decreases >= thresholds.minConfirmingSteps()
            && increases == 0 && cumulative.negate().compareTo(floor) >= 0;
        boolean volumeConfirmed = minParticipation.compareTo(
            BigDecimal.valueOf(thresholds.minEachSessionVolumeToOiBps())) >= 0;
        Trend trend = volumeConfirmed && increasing ? Trend.RISING_OPEN_INTEREST
            : volumeConfirmed && decreasing ? Trend.FALLING_OPEN_INTEREST : Trend.NONE;
        Status status = trend == Trend.NONE ? Status.NO_SIGNAL : Status.RESEARCH_CANDIDATE;
        String reason = !volumeConfirmed ? "OI_SESSION_VOLUME_INSUFFICIENT"
            : trend == Trend.RISING_OPEN_INTEREST ? "SUSTAINED_OPEN_INTEREST_RISE"
            : trend == Trend.FALLING_OPEN_INTEREST ? "SUSTAINED_OPEN_INTEREST_DECLINE"
            : "OPEN_INTEREST_TREND_NOT_CONFIRMED";
        return new Decision(status, trend, reason, STRATEGY_ID, asOf,
            contract.instrumentKey(), contract.expiry(), provider, sessions.size(),
            increases, decreases, rounded(cumulative), rounded(minParticipation));
    }

    private static boolean validContract(Contract c, Instant asOf) {
        if (c == null || !c.providerVerified() || c.instrumentKey() == null
                || !c.instrumentKey().matches("NSE_FO\\|[A-Za-z0-9._-]{1,64}")
                || c.underlyingKey() == null
                || !c.underlyingKey().matches(
                    "(?:NSE_EQ\\|[A-Za-z0-9._-]{1,64}|NSE_INDEX\\|[A-Za-z0-9 ._-]{1,64})")
                || c.lotSize() < 1 || c.lotSize() > 100000 || c.expiry() == null) {
            return false;
        }
        long days = ChronoUnit.DAYS.between(
            asOf.atZone(IST).toLocalDate(), c.expiry());
        // Avoid conflating the immediate pre-expiry rollover with same-contract OI.
        return days >= 7 && days <= 120;
    }

    private static boolean validSession(CompletedSession s, String instrumentKey, Instant asOf) {
        if (s == null || !s.sessionVerified() || s.exchangeDate() == null
                || s.exchangeDate().getDayOfWeek().getValue() > 5
                || !instrumentKey.equals(s.instrumentKey())
                || !("UPSTOX_ANALYTICS".equals(s.source())
                    || "ANGEL_ONE_SMARTAPI".equals(s.source()))
                || s.exchangeAt() == null || s.observedAt() == null
                || s.openInterestUnits() <= 0 || s.openInterestUnits() > MAX_UNITS
                || s.tradedVolumeUnits() < 0 || s.tradedVolumeUnits() > MAX_UNITS) {
            return false;
        }
        Instant close = s.exchangeDate().atTime(officialFuturesClose(s.exchangeDate())).atZone(IST).toInstant();
        return !close.isAfter(asOf)
            && !s.exchangeAt().isAfter(close)
            && !s.exchangeAt().isBefore(close.minus(CLOSING_WINDOW))
            && s.exchangeDate().equals(s.exchangeAt().atZone(IST).toLocalDate())
            && !s.observedAt().isBefore(close)
            && !s.observedAt().isAfter(asOf)
            && !s.observedAt().isAfter(close.plus(MAX_PUBLICATION_LAG));
    }

    private static LocalTime officialFuturesClose(LocalDate date) {
        if (!date.isBefore(DECEMBER_2026_CLOSE_CHANGE)) return DECEMBER_CLOSE;
        if (!date.isBefore(AUGUST_2026_CLOSE_CHANGE)) return AUGUST_CLOSE;
        return PRE_AUGUST_CLOSE;
    }

    private static boolean validThresholds(Thresholds t) {
        return t != null && within(t.minCumulativeOiChangeBps(), 1, 10000)
            && within(t.minConfirmingSteps(), 2, 4)
            && within(t.minEachSessionVolumeToOiBps(), 1, 50000)
            && within(t.maxOneStepOiChangeBps(), 1000, 100000);
    }

    private static boolean within(int n, int min, int max) {
        return n >= min && n <= max;
    }

    private static BigDecimal bps(BigDecimal numerator, BigDecimal denominator) {
        return numerator.multiply(BPS, MC).divide(denominator, MC);
    }

    private static BigDecimal rounded(BigDecimal v) {
        return v.setScale(4, RoundingMode.HALF_UP);
    }

    private static Decision blocked(Status status, String reasonCode, Instant asOf) {
        return new Decision(status, Trend.NONE, reasonCode, STRATEGY_ID, asOf,
            null, null, null, 0, 0, 0, null, null);
    }
}
