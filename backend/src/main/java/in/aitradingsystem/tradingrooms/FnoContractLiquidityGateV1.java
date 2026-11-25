package in.aitradingsystem.tradingrooms;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

/**
 * STR-179 — research-only F&O contract liquidity screening, not a trade signal.
 *
 * <p>Uses three chronological source-observed Level-1 quotes on the same
 * provider-verified NSE_FO contract. Checks both sides of the order book,
 * open-interest and cumulative session-volume units, spread, recent quote
 * stability, provenance, and original source timestamps. This conservative
 * screen cannot establish executable depth, a fill, profitability, or
 * authorization to trade. Provider permissions are external preconditions.
 */
public final class FnoContractLiquidityGateV1 {
    public static final String STRATEGY_ID = "strategy.STR-179";
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal BPS = new BigDecimal("10000");
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final long MAX_UNITS = 1_000_000_000_000L;
    private static final int REQUIRED_SNAPSHOTS = 3;
    private static final Duration MAX_OI_AGE = Duration.ofHours(36);
    private static final Duration MAX_VOLUME_AGE = Duration.ofMinutes(2);
    private static final Duration MAX_WINDOW_AGE = Duration.ofSeconds(90);

    private FnoContractLiquidityGateV1() {}

    public enum Status { BLOCKED_RIGHTS, BLOCKED_DATA, ILLIQUID, RESEARCH_PASS }
    public enum DerivativeKind { FUTURE, CALL, PUT }

    /**
     * A verified NSE_FO instrument and its actual lot-size contract metadata.
     * Options require strike; futures forbid it. Exchange contract identifiers
     * and unit normalization must be verified upstream by the source adapter.
     */
    public record Contract(String instrumentKey, String underlyingKey,
                           LocalDate expiry, int lotSize, DerivativeKind kind,
                           BigDecimal strikePrice, boolean providerVerified) {}

    /** All depth and OI/volume counts are in underlying units, not lots. */
    public record MarketSnapshot(
        String instrumentKey, String provider,
        Instant exchangeAt, Instant observedAt,
        BigDecimal bid, BigDecimal ask,
        long bidDepthUnits, long askDepthUnits,
        long openInterestUnits, long cumulativeVolumeUnits,
        Instant oiFirstObservedAt, Instant volumeFirstObservedAt,
        boolean exchangeSessionVerified, boolean quoteVerified,
        boolean oiVerified, boolean volumeVerified) {}

    /**
     * Research-use rights do not grant client redistribution, storage/ML rights,
     * broker F&O trading segment activation or any order permission.
     */
    public record SourceRights(boolean quoteResearchVerified,
                               boolean openInterestResearchVerified,
                               boolean volumeResearchVerified,
                               boolean contractDiscoveryVerified) {}

    /** Explicit research thresholds, not exchange/broker limits or defaults. */
    public record Thresholds(int minOpenInterestLots, int minSessionVolumeLots,
                             int minDepthLotsEachSide, int maxSpreadBps,
                             int maxEffectiveFrictionBps, int assumedPerLegImpactBps,
                             int maxQuoteAgeSeconds, int maxSnapshotGapSeconds,
                             int maxMidMoveBetweenSnapshotsBps) {}

    public record Decision(
        Status status, String reasonCode, String strategyId,
        String instrumentKey, String provider, Instant evaluatedAt,
        int inspectedSnapshots, BigDecimal maxObservedSpreadBps,
        BigDecimal estimatedRoundTripFrictionBps,
        BigDecimal latestOiLots, BigDecimal latestVolumeLots,
        long minimumObservedBidDepthUnits, long minimumObservedAskDepthUnits) {
        public boolean executable() { return false; }
        public boolean orderPlaced() { return false; }
        public boolean liveTradeAuthorized() { return false; }
    }

    /**
     * Evaluation is strictly as-of. The snapshots alone never make a contract
     * tradable; an authorized upstream market-data adapter must attest rights,
     * actual exchange session/calendar, original quote/OI publication times,
     * and unit normalization before passing input here.
     */
    public static Decision evaluate(Contract contract, List<MarketSnapshot> snapshots,
                                    SourceRights rights, Thresholds thresholds, Instant asOf) {
        Objects.requireNonNull(asOf, "asOf");
        if (rights == null || !rights.quoteResearchVerified()
                || !rights.openInterestResearchVerified()
                || !rights.volumeResearchVerified()
                || !rights.contractDiscoveryVerified()) {
            return blocked(Status.BLOCKED_RIGHTS,
                "LIQUIDITY_SOURCE_RIGHTS_NOT_VERIFIED", asOf);
        }
        if (!validContract(contract, asOf)) {
            return blocked(Status.BLOCKED_DATA, "LIQUIDITY_CONTRACT_INVALID", asOf);
        }
        if (snapshots == null || snapshots.size() != REQUIRED_SNAPSHOTS
                || !validThresholds(thresholds)) {
            return blocked(Status.BLOCKED_DATA,
                "LIQUIDITY_HISTORY_OR_THRESHOLDS_INVALID", asOf);
        }

        MarketSnapshot previous = null;
        String provider = null;
        long minimumBidDepth = Long.MAX_VALUE;
        long minimumAskDepth = Long.MAX_VALUE;
        BigDecimal maxSpread = BigDecimal.ZERO;
        for (MarketSnapshot row : snapshots) {
            if (!validSnapshot(row, contract, asOf)) {
                return blocked(Status.BLOCKED_DATA,
                    "LIQUIDITY_SNAPSHOT_PROVENANCE_INVALID", asOf);
            }
            if (provider != null && !provider.equals(row.provider())) {
                return blocked(Status.BLOCKED_DATA,
                    "LIQUIDITY_PROVIDER_CHANGED", asOf);
            }
            if (previous != null) {
                long gap = Duration.between(previous.exchangeAt(), row.exchangeAt())
                    .getSeconds();
                if (gap < 1 || gap > thresholds.maxSnapshotGapSeconds()
                        || row.cumulativeVolumeUnits() < previous.cumulativeVolumeUnits()
                        || row.oiFirstObservedAt().isBefore(previous.oiFirstObservedAt())
                        || row.volumeFirstObservedAt().isBefore(
                            previous.volumeFirstObservedAt())) {
                    return blocked(Status.BLOCKED_DATA,
                        "LIQUIDITY_SEQUENCE_OR_COUNTER_INVALID", asOf);
                }
                BigDecimal prevMid = mid(previous);
                BigDecimal midMove = bps(mid(row).subtract(prevMid, MC).abs(), prevMid);
                if (midMove.compareTo(BigDecimal.valueOf(
                        thresholds.maxMidMoveBetweenSnapshotsBps())) > 0) {
                    return blocked(Status.BLOCKED_DATA,
                        "LIQUIDITY_QUOTE_JUMP_UNVERIFIED", asOf);
                }
            }
            minimumBidDepth = Math.min(minimumBidDepth, row.bidDepthUnits());
            minimumAskDepth = Math.min(minimumAskDepth, row.askDepthUnits());
            BigDecimal spread = bps(row.ask().subtract(row.bid(), MC), mid(row));
            maxSpread = maxSpread.max(spread);
            provider = row.provider();
            previous = row;
        }

        MarketSnapshot latest = snapshots.getLast();
        long lastQuoteAge = Duration.between(latest.exchangeAt(), asOf).getSeconds();
        long firstQuoteAge = Duration.between(snapshots.getFirst().exchangeAt(), asOf)
            .getSeconds();
        if (lastQuoteAge > thresholds.maxQuoteAgeSeconds()
                || firstQuoteAge > MAX_WINDOW_AGE.getSeconds()) {
            return blocked(Status.BLOCKED_DATA,
                "LIQUIDITY_QUOTE_STALE", asOf);
        }

        BigDecimal lot = BigDecimal.valueOf(contract.lotSize());
        BigDecimal minOi = BigDecimal.valueOf(thresholds.minOpenInterestLots())
            .multiply(lot, MC);
        BigDecimal minVolume = BigDecimal.valueOf(thresholds.minSessionVolumeLots())
            .multiply(lot, MC);
        BigDecimal minDepth = BigDecimal.valueOf(thresholds.minDepthLotsEachSide())
            .multiply(lot, MC);
        BigDecimal oiLots = BigDecimal.valueOf(latest.openInterestUnits()).divide(lot, MC);
        BigDecimal volumeLots = BigDecimal.valueOf(
            latest.cumulativeVolumeUnits()).divide(lot, MC);
        BigDecimal friction = maxSpread.add(
            BigDecimal.valueOf(2L * thresholds.assumedPerLegImpactBps()), MC);

        String reason = BigDecimal.valueOf(latest.openInterestUnits()).compareTo(minOi) < 0
            ? "LIQUIDITY_OPEN_INTEREST_TOO_LOW"
            : BigDecimal.valueOf(latest.cumulativeVolumeUnits()).compareTo(minVolume) < 0
                ? "LIQUIDITY_DAY_VOLUME_TOO_LOW"
            : BigDecimal.valueOf(minimumBidDepth).compareTo(minDepth) < 0
                    || BigDecimal.valueOf(minimumAskDepth).compareTo(minDepth) < 0
                ? "LIQUIDITY_TWO_SIDED_DEPTH_TOO_LOW"
            : maxSpread.compareTo(BigDecimal.valueOf(thresholds.maxSpreadBps())) > 0
                ? "LIQUIDITY_SPREAD_TOO_WIDE"
            : friction.compareTo(
                BigDecimal.valueOf(thresholds.maxEffectiveFrictionBps())) > 0
                ? "LIQUIDITY_ESTIMATED_FRICTION_TOO_HIGH"
            : "LIQUIDITY_RESEARCH_SCREEN_PASSED";
        Status status = "LIQUIDITY_RESEARCH_SCREEN_PASSED".equals(reason)
            ? Status.RESEARCH_PASS : Status.ILLIQUID;
        return new Decision(status, reason, STRATEGY_ID, contract.instrumentKey(),
            provider, asOf, REQUIRED_SNAPSHOTS, rounded(maxSpread), rounded(friction),
            rounded(oiLots), rounded(volumeLots), minimumBidDepth, minimumAskDepth);
    }

    private static boolean validContract(Contract c, Instant asOf) {
        if (c == null || !c.providerVerified() || c.instrumentKey() == null
                || !c.instrumentKey().matches("NSE_FO\\|[A-Za-z0-9._-]{1,64}")
                || c.underlyingKey() == null
                || !c.underlyingKey().matches(
                    "(?:NSE_EQ\\|[A-Za-z0-9._-]{1,64}|NSE_INDEX\\|[A-Za-z0-9 ._-]{1,64})")
                || c.expiry() == null || c.kind() == null
                || c.lotSize() <= 0 || c.lotSize() > 100000) {
            return false;
        }
        if (c.kind() == DerivativeKind.FUTURE ? c.strikePrice() != null
                : c.strikePrice() == null || c.strikePrice().signum() <= 0) {
            return false;
        }
        long days = ChronoUnit.DAYS.between(
            asOf.atZone(IST).toLocalDate(), c.expiry());
        return days >= 0 && days <= 100;
    }

    private static boolean validSnapshot(MarketSnapshot s, Contract c, Instant asOf) {
        if (s == null || !s.exchangeSessionVerified() || !s.quoteVerified()
                || !s.oiVerified() || !s.volumeVerified()
                || !c.instrumentKey().equals(s.instrumentKey())
                || !("UPSTOX_V3".equals(s.provider())
                    || "UPSTOX_ANALYTICS".equals(s.provider())
                    || "ANGEL_ONE_SMARTAPI".equals(s.provider()))
                || !positive(s.bid()) || !positive(s.ask())
                || s.ask().compareTo(s.bid()) < 0
                || s.bidDepthUnits() < 0 || s.askDepthUnits() < 0
                || s.bidDepthUnits() > MAX_UNITS || s.askDepthUnits() > MAX_UNITS
                || s.openInterestUnits() <= 0 || s.openInterestUnits() > MAX_UNITS
                || s.cumulativeVolumeUnits() < 0
                || s.cumulativeVolumeUnits() > MAX_UNITS
                || s.exchangeAt() == null || s.observedAt() == null
                || s.oiFirstObservedAt() == null || s.volumeFirstObservedAt() == null) {
            return false;
        }
        return !s.exchangeAt().isAfter(s.observedAt())
            && !s.observedAt().isAfter(asOf)
            && !s.oiFirstObservedAt().isAfter(asOf)
            && !s.oiFirstObservedAt().isAfter(s.observedAt())
            && !s.volumeFirstObservedAt().isAfter(asOf)
            && !s.exchangeAt().isAfter(asOf)
            && !s.oiFirstObservedAt().isBefore(asOf.minus(MAX_OI_AGE))
            && !s.volumeFirstObservedAt().isBefore(asOf.minus(MAX_VOLUME_AGE))
            && !s.volumeFirstObservedAt().isAfter(s.observedAt());
    }

    private static boolean validThresholds(Thresholds t) {
        return t != null && within(t.minOpenInterestLots(), 1, 100000000)
            && within(t.minSessionVolumeLots(), 1, 100000000)
            && within(t.minDepthLotsEachSide(), 1, 1000)
            && within(t.maxSpreadBps(), 1, 5000)
            && within(t.maxEffectiveFrictionBps(), 1, 10000)
            && within(t.assumedPerLegImpactBps(), 1, 2000)
            && within(t.maxQuoteAgeSeconds(), 1, 15)
            && within(t.maxSnapshotGapSeconds(), 1, 45)
            && within(t.maxMidMoveBetweenSnapshotsBps(), 1, 5000);
    }

    private static boolean within(int x, int lower, int upper) {
        return x >= lower && x <= upper;
    }

    private static boolean positive(BigDecimal n) {
        return n != null && n.signum() > 0;
    }

    private static BigDecimal mid(MarketSnapshot s) {
        return s.ask().add(s.bid(), MC).divide(BigDecimal.valueOf(2), MC);
    }

    private static BigDecimal bps(BigDecimal numerator, BigDecimal denominator) {
        return numerator.multiply(BPS, MC).divide(denominator, MC);
    }

    private static BigDecimal rounded(BigDecimal n) {
        return n.setScale(4, RoundingMode.HALF_UP);
    }

    private static Decision blocked(Status status, String reason, Instant asOf) {
        return new Decision(status, reason, STRATEGY_ID, null, null, asOf,
            0, null, null, null, null, 0, 0);
    }
}
