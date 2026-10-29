package in.aitradingsystem.research.simulation;

import in.aitradingsystem.marketdata.IndianCashMarketCalendar;
import in.aitradingsystem.marketdata.MarketCandle;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Builds exact-length historical blocks that never cross an NSE cash session boundary and
 * selects a deterministic regime-compatible sampling pool.
 */
public final class HistoricalBlockSampler {
    private static final LocalTime SESSION_OPEN = LocalTime.of(9, 15);
    private static final LocalTime SESSION_CLOSE = LocalTime.of(15, 30);
    private final RegimeClassifier regimeClassifier;

    public HistoricalBlockSampler() {
        this(new RegimeClassifier());
    }

    HistoricalBlockSampler(RegimeClassifier regimeClassifier) {
        if (regimeClassifier == null) throw new IllegalArgumentException("regimeClassifier is required");
        this.regimeClassifier = regimeClassifier;
    }

    public SamplingPool select(List<MarketCandle> history, Instant dataCutoffAt, int blockSize) {
        if (history == null || dataCutoffAt == null) throw new IllegalArgumentException("history and dataCutoffAt are required");
        if (blockSize < 1) throw new IllegalArgumentException("blockSize must be positive");

        List<MarketCandle> eligible = history.stream()
                .filter(c -> c != null && c.candleTime() != null && !c.candleTime().isAfter(dataCutoffAt))
                .sorted(Comparator.comparing(MarketCandle::candleTime))
                .toList();
        validateChronology(eligible);
        if (eligible.size() < Math.max(RegimeClassifier.DEFAULT_WINDOW + 2, blockSize + 2)) {
            throw new IllegalArgumentException("insufficient completed candles for session-safe block sampling");
        }

        RegimeSnapshot targetRegime = regimeClassifier.classify(eligible, dataCutoffAt);
        List<HistoricalBlock> allBlocks = buildBlocks(eligible, dataCutoffAt, blockSize);
        if (allBlocks.isEmpty()) {
            throw new IllegalArgumentException("no eligible session-safe historical blocks for requested block size");
        }

        List<HistoricalBlock> exact = allBlocks.stream()
                .filter(block -> block.regimeSnapshot().volatilityRegime() == targetRegime.volatilityRegime())
                .filter(block -> block.regimeSnapshot().volumeRegime() == targetRegime.volumeRegime())
                .toList();
        if (!exact.isEmpty()) {
            return new SamplingPool(exact, allBlocks.size(), targetRegime,
                    BlockSamplingFallbackTier.EXACT_VOLATILITY_AND_VOLUME, transitionCount(eligible));
        }

        List<HistoricalBlock> sameVolatility = allBlocks.stream()
                .filter(block -> block.regimeSnapshot().volatilityRegime() == targetRegime.volatilityRegime())
                .toList();
        if (!sameVolatility.isEmpty()) {
            return new SamplingPool(sameVolatility, allBlocks.size(), targetRegime,
                    BlockSamplingFallbackTier.SAME_VOLATILITY, transitionCount(eligible));
        }

        return new SamplingPool(allBlocks, allBlocks.size(), targetRegime,
                BlockSamplingFallbackTier.ALL_ELIGIBLE, transitionCount(eligible));
    }

    private List<HistoricalBlock> buildBlocks(List<MarketCandle> eligible, Instant dataCutoffAt, int blockSize) {
        List<HistoricalBlock> blocks = new ArrayList<>();
        int index = 0;
        while (index < eligible.size()) {
            LocalDate tradeDate = tradeDate(eligible.get(index));
            int endExclusive = index + 1;
            while (endExclusive < eligible.size() && tradeDate(eligible.get(endExclusive)).equals(tradeDate)) {
                endExclusive++;
            }
            List<MarketCandle> session = eligible.subList(index, endExclusive).stream()
                    .filter(this::insideCashSession)
                    .toList();
            addSessionBlocks(eligible, session, dataCutoffAt, blockSize, blocks);
            index = endExclusive;
        }
        return List.copyOf(blocks);
    }

    private void addSessionBlocks(List<MarketCandle> allEligible, List<MarketCandle> session,
                                  Instant dataCutoffAt, int blockSize, List<HistoricalBlock> blocks) {
        if (session.size() < blockSize + 1) return;
        for (int previousIndex = 0; previousIndex + blockSize < session.size(); previousIndex++) {
            MarketCandle previous = session.get(previousIndex);
            if (previous.close() == null || previous.close().signum() <= 0) continue;
            MarketCandle firstObserved = session.get(previousIndex + 1);
            Instant regimeCutoff = previous.candleTime();
            if (regimeCutoff.isAfter(dataCutoffAt)) continue;

            RegimeSnapshot snapshot;
            try {
                snapshot = regimeClassifier.classify(allEligible, regimeCutoff);
            } catch (IllegalArgumentException insufficientPointInTimeHistory) {
                continue;
            }

            List<HistoricalBlock.Observation> observations = new ArrayList<>(blockSize);
            boolean valid = true;
            for (int offset = 1; offset <= blockSize; offset++) {
                MarketCandle prior = session.get(previousIndex + offset - 1);
                MarketCandle current = session.get(previousIndex + offset);
                if (current.candleTime().isAfter(dataCutoffAt)
                        || prior.close() == null || prior.close().signum() <= 0
                        || current.high() == null || current.low() == null || current.close() == null
                        || current.high().signum() <= 0 || current.low().signum() <= 0 || current.close().signum() <= 0) {
                    valid = false;
                    break;
                }
                observations.add(new HistoricalBlock.Observation(
                        current.high().divide(prior.close(), 10, RoundingMode.HALF_UP),
                        current.low().divide(prior.close(), 10, RoundingMode.HALF_UP),
                        current.close().divide(prior.close(), 10, RoundingMode.HALF_UP)));
            }
            if (valid && observations.size() == blockSize) {
                blocks.add(new HistoricalBlock(tradeDate(firstObserved), firstObserved.candleTime(), observations, snapshot));
            }
        }
    }

    private static void validateChronology(List<MarketCandle> candles) {
        for (int i = 1; i < candles.size(); i++) {
            if (!candles.get(i - 1).candleTime().isBefore(candles.get(i).candleTime())) {
                throw new IllegalArgumentException("completed candles must be strictly chronological");
            }
        }
    }

    private boolean insideCashSession(MarketCandle candle) {
        LocalTime time = candle.candleTime().atZone(IndianCashMarketCalendar.ZONE).toLocalTime();
        return !time.isBefore(SESSION_OPEN) && time.isBefore(SESSION_CLOSE);
    }

    private static LocalDate tradeDate(MarketCandle candle) {
        return candle.candleTime().atZone(IndianCashMarketCalendar.ZONE).toLocalDate();
    }

    private static int transitionCount(List<MarketCandle> candles) {
        int count = 0;
        for (int i = 1; i < candles.size(); i++) {
            MarketCandle previous = candles.get(i - 1);
            MarketCandle current = candles.get(i);
            if (tradeDate(previous).equals(tradeDate(current))) count++;
        }
        return count;
    }

    public record SamplingPool(
            List<HistoricalBlock> blocks,
            int allEligibleBlockCount,
            RegimeSnapshot targetRegime,
            BlockSamplingFallbackTier fallbackTier,
            int historicalSamples) {
        public SamplingPool {
            if (blocks == null || blocks.isEmpty()) throw new IllegalArgumentException("sampling blocks are required");
            if (allEligibleBlockCount < blocks.size()) throw new IllegalArgumentException("allEligibleBlockCount cannot be smaller than selected pool");
            if (targetRegime == null || fallbackTier == null) throw new IllegalArgumentException("regime provenance is required");
            if (historicalSamples < 0) throw new IllegalArgumentException("historicalSamples cannot be negative");
            blocks = List.copyOf(blocks);
        }

        public int eligibleBlockCount() {
            return blocks.size();
        }
    }
}
