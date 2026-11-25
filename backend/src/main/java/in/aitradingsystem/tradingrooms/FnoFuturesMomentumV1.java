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
 * STR-176 — source-proven, seven-completed-session NSE futures momentum research.
 *
 * <p>Classifies close breakouts beyond the previous six session highs/lows,
 * corroborated by recent close direction and exchange-reported volume/OI.
 * It never produces an executable trade, a profit forecast, or an order.
 * Missing/late bars, absent market-use rights, or an uncertain contract
 * identity fail closed. An authorized backend adapter, not this evaluator,
 * must verify the actual NSE session calendar and original publication times.
 */
public final class FnoFuturesMomentumV1 {
    public static final String STRATEGY_ID = "strategy.STR-176";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    // NSE F&O circular 07/2026 (NSE/FAOP/72352): Sunday Budget
    // session, 2026-02-01, 09:15–15:30 IST. Still requires sessionVerified
    // and genuine OHLCV/OI availability; never allow arbitrary weekends.
    private static final LocalDate VERIFIED_BUDGET_SESSION = LocalDate.of(2026, 2, 1);
    private static final LocalDate AUGUST_CLOSE_CHANGE = LocalDate.of(2026, 8, 3);
    private static final LocalDate DECEMBER_CLOSE_CHANGE = LocalDate.of(2026, 12, 7);
    private static final Duration MAX_FIRST_SEEN_LAG = Duration.ofHours(36);
    private static final BigDecimal BPS = new BigDecimal("10000");
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final long MAX_UNITS = 1_000_000_000_000L;
    private static final int SESSION_COUNT = 7;

    private FnoFuturesMomentumV1() {}

    public enum Status { BLOCKED_RIGHTS, BLOCKED_DATA, NO_SIGNAL, RESEARCH_CANDIDATE }
    public enum Momentum { NONE, UPWARD_BREAKOUT, DOWNWARD_BREAKOUT }

    /** Genuine single futures instrument; the seven bars must predate expiry. */
    public record Contract(String instrumentKey, String underlyingKey,
                           LocalDate listedFrom, LocalDate expiry,
                           int lotSize, boolean providerVerified) {}

    /**
     * Bar is one completed NSE futures exchange session. OHLC and volume must
     * be independently verified finalized observations; finalized OI has its
     * own original source observed/published timestamp.
     */
    public record CompletedSession(LocalDate exchangeDate, boolean sessionVerified,
                                   String instrumentKey, String provider,
                                   BigDecimal open, BigDecimal high, BigDecimal low,
                                   BigDecimal close, boolean ohlcvVerified,
                                   Instant ohlcvFirstObservedAt,
                                   long tradedVolumeUnits, long openInterestUnits,
                                   Instant oiFirstObservedAt) {}

    /** No implication of broker trading-segment access, LIVE rights, or display rights. */
    public record SourceRights(boolean ohlcvResearchVerified,
                               boolean oiResearchVerified,
                               boolean contractDiscoveryVerified) {}

    /** All bps values are explicit research assumptions, not calibrated signals. */
    public record Thresholds(int minBreakoutBps, int minConfirmingCloseSteps,
                             int minRecentVolumeVsBaselineBps,
                             int minLatestVolumeToOiBps,
                             int maxSingleSessionCloseMoveBps,
                             int maxDailyRangeBps,
                             int estimatedRoundTripCostsBps,
                             int minCostAdjustedBreakoutBps) {}

    public record Decision(Status status, Momentum momentum, String reasonCode,
                           String strategyId, String instrumentKey, String provider,
                           LocalDate expiry, Instant evaluatedAt,
                           int completedSessions, int risingCloseSteps,
                           int fallingCloseSteps, BigDecimal upwardBreakoutBps,
                           BigDecimal downwardBreakoutBps,
                           BigDecimal recentVolumeVsBaselineBps,
                           BigDecimal latestVolumeToOiBps,
                           BigDecimal costAdjustedBreakoutBps) {
        public boolean executable() { return false; }
        public boolean orderPlaced() { return false; }
        public boolean liveTradeAuthorized() { return false; }
    }

    public static Decision evaluate(Contract contract, List<CompletedSession> bars,
                                    SourceRights rights, Thresholds thresholds, Instant asOf) {
        Objects.requireNonNull(asOf, "asOf");
        if (rights == null || !rights.ohlcvResearchVerified() || !rights.oiResearchVerified()
                || !rights.contractDiscoveryVerified()) {
            return blocked(Status.BLOCKED_RIGHTS, "FUT_MOM_SOURCE_RIGHTS_NOT_VERIFIED", asOf);
        }
        if (!validContract(contract, asOf)) {
            return blocked(Status.BLOCKED_DATA, "FUT_MOM_CONTRACT_INVALID", asOf);
        }
        if (bars == null || bars.size() != SESSION_COUNT || !validThresholds(thresholds)) {
            return blocked(Status.BLOCKED_DATA, "FUT_MOM_HISTORY_OR_THRESHOLDS_INVALID", asOf);
        }
        LocalDate previousDay = null;
        String provider = null;
        for (CompletedSession bar : bars) {
            if (!validBar(bar, contract, asOf)) {
                return blocked(Status.BLOCKED_DATA, "FUT_MOM_COMPLETED_BAR_INVALID", asOf);
            }
            if (previousDay != null) {
                long days = ChronoUnit.DAYS.between(previousDay, bar.exchangeDate());
                if (days < 1 || days > 5) {
                    return blocked(Status.BLOCKED_DATA, "FUT_MOM_SESSION_SEQUENCE_INVALID", asOf);
                }
            }
            if (provider != null && !provider.equals(bar.provider())) {
                return blocked(Status.BLOCKED_DATA, "FUT_MOM_PROVIDER_CHANGED", asOf);
            }
            provider = bar.provider();
            previousDay = bar.exchangeDate();
        }
        long age = ChronoUnit.DAYS.between(previousDay, asOf.atZone(IST).toLocalDate());
        if (age < 0 || age > 5) {
            return blocked(Status.BLOCKED_DATA, "FUT_MOM_RECENT_BAR_STALE", asOf);
        }

        BigDecimal priorHigh = bars.getFirst().high();
        BigDecimal priorLow = bars.getFirst().low();
        BigDecimal baselineVolume = BigDecimal.ZERO;
        BigDecimal recentVolume = BigDecimal.ZERO;
        for (int i = 0; i < SESSION_COUNT; i++) {
            CompletedSession bar = bars.get(i);
            if (i < SESSION_COUNT - 1) {
                priorHigh = priorHigh.max(bar.high());
                priorLow = priorLow.min(bar.low());
            }
            if (i < 3) baselineVolume = baselineVolume.add(
                BigDecimal.valueOf(bar.tradedVolumeUnits()), MC);
            if (i >= 4) recentVolume = recentVolume.add(
                BigDecimal.valueOf(bar.tradedVolumeUnits()), MC);
            BigDecimal rangeBps = ratioBps(bar.high().subtract(bar.low(), MC), bar.close());
            if (rangeBps.compareTo(BigDecimal.valueOf(thresholds.maxDailyRangeBps())) > 0) {
                return blocked(Status.BLOCKED_DATA, "FUT_MOM_INTRADAY_RANGE_OUTLIER", asOf);
            }
            if (i > 0) {
                BigDecimal step = ratioBps(bar.close().subtract(bars.get(i - 1).close(), MC),
                                           bars.get(i - 1).close());
                if (step.abs().compareTo(
                        BigDecimal.valueOf(thresholds.maxSingleSessionCloseMoveBps())) > 0) {
                    return blocked(Status.BLOCKED_DATA, "FUT_MOM_PRICE_STEP_OUTLIER", asOf);
                }
            }
        }
        if (baselineVolume.signum() == 0) {
            return blocked(Status.BLOCKED_DATA, "FUT_MOM_BASELINE_VOLUME_MISSING", asOf);
        }
        BigDecimal latestClose = bars.getLast().close();
        BigDecimal up = ratioBps(latestClose.subtract(priorHigh, MC), priorHigh);
        BigDecimal down = ratioBps(priorLow.subtract(latestClose, MC), priorLow);
        BigDecimal volumeLift = ratioBps(recentVolume, baselineVolume);
        BigDecimal latestParticipation = ratioBps(
            BigDecimal.valueOf(bars.getLast().tradedVolumeUnits()),
            BigDecimal.valueOf(bars.getLast().openInterestUnits()));

        int rises = 0, falls = 0;
        // Confirm breakout momentum using only the last three close-to-close
        // steps, never using the same-day price bar before its first-observed time.
        for (int i = SESSION_COUNT - 3; i < SESSION_COUNT; i++) {
            int delta = bars.get(i).close().compareTo(bars.get(i - 1).close());
            if (delta > 0) rises++;
            if (delta < 0) falls++;
        }

        boolean volumeOk = volumeLift.compareTo(
            BigDecimal.valueOf(thresholds.minRecentVolumeVsBaselineBps())) >= 0
            && latestParticipation.compareTo(
                BigDecimal.valueOf(thresholds.minLatestVolumeToOiBps())) >= 0;
        boolean upCandidate = up.compareTo(BigDecimal.valueOf(thresholds.minBreakoutBps())) >= 0
            && rises >= thresholds.minConfirmingCloseSteps() && falls == 0;
        boolean downCandidate = down.compareTo(BigDecimal.valueOf(thresholds.minBreakoutBps())) >= 0
            && falls >= thresholds.minConfirmingCloseSteps() && rises == 0;
        BigDecimal rawEdge = upCandidate ? up : downCandidate ? down : BigDecimal.ZERO;
        BigDecimal afterCosts = rawEdge.subtract(
            BigDecimal.valueOf(thresholds.estimatedRoundTripCostsBps()), MC);
        boolean costsOk = afterCosts.compareTo(
            BigDecimal.valueOf(thresholds.minCostAdjustedBreakoutBps())) > 0;
        Momentum momentum = !volumeOk || !costsOk ? Momentum.NONE
            : upCandidate ? Momentum.UPWARD_BREAKOUT
            : downCandidate ? Momentum.DOWNWARD_BREAKOUT : Momentum.NONE;
        Status status = momentum == Momentum.NONE ? Status.NO_SIGNAL : Status.RESEARCH_CANDIDATE;
        String reason = !volumeOk ? "FUT_MOM_VOLUME_OR_OI_PARTICIPATION_LOW"
            : !(upCandidate || downCandidate) ? "FUT_MOM_BREAKOUT_NOT_CONFIRMED"
            : !costsOk ? "FUT_MOM_COST_ADJUSTED_BREAKOUT_TOO_SMALL"
            : momentum == Momentum.UPWARD_BREAKOUT ? "FUT_MOM_UPWARD_BREAKOUT_OBSERVED"
            : "FUT_MOM_DOWNWARD_BREAKOUT_OBSERVED";
        return new Decision(status, momentum, reason, STRATEGY_ID,
            contract.instrumentKey(), provider, contract.expiry(), asOf, SESSION_COUNT,
            rises, falls, round(up), round(down), round(volumeLift),
            round(latestParticipation), round(afterCosts));
    }

    private static boolean validContract(Contract c, Instant asOf) {
        if (c == null || !c.providerVerified() || c.instrumentKey() == null
                || !c.instrumentKey().matches("NSE_FO\\|[A-Za-z0-9._-]{1,64}")
                || c.underlyingKey() == null || !c.underlyingKey().matches(
                    "(?:NSE_EQ\\|[A-Za-z0-9._-]{1,64}|NSE_INDEX\\|[A-Za-z0-9 ._-]{1,64})")
                || c.listedFrom() == null || c.expiry() == null
                || c.listedFrom().isAfter(c.expiry())
                || c.lotSize() < 1 || c.lotSize() > 100000) return false;
        long untilExpiry = ChronoUnit.DAYS.between(
            asOf.atZone(IST).toLocalDate(), c.expiry());
        return untilExpiry >= 7 && untilExpiry <= 120;
    }

    private static boolean validBar(CompletedSession bar, Contract contract, Instant asOf) {
        if (bar == null || !bar.sessionVerified() || !bar.ohlcvVerified()
                || bar.exchangeDate() == null
                || (bar.exchangeDate().getDayOfWeek().getValue() > 5
                    && !VERIFIED_BUDGET_SESSION.equals(bar.exchangeDate()))
                || bar.exchangeDate().isBefore(contract.listedFrom())
                || bar.exchangeDate().isAfter(contract.expiry())
                || !contract.instrumentKey().equals(bar.instrumentKey())
                || !("UPSTOX_ANALYTICS".equals(bar.provider())
                    || "ANGEL_ONE_SMARTAPI".equals(bar.provider()))
                || !positive(bar.open()) || !positive(bar.high())
                || !positive(bar.low()) || !positive(bar.close())
                || bar.high().compareTo(bar.low()) < 0
                || bar.high().compareTo(bar.open()) < 0
                || bar.high().compareTo(bar.close()) < 0
                || bar.low().compareTo(bar.open()) > 0
                || bar.low().compareTo(bar.close()) > 0
                || bar.tradedVolumeUnits() < 0 || bar.tradedVolumeUnits() > MAX_UNITS
                || bar.openInterestUnits() <= 0 || bar.openInterestUnits() > MAX_UNITS
                || bar.ohlcvFirstObservedAt() == null || bar.oiFirstObservedAt() == null)
            return false;
        Instant close = bar.exchangeDate().atTime(normalNonExpiringClose(bar.exchangeDate())).atZone(IST).toInstant();
        return !close.isAfter(asOf)
            && !bar.ohlcvFirstObservedAt().isBefore(close)
            && !bar.oiFirstObservedAt().isBefore(close)
            && !bar.ohlcvFirstObservedAt().isAfter(asOf)
            && !bar.oiFirstObservedAt().isAfter(asOf)
            && !bar.ohlcvFirstObservedAt().isAfter(close.plus(MAX_FIRST_SEEN_LAG))
            && !bar.oiFirstObservedAt().isAfter(close.plus(MAX_FIRST_SEEN_LAG));
    }

    private static LocalTime normalNonExpiringClose(LocalDate date) {
        if (!date.isBefore(DECEMBER_CLOSE_CHANGE)) return LocalTime.of(15, 45);
        if (!date.isBefore(AUGUST_CLOSE_CHANGE)) return LocalTime.of(15, 40);
        return LocalTime.of(15, 30);
    }

    private static boolean positive(BigDecimal x) { return x != null && x.signum() > 0; }
    private static boolean within(int x, int low, int high) { return x >= low && x <= high; }
    private static boolean validThresholds(Thresholds t) {
        return t != null && within(t.minBreakoutBps(), 1, 3000)
            && within(t.minConfirmingCloseSteps(), 2, 3)
            && within(t.minRecentVolumeVsBaselineBps(), 10000, 50000)
            && within(t.minLatestVolumeToOiBps(), 1, 50000)
            && within(t.maxSingleSessionCloseMoveBps(), 100, 10000)
            && within(t.maxDailyRangeBps(), 100, 20000)
            && within(t.estimatedRoundTripCostsBps(), 1, 2000)
            && within(t.minCostAdjustedBreakoutBps(), 1, 2000);
    }
    private static BigDecimal ratioBps(BigDecimal numerator, BigDecimal denominator) {
        return numerator.multiply(BPS, MC).divide(denominator, MC);
    }
    private static BigDecimal round(BigDecimal value) {
        return value.setScale(4, RoundingMode.HALF_UP);
    }
    private static Decision blocked(Status status, String reason, Instant now) {
        return new Decision(status, Momentum.NONE, reason, STRATEGY_ID,
            null, null, null, now, 0, 0, 0, null, null, null, null, null);
    }
}
