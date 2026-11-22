package in.aitradingsystem.tradingrooms;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Source-independent historical replay bridge for two actual futures evaluators.
 *
 * <p>Unlike 43/43 research-integration CI, this adapter calls the same Java
 * evaluators used by research, once per historical as-of window. It never
 * infers original source publication from a midnight daily-candle label.
 * Callers MUST separately establish rights, instrument/corporate-action and
 * trading-calendar provenance before passing an original evidence window.
 * This bridge does not fetch providers, alter AWS data or place orders.
 *
 * <p>Even successful evaluator replay is NOT independently accepted market
 * E2E: actual original custody, out-of-sample outcomes, costs, broker ledger
 * and independent signoff are separate proof requirements.
 */
public final class FnoFuturesHistoricalReplayBridgeV1 {
    public static final String CONTRACT = "minttick.fno.futures-historical-structural-replay.v1";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final int MAX_WINDOWS = 64;
    private static final Set<String> SUPPORTED = Set.of(
        FnoFuturesOpenInterestTrendV1.STRATEGY_ID,
        FnoFuturesMomentumV1.STRATEGY_ID
    );
    private static final FnoFuturesOpenInterestTrendV1.Thresholds OI_THRESHOLDS =
        new FnoFuturesOpenInterestTrendV1.Thresholds(800, 3, 2500, 5000);
    private static final FnoFuturesMomentumV1.Thresholds MOMENTUM_THRESHOLDS =
        new FnoFuturesMomentumV1.Thresholds(100, 2, 11000, 2000, 2500,
            800, 20, 30);

    private FnoFuturesHistoricalReplayBridgeV1() {}

    /** A provider-listed, independently verified source instrument. */
    public record ContractIdentity(
        String instrumentKey, String underlyingKey, LocalDate listedFrom,
        LocalDate expiry, int lotSize, boolean providerVerified) {}

    /**
     * Rights are explicit per domain; caller assertions alone are not an
     * independently audited licence receipt or historical replay signoff.
     */
    public record EvidenceRights(
        boolean ohlcvResearchUse, boolean oiResearchUse,
        boolean contractDiscovery, boolean completedCalendarVerified,
        boolean originalFirstKnownVerified, boolean lotVolumeNormalized) {
        public boolean sufficientForResearch() {
            return ohlcvResearchUse && oiResearchUse && contractDiscovery
                && completedCalendarVerified && originalFirstKnownVerified
                && lotVolumeNormalized;
        }
    }

    /**
     * Immutable previously published session evidence. closeQuoteAt is the
     * true exchange near-close tick for OI trend; sourceFirstKnownAt and
     * oiFirstKnownAt are ORIGINAL publication/receipt times, never
     * reconstructed from an EOD session label or ingestion time.
     */
    public record HistoricalBar(
        LocalDate session, String instrumentKey, String provider,
        boolean calendarVerified, boolean ohlcvVerified,
        Instant closeQuoteAt, Instant sourceFirstKnownAt,
        Instant oiFirstKnownAt, BigDecimal open, BigDecimal high,
        BigDecimal low, BigDecimal close, long volumeUnderlyingUnits,
        long oiUnderlyingUnits) {}

    public record Window(Instant asOf, ContractIdentity contract,
                         EvidenceRights rights, String sourceReceiptSha256,
                         List<HistoricalBar> bars) {}

    /** No order or E2E pass claim may be produced by this type. */
    public record DecisionRow(
        Instant asOf, String strategyId, String result,
        String evaluatorReasonCode, int inputSessions,
        String inputReceiptHash) {}

    public record ReplayReport(
        String contractVersion, String strategyId, String state,
        String reasonCode, int windowsAttempted, int windowsEvaluatorRan,
        int researchCandidates, int noSignals, String outputSha256,
        boolean independentlyVerifiedMarketE2E,
        boolean paperExecutionReady, boolean liveExecutionReady,
        boolean brokerOrdersPlaced, List<DecisionRow> decisions) {}

    public static ReplayReport replay(String strategyId, List<Window> windows) {
        if (!SUPPORTED.contains(strategyId))
            return blocked(strategyId, "NO_TYPED_HISTORICAL_REPLAY_ADAPTER");
        if (windows == null || windows.isEmpty() || windows.size() > MAX_WINDOWS)
            return blocked(strategyId, "INVALID_REPLAY_WINDOW_COUNT");

        List<DecisionRow> rows = new ArrayList<>(windows.size());
        Set<Instant> seenAsOf = new HashSet<>();
        Instant previous = null;
        String contractKey = null;
        String provider = null;
        int candidates = 0, noSignals = 0;
        for (Window window : windows) {
            if (window == null || window.asOf() == null || window.contract() == null
                    || window.rights() == null || !window.rights().sufficientForResearch()
                    || window.sourceReceiptSha256() == null
                    || !window.sourceReceiptSha256().matches("[0-9a-f]{64}")) {
                return blocked(strategyId, "SOURCE_RIGHTS_CUSTODY_OR_WINDOW_MISSING");
            }
            if ((previous != null && !window.asOf().isAfter(previous))
                    || !seenAsOf.add(window.asOf())) {
                return blocked(strategyId, "REPLAY_DECISION_TIMES_NOT_INCREASING");
            }
            if (!window.contract().providerVerified()
                    || window.contract().instrumentKey() == null
                    || window.contract().underlyingKey() == null
                    || window.contract().listedFrom() == null
                    || window.contract().expiry() == null) {
                return blocked(strategyId, "SOURCE_CONTRACT_PROVENANCE_MISSING");
            }
            if (contractKey != null
                    && !contractKey.equals(window.contract().instrumentKey())) {
                return blocked(strategyId, "CONTRACT_ROLLOVER_IN_SINGLE_REPLAY");
            }
            contractKey = window.contract().instrumentKey();

            int needed = FnoFuturesMomentumV1.STRATEGY_ID.equals(strategyId) ? 7 : 5;
            if (window.bars() == null || window.bars().size() != needed)
                return blocked(strategyId, "MISSING_REQUIRED_SESSIONS");
            LocalDate prior = null;
            Set<LocalDate> dates = new HashSet<>();
            for (HistoricalBar bar : window.bars()) {
                if (bar == null || bar.session() == null
                        || !dates.add(bar.session()) || !bar.calendarVerified()
                        || !bar.ohlcvVerified() || bar.instrumentKey() == null
                        || !bar.instrumentKey().equals(contractKey)
                        || bar.provider() == null || !Set.of(
                            "UPSTOX_ANALYTICS", "ANGEL_ONE_SMARTAPI")
                            .contains(bar.provider())
                        || (provider != null && !provider.equals(bar.provider()))
                        || bar.closeQuoteAt() == null
                        || bar.sourceFirstKnownAt() == null
                        || bar.oiFirstKnownAt() == null
                        || bar.sourceFirstKnownAt().isAfter(window.asOf())
                        || bar.oiFirstKnownAt().isAfter(window.asOf())
                        || !bar.session().isBefore(
                            window.asOf().atZone(IST).toLocalDate())
                        || (prior != null && !bar.session().isAfter(prior))) {
                    return blocked(strategyId, "INVALID_OR_FUTURE_KNOWN_SESSION");
                }
                provider = bar.provider();
                prior = bar.session();
            }

            String status, reason;
            if (FnoFuturesOpenInterestTrendV1.STRATEGY_ID.equals(strategyId)) {
                var c = window.contract();
                var contract = new FnoFuturesOpenInterestTrendV1.Contract(
                    c.instrumentKey(), c.underlyingKey(), c.expiry(),
                    c.lotSize(), c.providerVerified());
                var bars = window.bars().stream().map(b ->
                    new FnoFuturesOpenInterestTrendV1.CompletedSession(
                        b.session(), b.calendarVerified(), b.instrumentKey(),
                        b.provider(), b.closeQuoteAt(), b.oiFirstKnownAt(),
                        b.oiUnderlyingUnits(), b.volumeUnderlyingUnits())
                ).toList();
                var result = FnoFuturesOpenInterestTrendV1.evaluate(contract,
                    bars, new FnoFuturesOpenInterestTrendV1.SourceRights(
                        window.rights().oiResearchUse(),
                        window.rights().contractDiscovery()),
                    OI_THRESHOLDS, window.asOf());
                status = result.status().name();
                reason = result.reasonCode();
            } else {
                var c = window.contract();
                var contract = new FnoFuturesMomentumV1.Contract(
                    c.instrumentKey(), c.underlyingKey(), c.listedFrom(),
                    c.expiry(), c.lotSize(), c.providerVerified());
                var bars = window.bars().stream().map(b ->
                    new FnoFuturesMomentumV1.CompletedSession(
                        b.session(), b.calendarVerified(), b.instrumentKey(),
                        b.provider(), b.open(), b.high(), b.low(), b.close(),
                        b.ohlcvVerified(), b.sourceFirstKnownAt(),
                        b.volumeUnderlyingUnits(), b.oiUnderlyingUnits(),
                        b.oiFirstKnownAt())
                ).toList();
                var result = FnoFuturesMomentumV1.evaluate(contract, bars,
                    new FnoFuturesMomentumV1.SourceRights(
                        window.rights().ohlcvResearchUse(),
                        window.rights().oiResearchUse(),
                        window.rights().contractDiscovery()),
                    MOMENTUM_THRESHOLDS, window.asOf());
                status = result.status().name();
                reason = result.reasonCode();
            }
            if (status.startsWith("BLOCKED"))
                return blocked(strategyId, "EVALUATOR_" + reason);
            if ("RESEARCH_CANDIDATE".equals(status)) candidates++;
            if ("NO_SIGNAL".equals(status)) noSignals++;
            rows.add(new DecisionRow(window.asOf(), strategyId,
                status, reason, needed, window.sourceReceiptSha256()));
            previous = window.asOf();
        }
        var decisions = List.copyOf(rows);
        return new ReplayReport(CONTRACT, strategyId,
            "EVALUATOR_REPLAY_RESEARCH_ONLY_NOT_E2E", "AFTER_COST_OOS_AND_INDEPENDENT_SOURCE_PROOF_MISSING",
            decisions.size(), decisions.size(), candidates, noSignals,
            fingerprint(strategyId, decisions),
            false, false, false, false, decisions);
    }

    private static ReplayReport blocked(String id, String code) {
        return new ReplayReport(CONTRACT, id, "BLOCKED_NOT_E2E", code,
            0, 0, 0, 0, null, false, false, false, false, List.of());
    }

    private static String fingerprint(String id, List<DecisionRow> rows) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (DecisionRow row : rows) {
                String value = id + "|" + row.asOf() + "|" + row.result()
                    + "|" + row.evaluatorReasonCode() + "|"
                    + row.inputReceiptHash() + "\n";
                digest.update(value.getBytes(StandardCharsets.UTF_8));
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA_256_MISSING", impossible);
        }
    }
}
