package in.aitradingsystem.backtest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import in.aitradingsystem.marketdata.MarketCandle;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SmaBacktestEngineTest {
    private final SmaBacktestEngine engine = new SmaBacktestEngine();

    @Test
    void rejectsInsufficientCandleHistory() {
        var config = config();
        assertThrows(IllegalArgumentException.class, () -> engine.run(candles(3), config));
    }

    @Test
    void rejectsUnsortedOrDuplicateCandles() {
        List<MarketCandle> items = new ArrayList<>(candles(24));
        MarketCandle first = items.getFirst();
        items.set(1, new MarketCandle(
            items.get(1).id(), items.get(1).instrumentId(), items.get(1).exchange(),
            items.get(1).tradingSymbol(), items.get(1).symbolToken(), items.get(1).catalogueVersion(),
            items.get(1).interval(), first.candleTime(), items.get(1).open(), items.get(1).high(),
            items.get(1).low(), items.get(1).close(), items.get(1).volume(), items.get(1).source(),
            items.get(1).fetchedAt()));
        assertThrows(IllegalArgumentException.class, () -> engine.run(items, config()));
    }

    @Test
    void producesDeterministicResultOnIdenticalInput() {
        List<MarketCandle> history = candles(80);
        BacktestEngineResult first = engine.run(history, config());
        BacktestEngineResult second = engine.run(history, config());

        assertEquals(first, second);
        assertEquals(history.size(), first.candlesEvaluated());
        assertEquals(first.tradeCount(), first.trades().size());
        assertEquals(first.netPnl(), first.finalEquity().subtract(new BigDecimal("100000.00")).setScale(2));
    }

    @Test
    void holdoutModeUsesEarlierCandlesOnlyForWarmupAndNeverOpensTrainingTrades() {
        List<MarketCandle> history = candles(100);
        Instant holdoutStart = history.get(70).candleTime();
        var result = engine.run(history, config(), holdoutStart);

        assertEquals(30, result.candlesEvaluated());
        assertEquals(history.stream().filter(c -> !c.candleTime().isBefore(holdoutStart)).count(),
            result.candlesEvaluated());
        assertEquals(result.tradeCount(), result.trades().size());
        result.trades().forEach(trade ->
            org.junit.jupiter.api.Assertions.assertFalse(trade.entryAt().isBefore(holdoutStart)));
    }

    @Test
    void holdoutModeRequiresWarmupAndEvaluationCandlesOnBothSidesOfBoundary() {
        List<MarketCandle> history = candles(20);
        assertThrows(IllegalArgumentException.class,
            () -> engine.run(history, config(), history.getFirst().candleTime()));
        assertThrows(IllegalArgumentException.class,
            () -> engine.run(history, config(), Instant.parse("2025-12-31T00:00:00Z")));
    }

    private static SmaBacktestEngine.Config config() {
        return new SmaBacktestEngine.Config(
            new BigDecimal("100000"), 3, 5, new BigDecimal("0.25"),
            new BigDecimal("0.0005"), new BigDecimal("0.001"),
            new BigDecimal("0.5"), new BigDecimal("2"));
    }

    private static List<MarketCandle> candles(int count) {
        List<MarketCandle> result = new ArrayList<>();
        Instant start = Instant.parse("2026-01-01T03:45:00Z");
        for (int i = 0; i < count; i++) {
            BigDecimal close = BigDecimal.valueOf(100 + (i * 0.8));
            BigDecimal open = close.subtract(new BigDecimal("0.20"));
            BigDecimal high = close.add(new BigDecimal("0.60"));
            BigDecimal low = close.subtract(new BigDecimal("0.60"));
            result.add(new MarketCandle(
                i + 1L, 101L, "NSE", "TEST-EQ", "test-token", UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),
                "FIVE_MINUTE", start.plusSeconds(i * 300L), open, high, low, close,
                10_000L + i * 100L, "TEST_FIXTURE", start.plusSeconds(i * 300L)));
        }
        return List.copyOf(result);
    }
}