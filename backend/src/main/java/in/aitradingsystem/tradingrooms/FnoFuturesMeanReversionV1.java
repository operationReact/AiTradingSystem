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
 * STR-177 — deterministic, research-only futures mean-reversion observation.
 *
 * <p>Uses eight source-verified baseline closes, a ninth-session price
 * excursion, and a TENTH completed-session move back toward the baseline
 * mean. The last bar must still be on the excursion side of the baseline mean.
 * This avoids mistaking an unconfirmed extreme for a reversion and avoids
 * labelling an already completed bounce as an untapped opportunity.
 *
 * <p>Standard deviation is calculated only from the first eight sessions.
 * Nothing is estimated from future observations. The calculation is not a
 * profit forecast, order request, or evidence of market-data rights.
 *
 * <p><b>Open-interest change integrity rule.</b> A large single-session change
 * in open interest is normally a sign of corrupt or misattributed data (for
 * example a stale row, a decimal error, or a value read from the wrong
 * contract), so the {@code maxOiChangePerStepBps} guard rejects it. But a real
 * NIFTY future legitimately sees very large OI changes around rollover and in
 * the expiry week. The exchange publishes, independently of the day-end OI
 * level, a per-session change field (NSE UDiFF {@code ChngInOpnIntrst}). When a
 * session carries that exchange-reported change and it reconciles <em>exactly</em>
 * with the day-over-day delta of the retained OI series, the move is confirmed
 * to be authentic exchange data: the guard is satisfied and the original
 * exchange value is preserved and used unchanged. When the exchange-reported
 * change is present but contradicts the computed delta, the OI series is
 * internally inconsistent and the row is rejected as corrupt. When no exchange
 * change field is available (for example the broker-analytics source), the
 * magnitude guard applies as before. Thresholds are never loosened to admit a
 * change; only an independently exchange-reconciled change is admitted.
 */
public final class FnoFuturesMeanReversionV1 {
    public static final String STRATEGY_ID = "strategy.STR-177";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate AUGUST_CLOSE_CHANGE = LocalDate.of(2026, 8, 3);
    private static final LocalDate DECEMBER_CLOSE_CHANGE = LocalDate.of(2026, 12, 7);
    private static final Duration MAX_FIRST_OBSERVED_LAG = Duration.ofHours(36);
    private static final BigDecimal BPS = new BigDecimal("10000");
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final int BASELINE_COUNT = 8;
    private static final int TOTAL_SESSIONS = 10;
    private static final long MAX_UNITS = 1_000_000_000_000L;

    private FnoFuturesMeanReversionV1() {}

    public enum Status { BLOCKED_RIGHTS, BLOCKED_DATA, NO_SIGNAL, RESEARCH_CANDIDATE }
    public enum Pattern { NONE, LOW_EXTREME_REBOUND, HIGH_EXTREME_PULLBACK }

    /** All ten rows must have this identical source-listed NSE future key. */
    public record Contract(String instrumentKey, String underlyingKey, LocalDate listedFrom,
                           LocalDate expiry, int lotSize, boolean providerVerified) {}

    /**
     * Both finalized close/volume and OI have original as-of publication timestamps.
     *
     * <p>{@code exchangeReportedOiChangeUnits} is the signed per-session change in
     * open interest as published independently by the exchange (NSE UDiFF
     * {@code ChngInOpnIntrst}), in the same units as {@code openInterestUnits}, or
     * {@code null} when the source does not publish an independent change field.
     * It is retained exactly as received to cross-validate the OI series; it is
     * never used as a price, a forecast, or an order input.
     */
    public record CompletedSession(LocalDate sessionDate, boolean sessionVerified,
                                   String instrumentKey, String provider,
                                   BigDecimal open, BigDecimal high, BigDecimal low,
                                   BigDecimal close, boolean ohlcvVerified,
                                   Instant ohlcvFirstObservedAt, long volumeUnits,
                                   long openInterestUnits, Instant oiFirstObservedAt,
                                   Long exchangeReportedOiChangeUnits) {
        /**
         * Backward-compatible session without an independent exchange-reported OI
         * change (for example the broker-analytics source, or callers that predate
         * the reconciliation rule). Behaves exactly as before: the OI magnitude
         * guard applies with no exchange reconciliation available.
         */
        public CompletedSession(LocalDate sessionDate, boolean sessionVerified,
                                String instrumentKey, String provider,
                                BigDecimal open, BigDecimal high, BigDecimal low,
                                BigDecimal close, boolean ohlcvVerified,
                                Instant ohlcvFirstObservedAt, long volumeUnits,
                                long openInterestUnits, Instant oiFirstObservedAt) {
            this(sessionDate, sessionVerified, instrumentKey, provider, open, high, low,
                close, ohlcvVerified, ohlcvFirstObservedAt, volumeUnits,
                openInterestUnits, oiFirstObservedAt, null);
        }
    }

    /** Internal research rights are independent of display and F&O order permissions. */
    public record SourceRights(boolean ohlcvResearchVerified, boolean oiResearchVerified,
                               boolean contractDiscoveryVerified) {}

    /**
     * Integer basis-point thresholds and z-score in hundredths (200 = 2.00σ).
     * All assumptions are research fixtures until calibrated on real as-of data.
     */
    public record Thresholds(int minBaselineStdBps, int minExtremeZHundredths,
                             int minReversalFromExtremeBps, int minRemainingAfterCostsBps,
                             int estimatedRoundTripCostsBps,
                             int minLatestVolumeVsBaselineBps, int minLatestVolumeToOiBps,
                             int maxSingleSessionCloseMoveBps, int maxDailyRangeBps,
                             int maxOiChangePerStepBps) {}

    public record Decision(Status status, Pattern pattern, String reasonCode,
                           String strategyId, Instant evaluatedAt, String instrumentKey,
                           String provider, LocalDate expiry, int completedSessions,
                           BigDecimal baselineMean, BigDecimal baselineStdBps,
                           BigDecimal extremeZScore, BigDecimal reversalFromExtremeBps,
                           BigDecimal remainingToMeanBps,
                           BigDecimal costAdjustedRemainingBps,
                           BigDecimal latestVolumeVsBaselineBps) {
        public boolean executable() { return false; }
        public boolean orderPlaced() { return false; }
        public boolean liveTradeAuthorized() { return false; }
    }

    public static Decision evaluate(Contract contract, List<CompletedSession> bars,
                                    SourceRights rights, Thresholds thresholds, Instant asOf) {
        Objects.requireNonNull(asOf, "asOf");
        if (rights == null || !rights.ohlcvResearchVerified()
                || !rights.oiResearchVerified() || !rights.contractDiscoveryVerified()) {
            return blocked(Status.BLOCKED_RIGHTS, "FUT_MR_SOURCE_RIGHTS_NOT_VERIFIED", asOf);
        }
        return evaluateSessions(contract, bars, thresholds, asOf, false);
    }

    /** Evaluates a forward research window from privately retained official NSE files. */
    public static Decision evaluateRetainedNseResearch(Contract contract, List<CompletedSession> bars,
                                                      Thresholds thresholds, Instant asOf) {
        return evaluateSessions(contract, bars, thresholds, Objects.requireNonNull(asOf, "asOf"), true);
    }

    private static Decision evaluateSessions(Contract contract, List<CompletedSession> bars,
                                             Thresholds thresholds, Instant asOf, boolean archiveResearch) {
        if (!validContract(contract, asOf)) {
            return blocked(Status.BLOCKED_DATA, "FUT_MR_CONTRACT_INVALID", asOf);
        }
        if (bars == null || bars.size() != TOTAL_SESSIONS || !validThresholds(thresholds)) {
            return blocked(Status.BLOCKED_DATA, "FUT_MR_HISTORY_OR_THRESHOLDS_INVALID", asOf);
        }
        String provider = null;
        LocalDate prevDay = null;
        CompletedSession previous = null;
        BigDecimal baselineVolume = BigDecimal.ZERO;
        BigDecimal baselineSum = BigDecimal.ZERO;
        for (int i = 0; i < bars.size(); i++) {
            CompletedSession b = bars.get(i);
            if (!validSession(b, contract, asOf, archiveResearch)) {
                return blocked(Status.BLOCKED_DATA, "FUT_MR_SESSION_INVALID", asOf);
            }
            if (provider != null && !provider.equals(b.provider())) {
                return blocked(Status.BLOCKED_DATA, "FUT_MR_PROVIDER_CHANGED", asOf);
            }
            if (prevDay != null) {
                long gap = ChronoUnit.DAYS.between(prevDay, b.sessionDate());
                if (gap < 1 || gap > 5) {
                    return blocked(Status.BLOCKED_DATA, "FUT_MR_SESSION_SEQUENCE_INVALID", asOf);
                }
            }
            // The verified NSE holiday/session calendar is an upstream adapter
            // obligation. The local checks reject obvious gaps and weekends.
            BigDecimal range = bps(b.high().subtract(b.low(), MC), b.close());
            if (range.compareTo(BigDecimal.valueOf(thresholds.maxDailyRangeBps())) > 0) {
                return blocked(Status.BLOCKED_DATA, "FUT_MR_INTRADAY_RANGE_OUTLIER", asOf);
            }
            if (previous != null) {
                BigDecimal closeChange = bps(
                    b.close().subtract(previous.close(), MC), previous.close()).abs();
                if (closeChange.compareTo(
                        BigDecimal.valueOf(thresholds.maxSingleSessionCloseMoveBps())) > 0) {
                    return blocked(Status.BLOCKED_DATA, "FUT_MR_DAILY_CHANGE_OUTLIER", asOf);
                }
                // Both OI levels are validated to lie in [1, MAX_UNITS], so the
                // signed day-over-day delta always fits in a long without overflow.
                long computedOiDelta = b.openInterestUnits() - previous.openInterestUnits();
                Long reportedOiChange = b.exchangeReportedOiChangeUnits();
                boolean oiReconciledWithExchange =
                    reportedOiChange != null && reportedOiChange.longValue() == computedOiDelta;
                if (reportedOiChange != null && !oiReconciledWithExchange) {
                    // The exchange independently published a per-session OI change
                    // (NSE ChngInOpnIntrst) that disagrees with the computed delta of
                    // the retained OI series: the two fields are internally
                    // inconsistent, so the row is rejected rather than silently used.
                    return blocked(Status.BLOCKED_DATA,
                        "FUT_MR_OI_CHANGE_CONTRADICTS_EXCHANGE", asOf);
                }
                BigDecimal oiChange = bps(
                    BigDecimal.valueOf(Math.abs(computedOiDelta)),
                    BigDecimal.valueOf(previous.openInterestUnits()));
                if (oiChange.compareTo(
                        BigDecimal.valueOf(thresholds.maxOiChangePerStepBps())) > 0
                        && !oiReconciledWithExchange) {
                    // Large OI move with no reconciling exchange-reported change:
                    // treat as a data-integrity outlier. A change that the exchange
                    // itself reported (rollover / expiry-week build) is authentic and
                    // is preserved and used unchanged.
                    return blocked(Status.BLOCKED_DATA, "FUT_MR_DAILY_CHANGE_OUTLIER", asOf);
                }
            }
            if (i < BASELINE_COUNT) {
                baselineSum = baselineSum.add(b.close(), MC);
                baselineVolume = baselineVolume.add(BigDecimal.valueOf(b.volumeUnits()), MC);
            }
            prevDay = b.sessionDate();
            provider = b.provider();
            previous = b;
        }
        if (ChronoUnit.DAYS.between(prevDay, asOf.atZone(IST).toLocalDate()) > 5) {
            return blocked(Status.BLOCKED_DATA, "FUT_MR_LAST_SESSION_STALE", asOf);
        }
        if (baselineVolume.signum() == 0) {
            return blocked(Status.BLOCKED_DATA, "FUT_MR_BASELINE_VOLUME_MISSING", asOf);
        }

        BigDecimal mean = baselineSum.divide(BigDecimal.valueOf(BASELINE_COUNT), MC);
        BigDecimal varianceSum = BigDecimal.ZERO;
        for (int i = 0; i < BASELINE_COUNT; i++) {
            BigDecimal delta = bars.get(i).close().subtract(mean, MC);
            varianceSum = varianceSum.add(delta.multiply(delta, MC), MC);
        }
        BigDecimal std = varianceSum.divide(BigDecimal.valueOf(BASELINE_COUNT), MC).sqrt(MC);
        BigDecimal stdBps = bps(std, mean);
        CompletedSession extreme = bars.get(BASELINE_COUNT);
        CompletedSession latest = bars.get(BASELINE_COUNT + 1);
        BigDecimal latestVolumeRatio = bps(
            BigDecimal.valueOf(latest.volumeUnits()).multiply(
                BigDecimal.valueOf(BASELINE_COUNT), MC), baselineVolume);
        BigDecimal latestOiParticipation = bps(
            BigDecimal.valueOf(latest.volumeUnits()),
            BigDecimal.valueOf(latest.openInterestUnits()));

        if (stdBps.compareTo(BigDecimal.valueOf(thresholds.minBaselineStdBps())) < 0) {
            return measured(Status.NO_SIGNAL, Pattern.NONE, "FUT_MR_BASELINE_VARIANCE_TOO_LOW",
                contract, provider, asOf, mean, stdBps, null, null, null, null, latestVolumeRatio);
        }
        if (latestVolumeRatio.compareTo(
                BigDecimal.valueOf(thresholds.minLatestVolumeVsBaselineBps())) < 0
                || latestOiParticipation.compareTo(
                    BigDecimal.valueOf(thresholds.minLatestVolumeToOiBps())) < 0) {
            return measured(Status.NO_SIGNAL, Pattern.NONE, "FUT_MR_VOLUME_OR_OI_LOW",
                contract, provider, asOf, mean, stdBps, null, null, null, null, latestVolumeRatio);
        }

        BigDecimal extremeZ = extreme.close().subtract(mean, MC).divide(std, MC);
        BigDecimal requiredZ = BigDecimal.valueOf(
            thresholds.minExtremeZHundredths()).divide(HUNDRED, MC);
        boolean lowExtreme = extremeZ.compareTo(requiredZ.negate()) <= 0;
        boolean highExtreme = extremeZ.compareTo(requiredZ) >= 0;
        if (!lowExtreme && !highExtreme) {
            return measured(Status.NO_SIGNAL, Pattern.NONE, "FUT_MR_EXTREME_NOT_CONFIRMED",
                contract, provider, asOf, mean, stdBps, extremeZ, null, null, null,
                latestVolumeRatio);
        }

        boolean recovering = lowExtreme
            ? latest.close().compareTo(extreme.close()) > 0
                && latest.close().compareTo(mean) < 0
            : latest.close().compareTo(extreme.close()) < 0
                && latest.close().compareTo(mean) > 0;
        if (!recovering) {
            return measured(Status.NO_SIGNAL, Pattern.NONE, "FUT_MR_RECOVERY_NOT_CONFIRMED",
                contract, provider, asOf, mean, stdBps, extremeZ, null, null, null,
                latestVolumeRatio);
        }

        BigDecimal rebound = lowExtreme
            ? bps(latest.close().subtract(extreme.close(), MC), extreme.close())
            : bps(extreme.close().subtract(latest.close(), MC), extreme.close());
        BigDecimal remaining = bps(mean.subtract(latest.close(), MC).abs(), latest.close());
        BigDecimal afterCosts = remaining.subtract(
            BigDecimal.valueOf(thresholds.estimatedRoundTripCostsBps()), MC);
        boolean qualifies = rebound.compareTo(
                BigDecimal.valueOf(thresholds.minReversalFromExtremeBps())) >= 0
            && afterCosts.compareTo(
                BigDecimal.valueOf(thresholds.minRemainingAfterCostsBps())) > 0;
        Pattern pattern = !qualifies ? Pattern.NONE
            : lowExtreme ? Pattern.LOW_EXTREME_REBOUND : Pattern.HIGH_EXTREME_PULLBACK;
        return measured(qualifies ? Status.RESEARCH_CANDIDATE : Status.NO_SIGNAL,
            pattern, !qualifies ? "FUT_MR_REVERSAL_OR_COST_HURDLE_NOT_MET"
                : lowExtreme ? "FUT_MR_LOW_EXTREME_REBOUND_OBSERVED"
                : "FUT_MR_HIGH_EXTREME_PULLBACK_OBSERVED",
            contract, provider, asOf, mean, stdBps, extremeZ, rebound, remaining,
            afterCosts, latestVolumeRatio);
    }

    private static boolean validContract(Contract c, Instant asOf) {
        if (c == null || !c.providerVerified() || c.instrumentKey() == null
                || !c.instrumentKey().matches("NSE_FO\\|[A-Za-z0-9._-]{1,64}")
                || c.underlyingKey() == null || !c.underlyingKey().matches(
                    "(?:NSE_EQ\\|[A-Za-z0-9._-]{1,64}|NSE_INDEX\\|[A-Za-z0-9 ._-]{1,64})")
                || c.listedFrom() == null || c.expiry() == null
                || c.listedFrom().isAfter(c.expiry())
                || c.lotSize() < 1 || c.lotSize() > 100000) return false;
        long remainingDays = ChronoUnit.DAYS.between(
            asOf.atZone(IST).toLocalDate(), c.expiry());
        return remainingDays >= 7 && remainingDays <= 120;
    }

    private static boolean validSession(CompletedSession b, Contract c, Instant asOf, boolean archiveResearch) {
        if (b == null || !b.sessionVerified() || !b.ohlcvVerified()
                || b.sessionDate() == null || b.sessionDate().getDayOfWeek().getValue() > 5
                || b.sessionDate().isBefore(c.listedFrom()) || b.sessionDate().isAfter(c.expiry())
                || !c.instrumentKey().equals(b.instrumentKey())
                || !validProvider(b.provider(), archiveResearch)
                || !positive(b.open()) || !positive(b.high())
                || !positive(b.low()) || !positive(b.close())
                || b.high().compareTo(b.low()) < 0
                || b.high().compareTo(b.open()) < 0
                || b.high().compareTo(b.close()) < 0
                || b.low().compareTo(b.open()) > 0
                || b.low().compareTo(b.close()) > 0
                || b.volumeUnits() < 0 || b.volumeUnits() > MAX_UNITS
                || b.openInterestUnits() <= 0 || b.openInterestUnits() > MAX_UNITS
                || b.ohlcvFirstObservedAt() == null || b.oiFirstObservedAt() == null)
            return false;
        Instant close = b.sessionDate().atTime(normalNonExpiringClose(b.sessionDate())).atZone(IST).toInstant();
        return !close.isAfter(asOf)
            && !b.ohlcvFirstObservedAt().isBefore(close)
            && !b.oiFirstObservedAt().isBefore(close)
            && !b.ohlcvFirstObservedAt().isAfter(asOf)
            && !b.oiFirstObservedAt().isAfter(asOf)
            && (archiveResearch || (!b.ohlcvFirstObservedAt().isAfter(close.plus(MAX_FIRST_OBSERVED_LAG))
                && !b.oiFirstObservedAt().isAfter(close.plus(MAX_FIRST_OBSERVED_LAG))));
    }

    private static boolean validProvider(String provider, boolean archiveResearch) {
        return archiveResearch ? "NSE_RETAINED_EOD".equals(provider)
            : "UPSTOX_ANALYTICS".equals(provider) || "ANGEL_ONE_SMARTAPI".equals(provider);
    }

    private static LocalTime normalNonExpiringClose(LocalDate date) {
        if (!date.isBefore(DECEMBER_CLOSE_CHANGE)) return LocalTime.of(15, 45);
        if (!date.isBefore(AUGUST_CLOSE_CHANGE)) return LocalTime.of(15, 40);
        return LocalTime.of(15, 30);
    }

    private static boolean positive(BigDecimal x) { return x != null && x.signum() > 0; }

    private static boolean validThresholds(Thresholds t) {
        return t != null && within(t.minBaselineStdBps(), 1, 5000)
            && within(t.minExtremeZHundredths(), 100, 1000)
            && within(t.minReversalFromExtremeBps(), 1, 5000)
            && within(t.minRemainingAfterCostsBps(), 1, 5000)
            && within(t.estimatedRoundTripCostsBps(), 1, 2000)
            && within(t.minLatestVolumeVsBaselineBps(), 1000, 50000)
            && within(t.minLatestVolumeToOiBps(), 1, 50000)
            && within(t.maxSingleSessionCloseMoveBps(), 100, 10000)
            && within(t.maxDailyRangeBps(), 100, 20000)
            && within(t.maxOiChangePerStepBps(), 100, 10000);
    }

    private static boolean within(int value, int min, int max) {
        return value >= min && value <= max;
    }

    private static BigDecimal bps(BigDecimal numerator, BigDecimal denominator) {
        return numerator.multiply(BPS, MC).divide(denominator, MC);
    }

    private static BigDecimal round(BigDecimal x) {
        return x == null ? null : x.setScale(4, RoundingMode.HALF_UP);
    }

    private static Decision measured(Status status, Pattern pattern, String reason,
                                     Contract c, String provider, Instant asOf,
                                     BigDecimal mean, BigDecimal stdBps, BigDecimal extremeZ,
                                     BigDecimal rebound, BigDecimal remaining,
                                     BigDecimal afterCosts, BigDecimal volumeRatio) {
        return new Decision(status, pattern, reason, STRATEGY_ID, asOf,
            c.instrumentKey(), provider, c.expiry(), TOTAL_SESSIONS,
            round(mean), round(stdBps), round(extremeZ), round(rebound),
            round(remaining), round(afterCosts), round(volumeRatio));
    }

    private static Decision blocked(Status status, String reason, Instant asOf) {
        return new Decision(status, Pattern.NONE, reason, STRATEGY_ID, asOf,
            null, null, null, 0, null, null, null, null, null, null, null);
    }
}
