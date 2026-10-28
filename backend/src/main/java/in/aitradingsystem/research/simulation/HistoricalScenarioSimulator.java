package in.aitradingsystem.research.simulation;

import in.aitradingsystem.marketdata.MarketCandle;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Deterministic long-only scenario simulator using contiguous historical OHLC return blocks.
 * If stop and target are touched in the same simulated bar, stop wins conservatively.
 */
public final class HistoricalScenarioSimulator {
    public static final String METHODOLOGY = "BLOCK_BOOTSTRAP_CONSERVATIVE_V1";
    private static final BigDecimal TEN_THOUSAND = new BigDecimal("10000");

    public ScenarioSimulationResult simulate(ScenarioSimulationRequest request, List<MarketCandle> history) {
        if (request == null) throw new IllegalArgumentException("request is required");
        if (history == null || history.size() < Math.max(30, request.blockSize() + 2)) {
            throw new IllegalArgumentException("insufficient historical candles for simulation");
        }

        List<Observation> observations = observations(history);
        if (observations.size() <= request.blockSize()) {
            throw new IllegalArgumentException("insufficient historical transitions for requested block size");
        }

        BigDecimal slippage = request.entryPrice()
                .multiply(BigDecimal.valueOf(request.slippageBps()))
                .divide(TEN_THOUSAND, 10, RoundingMode.HALF_UP);
        BigDecimal effectiveEntry = request.entryPrice().add(slippage);
        BigDecimal risk = effectiveEntry.subtract(request.stopPrice());
        if (risk.signum() <= 0) throw new IllegalArgumentException("effective entry must remain above stop after slippage");

        SplittableRandom random = new SplittableRandom(request.seed());
        List<Double> rOutcomes = new ArrayList<>(request.simulations());
        int targetFirst = 0;
        int stopFirst = 0;
        int neither = 0;

        for (int simulation = 0; simulation < request.simulations(); simulation++) {
            BigDecimal price = effectiveEntry;
            Outcome outcome = Outcome.NEITHER;
            int remaining = request.horizonBars();

            while (remaining > 0 && outcome == Outcome.NEITHER) {
                int maxStart = observations.size() - request.blockSize();
                int start = random.nextInt(maxStart + 1);
                for (int j = 0; j < request.blockSize() && remaining > 0; j++, remaining--) {
                    Observation observation = observations.get(start + j);
                    BigDecimal high = price.multiply(observation.highFactor());
                    BigDecimal low = price.multiply(observation.lowFactor());
                    BigDecimal close = price.multiply(observation.closeFactor());

                    boolean stopHit = low.compareTo(request.stopPrice()) <= 0;
                    boolean targetHit = high.compareTo(request.targetPrice()) >= 0;
                    if (stopHit) {
                        outcome = Outcome.STOP_FIRST;
                        price = request.stopPrice();
                        break;
                    }
                    if (targetHit) {
                        outcome = Outcome.TARGET_FIRST;
                        price = request.targetPrice();
                        break;
                    }
                    price = close.max(new BigDecimal("0.01"));
                }
            }

            double r = outcomeR(request, effectiveEntry, risk, price);
            rOutcomes.add(r);
            if (outcome == Outcome.TARGET_FIRST) targetFirst++;
            else if (outcome == Outcome.STOP_FIRST) stopFirst++;
            else neither++;
        }

        Collections.sort(rOutcomes);
        return new ScenarioSimulationResult(
                METHODOLOGY,
                request.seed(),
                request.simulations(),
                observations.size(),
                request.horizonBars(),
                request.blockSize(),
                targetFirst,
                stopFirst,
                neither,
                targetFirst / (double) request.simulations(),
                stopFirst / (double) request.simulations(),
                neither / (double) request.simulations(),
                rOutcomes.stream().mapToDouble(Double::doubleValue).average().orElse(0.0),
                percentile(rOutcomes, 0.05),
                percentile(rOutcomes, 0.50),
                percentile(rOutcomes, 0.95));
    }

    private static List<Observation> observations(List<MarketCandle> history) {
        List<Observation> observations = new ArrayList<>(history.size() - 1);
        for (int i = 1; i < history.size(); i++) {
            MarketCandle previous = history.get(i - 1);
            MarketCandle current = history.get(i);
            if (previous.close().signum() <= 0) continue;
            observations.add(new Observation(
                    current.high().divide(previous.close(), 10, RoundingMode.HALF_UP),
                    current.low().divide(previous.close(), 10, RoundingMode.HALF_UP),
                    current.close().divide(previous.close(), 10, RoundingMode.HALF_UP)));
        }
        return List.copyOf(observations);
    }

    private static double outcomeR(ScenarioSimulationRequest request, BigDecimal effectiveEntry,
                                   BigDecimal risk, BigDecimal exitPrice) {
        BigDecimal roundTripCost = effectiveEntry
                .multiply(BigDecimal.valueOf(request.roundTripCostBps()))
                .divide(TEN_THOUSAND, 10, RoundingMode.HALF_UP);
        return exitPrice.subtract(effectiveEntry).subtract(roundTripCost)
                .divide(risk, 10, RoundingMode.HALF_UP).doubleValue();
    }

    private static double percentile(List<Double> sorted, double quantile) {
        if (sorted.isEmpty()) return 0.0;
        int index = (int) Math.round((sorted.size() - 1) * quantile);
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

    private enum Outcome { TARGET_FIRST, STOP_FIRST, NEITHER }

    private record Observation(BigDecimal highFactor, BigDecimal lowFactor, BigDecimal closeFactor) {}
}
