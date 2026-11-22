package in.aitradingsystem.tradingrooms;

import java.time.Instant;
import java.util.List;

/**
 * Deterministic, after-cost accounting over already completed, externally
 * supplied historical round trips. It fetches no data, infers no fills, and
 * cannot certify source custody, a backtest, PAPER, or LIVE execution.
 *
 * The caller must independently attest each source and original trade
 * execution before using this component in an acceptance pipeline.
 */
public final class FnoHistoricalAfterCostWalkForwardV1 {
    private FnoHistoricalAfterCostWalkForwardV1() {}

    public static final String CONTRACT = "minttick.fno43.aftercost-oos-accounting.v1";

    public record CompletedTrade(
        String strategyId, String contractKey,
        Instant entryDecisionAt, Instant entrySourceFirstKnownAt, Instant entryFillAt,
        Instant exitDecisionAt, Instant exitSourceFirstKnownAt, Instant exitFillAt,
        String entrySourceSha256, String exitSourceSha256,
        long grossPnlPaise, long brokerageAndExchangePaise, long taxesPaise,
        long bidAskAndSlippagePaise, long marketImpactPaise, long financingPaise,
        long capitalAtRiskPaise) {}

    public record Report(
        String contractVersion, String strategyId, String state, String reasonCode,
        int trainingTrades, int outOfSampleTrades,
        long trainingAfterCostPnlPaise, long outOfSampleGrossPnlPaise,
        long outOfSampleCostsPaise, long outOfSampleNetPnlPaise,
        long outOfSampleMaxDrawdownPaise, int outOfSampleLossTrades,
        boolean independentHistoricalMarketE2E,
        boolean paperLedgerE2E, boolean liveBrokerE2E,
        boolean ordersPlaced) {}

    /**
     * Split at an absolute original decision-time boundary. A trade that
     * straddles the split is rejected, not assigned to whichever split wins.
     * Strictly sequential round trips are required for this single-position
     * ledger; multi-position/multi-leg executions need their own reconciler.
     */
    public static Report evaluate(
        String strategyId, Instant asOf, Instant trainingEnd,
        boolean researchSourceRightsAttested, List<CompletedTrade> completed) {
        if (strategyId == null
            || !strategyId.matches("strategy\\.STR-(17[0-9]|18[0-9]|19[0-9]|20[0-9]|21[0-2])"))
            return blocked(strategyId, "STRATEGY_OUTSIDE_FNO_43_SCOPE");
        if (asOf == null || trainingEnd == null || !trainingEnd.isBefore(asOf))
            return blocked(strategyId, "HISTORICAL_WALK_FORWARD_SPLIT_INVALID");
        if (!researchSourceRightsAttested)
            return blocked(strategyId, "SOURCE_RESEARCH_RIGHTS_NOT_ATTESTED");
        if (completed == null || completed.isEmpty() || completed.size() > 10000)
            return blocked(strategyId, "ORIGINAL_COMPLETED_TRADES_MISSING_OR_UNBOUNDED");

        long trainNet = 0, oosGross = 0, oosCosts = 0, oosNet = 0;
        long equity = 0, peak = 0, drawdown = 0;
        int train = 0, oos = 0, losing = 0;
        Instant lastExit = null;
        try {
            for (CompletedTrade t : completed) {
                if (t == null || !strategyId.equals(t.strategyId())
                    || t.contractKey() == null
                    || !t.contractKey().matches("NSE_(FO|EQ)\\|[A-Za-z0-9._-]{1,64}")
                    || t.entryDecisionAt() == null || t.entrySourceFirstKnownAt() == null
                    || t.entryFillAt() == null || t.exitDecisionAt() == null
                    || t.exitSourceFirstKnownAt() == null || t.exitFillAt() == null
                    || !validDigest(t.entrySourceSha256())
                    || !validDigest(t.exitSourceSha256())
                    || t.entrySourceFirstKnownAt().isAfter(t.entryDecisionAt())
                    || t.entryDecisionAt().isAfter(t.entryFillAt())
                    || t.entryFillAt().isAfter(t.exitDecisionAt())
                    || t.exitSourceFirstKnownAt().isAfter(t.exitDecisionAt())
                    || t.exitDecisionAt().isAfter(t.exitFillAt())
                    || t.exitFillAt().isAfter(asOf)
                    || (lastExit != null && t.entryDecisionAt().isBefore(lastExit))
                    || t.capitalAtRiskPaise() <= 0
                    || t.brokerageAndExchangePaise() < 0 || t.taxesPaise() < 0
                    || t.bidAskAndSlippagePaise() < 0 || t.marketImpactPaise() < 0
                    || t.financingPaise() < 0)
                    return blocked(strategyId, "ORIGINAL_FILL_PROVENANCE_OR_ORDER_INVALID");

                // Never use an outcome from after the train/OOS boundary
                // to infer a historical training observation.
                boolean trainOnly = !t.exitFillAt().isAfter(trainingEnd);
                boolean oosOnly = !t.entryDecisionAt().isBefore(trainingEnd);
                if (!trainOnly && !oosOnly)
                    return blocked(strategyId, "TRAIN_OOS_TRADE_CROSSES_BOUNDARY");

                long costs = Math.addExact(t.brokerageAndExchangePaise(), t.taxesPaise());
                costs = Math.addExact(costs, t.bidAskAndSlippagePaise());
                costs = Math.addExact(costs, t.marketImpactPaise());
                costs = Math.addExact(costs, t.financingPaise());
                long net = Math.subtractExact(t.grossPnlPaise(), costs);
                if (trainOnly) {
                    train++;
                    trainNet = Math.addExact(trainNet, net);
                } else {
                    oos++;
                    oosGross = Math.addExact(oosGross, t.grossPnlPaise());
                    oosCosts = Math.addExact(oosCosts, costs);
                    oosNet = Math.addExact(oosNet, net);
                    equity = Math.addExact(equity, net);
                    peak = Math.max(peak, equity);
                    drawdown = Math.max(drawdown, Math.subtractExact(peak, equity));
                    if (net < 0) losing++;
                }
                lastExit = t.exitFillAt();
            }
        } catch (ArithmeticException overflow) {
            return blocked(strategyId, "AFTER_COST_ACCOUNTING_OVERFLOW");
        }
        if (train == 0 || oos == 0)
            return blocked(strategyId, "TRAIN_OR_OOS_COMPLETED_TRADES_MISSING");

        // Deliberately never claim market E2E: a caller-provided receipt,
        // digest, or bool cannot prove the source or actual broker fills.
        return new Report(CONTRACT, strategyId,
            "AFTER_COST_WALK_FORWARD_ACCOUNTED_RESEARCH_ONLY_NOT_E2E",
            "INDEPENDENT_ORIGINAL_SOURCE_AND_FILL_ACCEPTANCE_STILL_REQUIRED",
            train, oos, trainNet, oosGross, oosCosts, oosNet,
            drawdown, losing, false, false, false, false);
    }

    private static boolean validDigest(String digest) {
        return digest != null && digest.matches("[0-9a-f]{64}");
    }

    private static Report blocked(String id, String reason) {
        return new Report(CONTRACT, id, "BLOCKED_NOT_HISTORICAL_E2E",
            reason, 0, 0, 0, 0, 0, 0, 0, 0,
            false, false, false, false);
    }
}
