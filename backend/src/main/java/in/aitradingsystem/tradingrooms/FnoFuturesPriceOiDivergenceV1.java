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
 * STR-174 — deterministic research-only futures price / open-interest divergence.
 *
 * <p>Compares finalized exchange-session CLOSE and OI of the exact same NSE
 * futures contract across five completed sessions. A price/OI divergence is
 * a market-structure observation, not proof of short covering, a buy/sell
 * instruction, arbitrage or a predictable future return.
 *
 * <p>No broker connection, account rights mutation, persistence or execution.
 * An independently authorized source adapter must establish actual market
 * rights, verified closing prices, consistent OI units, and original publication
 * times. Historical rows downloaded later cannot be backdated to session close.
 */
public final class FnoFuturesPriceOiDivergenceV1 {
    public static final String STRATEGY_ID = "strategy.STR-174";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    // 2026 NSE derivatives normal closing times; this strategy excludes expiry day.
    private static final LocalDate AUGUST_CHANGE = LocalDate.of(2026, 8, 3);
    private static final LocalDate DECEMBER_CHANGE = LocalDate.of(2026, 12, 7);
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal BPS = new BigDecimal("10000");
    private static final Duration MAX_PUBLICATION_LAG = Duration.ofHours(36);
    private static final long MAX_OI_OR_VOLUME = 1_000_000_000_000L;
    private static final int REQUIRED_SESSIONS = 5;

    private FnoFuturesPriceOiDivergenceV1() {}

    public enum Status { BLOCKED_RIGHTS, BLOCKED_DATA, NO_SIGNAL, RESEARCH_CANDIDATE }
    public enum Divergence { NONE, PRICE_UP_OI_DOWN, PRICE_DOWN_OI_UP }

    public record Contract(String instrumentKey, String underlyingKey, LocalDate expiry,
                           int lotSize, boolean contractVerified) {}

    /**
     * Prices are verified final session CLOSE/settlement, never LTP snapshots.
     * finalPriceObservedAt and finalizedOiObservedAt are the independently
     * recorded first time each final value was available to this source.
     * Open interest and session volume are normalized to underlying units.
     */
    public record CompletedSession(LocalDate exchangeDate, boolean sessionVerified,
                                   String instrumentKey, String provider,
                                   BigDecimal finalClosePrice, boolean finalPriceVerified,
                                   Instant finalPriceObservedAt,
                                   long finalizedOpenInterestUnits, long tradedVolumeUnits,
                                   Instant finalizedOiObservedAt) {}

    public record SourceRights(boolean finalPriceResearchVerified,
                               boolean finalizedOiResearchVerified,
                               boolean contractDiscoveryVerified) {}

    /** Explicit research calibration, not trading recommendations. */
    public record Thresholds(int minCumulativePriceMoveBps, int minCumulativeOiChangeBps,
                             int minConfirmingDivergentSteps, int minEachSessionVolumeToOiBps,
                             int maxOneStepPriceChangeBps, int maxOneStepOiChangeBps) {}

    public record Decision(Status status, Divergence divergence, String reasonCode,
                           String strategyId, String instrumentKey, String source,
                           LocalDate expiry, Instant evaluatedAt, int completedSessions,
                           int upPriceDownOiSteps, int downPriceUpOiSteps,
                           BigDecimal cumulativePriceMoveBps,
                           BigDecimal cumulativeOiChangeBps,
                           BigDecimal minSessionVolumeToOiBps) {
        public boolean executable() { return false; }
        public boolean orderPlaced() { return false; }
        public boolean directionalBuyOrSell() { return false; }
    }

    public static Decision evaluate(Contract contract, List<CompletedSession> sessions,
                                    SourceRights rights, Thresholds thresholds, Instant asOf) {
        Objects.requireNonNull(asOf, "asOf");
        if (rights == null || !rights.finalPriceResearchVerified()
                || !rights.finalizedOiResearchVerified()
                || !rights.contractDiscoveryVerified()) {
            return blocked(Status.BLOCKED_RIGHTS, "PRICE_OI_SOURCE_RIGHTS_NOT_VERIFIED", asOf);
        }
        if (!validContract(contract, asOf)) {
            return blocked(Status.BLOCKED_DATA, "PRICE_OI_CONTRACT_INVALID", asOf);
        }
        if (sessions == null || sessions.size() != REQUIRED_SESSIONS
                || !validThresholds(thresholds)) {
            return blocked(Status.BLOCKED_DATA, "PRICE_OI_HISTORY_OR_THRESHOLDS_INVALID", asOf);
        }
        String provider = null;
        LocalDate previousDay = null;
        for (CompletedSession session : sessions) {
            if (!validSession(session, contract.instrumentKey(), asOf)) {
                return blocked(Status.BLOCKED_DATA, "PRICE_OI_FINAL_SESSION_INVALID", asOf);
            }
            if (provider != null && !provider.equals(session.provider())) {
                return blocked(Status.BLOCKED_DATA, "PRICE_OI_PROVIDER_CHANGED", asOf);
            }
            if (previousDay != null) {
                long gap = ChronoUnit.DAYS.between(previousDay, session.exchangeDate());
                // Calendar verification (including NSE holidays) belongs to
                // the upstream adapter, beyond this bounded date sanity check.
                if (gap < 1 || gap > 5) {
                    return blocked(Status.BLOCKED_DATA, "PRICE_OI_SESSION_SEQUENCE_INVALID", asOf);
                }
            }
            provider = session.provider();
            previousDay = session.exchangeDate();
        }
        long elapsed = ChronoUnit.DAYS.between(previousDay, asOf.atZone(IST).toLocalDate());
        if (elapsed < 0 || elapsed > 5) {
            return blocked(Status.BLOCKED_DATA, "PRICE_OI_LAST_SESSION_STALE", asOf);
        }

        int upPriceDownOiSteps = 0;
        int downPriceUpOiSteps = 0;
        boolean contrarySteps = false;
        BigDecimal minimumVolumeToOi = null;
        CompletedSession previous = null;
        for (CompletedSession current : sessions) {
            BigDecimal participation = ratioBps(
                BigDecimal.valueOf(current.tradedVolumeUnits()),
                BigDecimal.valueOf(current.finalizedOpenInterestUnits()));
            minimumVolumeToOi = minimumVolumeToOi == null ? participation
                : minimumVolumeToOi.min(participation);
            if (previous != null) {
                BigDecimal priceChange = ratioBps(
                    current.finalClosePrice().subtract(previous.finalClosePrice(), MC),
                    previous.finalClosePrice());
                BigDecimal oiChange = ratioBps(
                    BigDecimal.valueOf(current.finalizedOpenInterestUnits())
                        .subtract(BigDecimal.valueOf(previous.finalizedOpenInterestUnits())),
                    BigDecimal.valueOf(previous.finalizedOpenInterestUnits()));
                if (priceChange.abs().compareTo(
                        BigDecimal.valueOf(thresholds.maxOneStepPriceChangeBps())) > 0
                        || oiChange.abs().compareTo(
                        BigDecimal.valueOf(thresholds.maxOneStepOiChangeBps())) > 0) {
                    return blocked(Status.BLOCKED_DATA, "PRICE_OI_ONE_STEP_OUTLIER", asOf);
                }
                if (priceChange.signum() > 0 && oiChange.signum() < 0) upPriceDownOiSteps++;
                else if (priceChange.signum() < 0 && oiChange.signum() > 0) downPriceUpOiSteps++;
                else if (priceChange.signum() != 0 || oiChange.signum() != 0) contrarySteps = true;
            }
            previous = current;
        }

        BigDecimal totalPrice = ratioBps(
            sessions.getLast().finalClosePrice().subtract(
                sessions.getFirst().finalClosePrice(), MC),
            sessions.getFirst().finalClosePrice());
        BigDecimal totalOi = ratioBps(
            BigDecimal.valueOf(sessions.getLast().finalizedOpenInterestUnits())
                .subtract(BigDecimal.valueOf(sessions.getFirst().finalizedOpenInterestUnits())),
            BigDecimal.valueOf(sessions.getFirst().finalizedOpenInterestUnits()));
        boolean enoughVolume = minimumVolumeToOi.compareTo(
            BigDecimal.valueOf(thresholds.minEachSessionVolumeToOiBps())) >= 0;
        boolean upDown = !contrarySteps && downPriceUpOiSteps == 0
            && upPriceDownOiSteps >= thresholds.minConfirmingDivergentSteps()
            && totalPrice.compareTo(
                BigDecimal.valueOf(thresholds.minCumulativePriceMoveBps())) >= 0
            && totalOi.compareTo(
                BigDecimal.valueOf(-thresholds.minCumulativeOiChangeBps())) <= 0;
        boolean downUp = !contrarySteps && upPriceDownOiSteps == 0
            && downPriceUpOiSteps >= thresholds.minConfirmingDivergentSteps()
            && totalPrice.compareTo(
                BigDecimal.valueOf(-thresholds.minCumulativePriceMoveBps())) <= 0
            && totalOi.compareTo(
                BigDecimal.valueOf(thresholds.minCumulativeOiChangeBps())) >= 0;
        Divergence divergence = !enoughVolume ? Divergence.NONE
            : upDown ? Divergence.PRICE_UP_OI_DOWN
            : downUp ? Divergence.PRICE_DOWN_OI_UP : Divergence.NONE;
        Status status = divergence == Divergence.NONE
            ? Status.NO_SIGNAL : Status.RESEARCH_CANDIDATE;
        String reason = !enoughVolume ? "PRICE_OI_VOLUME_PARTICIPATION_INSUFFICIENT"
            : divergence == Divergence.PRICE_UP_OI_DOWN ? "PRICE_RISING_WITH_FALLING_OI"
            : divergence == Divergence.PRICE_DOWN_OI_UP ? "PRICE_FALLING_WITH_RISING_OI"
            : "PRICE_OI_DIVERGENCE_NOT_CONFIRMED";
        return new Decision(status, divergence, reason, STRATEGY_ID,
            contract.instrumentKey(), provider, contract.expiry(), asOf,
            REQUIRED_SESSIONS, upPriceDownOiSteps, downPriceUpOiSteps,
            rounded(totalPrice), rounded(totalOi), rounded(minimumVolumeToOi));
    }

    private static boolean validContract(Contract c, Instant asOf) {
        if (c == null || !c.contractVerified() || c.instrumentKey() == null
                || !c.instrumentKey().matches("NSE_FO\\|[A-Za-z0-9._-]{1,64}")
                || c.underlyingKey() == null
                || !c.underlyingKey().matches(
                    "(?:NSE_EQ\\|[A-Za-z0-9._-]{1,64}|NSE_INDEX\\|[A-Za-z0-9 ._-]{1,64})")
                || c.expiry() == null || c.lotSize() < 1 || c.lotSize() > 100000)
            return false;
        long days = ChronoUnit.DAYS.between(
            asOf.atZone(IST).toLocalDate(), c.expiry());
        return days >= 7 && days <= 120;
    }

    private static boolean validSession(CompletedSession s, String key, Instant asOf) {
        if (s == null || !s.sessionVerified() || !s.finalPriceVerified()
                || s.exchangeDate() == null
                || s.exchangeDate().getDayOfWeek().getValue() > 5
                || !key.equals(s.instrumentKey())
                || !("UPSTOX_ANALYTICS".equals(s.provider())
                    || "ANGEL_ONE_SMARTAPI".equals(s.provider()))
                || s.finalClosePrice() == null || s.finalClosePrice().signum() <= 0
                || s.finalClosePrice().compareTo(new BigDecimal("1000000000")) > 0
                || s.finalizedOpenInterestUnits() <= 0
                || s.finalizedOpenInterestUnits() > MAX_OI_OR_VOLUME
                || s.tradedVolumeUnits() < 0 || s.tradedVolumeUnits() > MAX_OI_OR_VOLUME
                || s.finalPriceObservedAt() == null || s.finalizedOiObservedAt() == null)
            return false;
        Instant close = s.exchangeDate().atTime(normalFuturesClose(s.exchangeDate())).atZone(IST).toInstant();
        return !close.isAfter(asOf)
            && !s.finalPriceObservedAt().isBefore(close)
            && !s.finalizedOiObservedAt().isBefore(close)
            && !s.finalPriceObservedAt().isAfter(asOf)
            && !s.finalizedOiObservedAt().isAfter(asOf)
            && !s.finalPriceObservedAt().isAfter(close.plus(MAX_PUBLICATION_LAG))
            && !s.finalizedOiObservedAt().isAfter(close.plus(MAX_PUBLICATION_LAG));
    }

    private static LocalTime normalFuturesClose(LocalDate date) {
        if (!date.isBefore(DECEMBER_CHANGE)) return LocalTime.of(15, 45);
        if (!date.isBefore(AUGUST_CHANGE)) return LocalTime.of(15, 40);
        return LocalTime.of(15, 30);
    }

    private static boolean validThresholds(Thresholds t) {
        return t != null
            && between(t.minCumulativePriceMoveBps(), 1, 10000)
            && between(t.minCumulativeOiChangeBps(), 1, 10000)
            && between(t.minConfirmingDivergentSteps(), 2, 4)
            && between(t.minEachSessionVolumeToOiBps(), 1, 50000)
            && between(t.maxOneStepPriceChangeBps(), 100, 100000)
            && between(t.maxOneStepOiChangeBps(), 1000, 100000);
    }

    private static boolean between(int x, int low, int high) {
        return x >= low && x <= high;
    }

    private static BigDecimal ratioBps(BigDecimal a, BigDecimal b) {
        return a.multiply(BPS, MC).divide(b, MC);
    }

    private static BigDecimal rounded(BigDecimal x) {
        return x.setScale(4, RoundingMode.HALF_UP);
    }

    private static Decision blocked(Status status, String reason, Instant asOf) {
        return new Decision(status, Divergence.NONE, reason, STRATEGY_ID,
            null, null, null, asOf, 0, 0, 0, null, null, null);
    }
}
