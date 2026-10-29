package in.aitradingsystem.scanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.aitradingsystem.marketdata.MarketCandle;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class StrategyHardeningFixtureMatrixTest {
    private static final Duration MAX_AGE = Duration.ofMinutes(10);
    private static final Instant START = Instant.parse("2026-10-28T03:45:00Z");

    private final List<ScannerStrategy> strategies = List.of(
            new VolumeBreakoutStrategy(),
            new MovingAverageReclaimStrategy(),
            new OpeningRangeBreakoutStrategy(),
            new VwapMomentumStrategy(),
            new RsiReversalStrategy());

    @Test
    void positiveFixturesProduceCandidateForEveryStrategy() {
        for (ScannerStrategy strategy : strategies) {
            List<MarketCandle> bars = positiveBars(strategy);
            List<ScanCandidate> result = engine(strategy).scan(context(bars, bars.getLast().candleTime().plusSeconds(60)));
            assertTrue(result.stream().anyMatch(c -> c.strategy().equals(strategy.name())), strategy.name());
        }
    }

    @Test
    void negativeFixturesProduceNoCandidateForEveryStrategy() {
        for (ScannerStrategy strategy : strategies) {
            List<MarketCandle> bars = flatBars(Math.max(strategy.minimumCandles(), 21));
            assertTrue(engine(strategy).scan(context(bars, bars.getLast().candleTime().plusSeconds(60))).isEmpty(), strategy.name());
        }
    }

    @Test
    void exactStalenessBoundaryRemainsEligibleForEveryStrategy() {
        for (ScannerStrategy strategy : strategies) {
            List<MarketCandle> bars = positiveBars(strategy);
            Instant cutoff = bars.getLast().candleTime().plus(MAX_AGE);
            assertFalse(engine(strategy).scan(context(bars, cutoff)).isEmpty(), strategy.name());
        }
    }

    @Test
    void insufficientHistoryFailsClosedForEveryStrategy() {
        for (ScannerStrategy strategy : strategies) {
            int count = Math.max(1, strategy.minimumCandles() - 1);
            List<MarketCandle> bars = flatBars(count);
            assertTrue(engine(strategy).scan(context(bars, bars.getLast().candleTime().plusSeconds(60))).isEmpty(), strategy.name());
        }
    }

    @Test
    void futureAndStaleCandlesFailClosedForEveryStrategy() {
        for (ScannerStrategy strategy : strategies) {
            List<MarketCandle> bars = positiveBars(strategy);
            Instant latest = bars.getLast().candleTime();
            assertTrue(engine(strategy).scan(context(bars, latest.minusSeconds(1))).isEmpty(), strategy.name() + " future");
            assertTrue(engine(strategy).scan(context(bars, latest.plus(MAX_AGE).plusSeconds(1))).isEmpty(), strategy.name() + " stale");
        }
    }

    @Test
    void duplicateAndOutOfOrderCandlesFailClosedForEveryStrategy() {
        for (ScannerStrategy strategy : strategies) {
            List<MarketCandle> bars = new ArrayList<>(positiveBars(strategy));
            int last = bars.size() - 1;
            MarketCandle latest = bars.get(last);
            Instant duplicateTime = bars.get(last - 1).candleTime();
            bars.set(last, copyAt(latest, duplicateTime));
            assertTrue(engine(strategy).scan(context(List.copyOf(bars), duplicateTime.plusSeconds(60))).isEmpty(), strategy.name());
        }
    }

    @Test
    void deterministicReplayMatchesForEveryStrategy() {
        for (ScannerStrategy strategy : strategies) {
            List<MarketCandle> bars = positiveBars(strategy);
            ScanContext context = context(bars, bars.getLast().candleTime().plusSeconds(60));
            assertEquals(engine(strategy).scan(context), engine(strategy).scan(context), strategy.name());
        }
    }

    private static ScannerEngine engine(ScannerStrategy strategy) {
        return new ScannerEngine(List.of(strategy), MAX_AGE);
    }

    private static ScanContext context(List<MarketCandle> bars, Instant evaluatedAt) {
        return new ScanContext(1L, "RELIANCE-EQ", "NSE", "FIVE_MINUTE", evaluatedAt, bars);
    }

    private static List<MarketCandle> positiveBars(ScannerStrategy strategy) {
        if (strategy instanceof VolumeBreakoutStrategy) return volumeBreakoutBars();
        if (strategy instanceof MovingAverageReclaimStrategy) return movingAverageReclaimBars();
        if (strategy instanceof OpeningRangeBreakoutStrategy) return openingRangeBreakoutBars();
        if (strategy instanceof VwapMomentumStrategy) return vwapMomentumBars();
        if (strategy instanceof RsiReversalStrategy) return rsiReversalBars();
        throw new IllegalArgumentException("Unknown strategy " + strategy.name());
    }

    private static List<MarketCandle> volumeBreakoutBars() {
        List<MarketCandle> bars = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            BigDecimal close = new BigDecimal("100.00").add(BigDecimal.valueOf(i).multiply(new BigDecimal("0.05")));
            bars.add(candle(i + 1L, START.plusSeconds(i * 300L), close.subtract(new BigDecimal("0.20")),
                    close.add(new BigDecimal("0.30")), close.subtract(new BigDecimal("0.40")), close, 1000));
        }
        bars.add(candle(21, START.plusSeconds(20 * 300L), bd("102"), bd("106"), bd("103"), bd("105"), 2500));
        return List.copyOf(bars);
    }

    private static List<MarketCandle> movingAverageReclaimBars() {
        List<MarketCandle> bars = new ArrayList<>();
        for (int i = 0; i < 19; i++) bars.add(candle(i + 1L, START.plusSeconds(i * 300L), bd("100"), bd("101"), bd("99"), bd("100"), 1000));
        bars.add(candle(20, START.plusSeconds(19 * 300L), bd("100"), bd("100"), bd("94"), bd("95"), 1000));
        bars.add(candle(21, START.plusSeconds(20 * 300L), bd("96"), bd("111"), bd("95"), bd("110"), 1500));
        return List.copyOf(bars);
    }

    private static List<MarketCandle> openingRangeBreakoutBars() {
        return List.of(
                candle(1, START, bd("100"), bd("101"), bd("99.5"), bd("100.5"), 1000),
                candle(2, START.plusSeconds(300), bd("100.5"), bd("101.5"), bd("100"), bd("101"), 1100),
                candle(3, START.plusSeconds(600), bd("101"), bd("101.4"), bd("100.8"), bd("101.2"), 900),
                candle(4, START.plusSeconds(900), bd("101.4"), bd("103.5"), bd("101.3"), bd("103"), 1500));
    }

    private static List<MarketCandle> vwapMomentumBars() {
        List<MarketCandle> bars = new ArrayList<>();
        for (int i = 0; i < 19; i++) {
            BigDecimal close = bd("100").add(BigDecimal.valueOf(i).multiply(bd("0.02")));
            bars.add(candle(i + 1, START.plusSeconds(i * 300L), close, close.add(bd("0.20")), close.subtract(bd("0.20")), close, 1000));
        }
        bars.add(candle(20, START.plusSeconds(19 * 300L), bd("102"), bd("105.5"), bd("101.8"), bd("105"), 2500));
        return List.copyOf(bars);
    }

    private static List<MarketCandle> rsiReversalBars() {
        List<MarketCandle> bars = new ArrayList<>();
        BigDecimal close = bd("100");
        bars.add(candle(1, START, close, close.add(bd("0.2")), close.subtract(bd("0.2")), close, 1000));
        for (int i = 1; i <= 14; i++) {
            close = close.subtract(BigDecimal.ONE);
            bars.add(candle(i + 1, START.plusSeconds(i * 300L), close.add(bd("0.2")), close.add(bd("0.3")), close.subtract(bd("0.5")), close, 1000));
        }
        close = close.add(bd("7"));
        bars.add(candle(16, START.plusSeconds(15 * 300L), close.subtract(BigDecimal.ONE), close.add(bd("0.5")), close.subtract(bd("0.4")), close, 1600));
        return List.copyOf(bars);
    }

    private static List<MarketCandle> flatBars(int count) {
        List<MarketCandle> bars = new ArrayList<>();
        for (int i = 0; i < count; i++) bars.add(candle(i + 1L, START.plusSeconds(i * 300L), bd("100"), bd("101"), bd("99"), bd("100"), 1000));
        return List.copyOf(bars);
    }

    private static MarketCandle copyAt(MarketCandle candle, Instant time) {
        return new MarketCandle(candle.id(), candle.instrumentId(), candle.exchange(), candle.tradingSymbol(), candle.symbolToken(),
                candle.catalogueVersion(), candle.interval(), time, candle.open(), candle.high(), candle.low(), candle.close(), candle.volume(), candle.source(), candle.fetchedAt());
    }

    private static MarketCandle candle(long id, Instant time, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close, long volume) {
        return new MarketCandle(id, 1L, "NSE", "RELIANCE-EQ", "2885", null, "FIVE_MINUTE", time,
                open, high, low, close, volume, "TEST_FIXTURE", time.plusSeconds(1));
    }

    private static BigDecimal bd(String value) { return new BigDecimal(value); }
}
