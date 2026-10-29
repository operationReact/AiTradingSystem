package in.aitradingsystem.backtest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import in.aitradingsystem.marketdata.MarketCandle;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LookAheadBiasRegressionTest {
    private final SmaBacktestEngine engine = new SmaBacktestEngine();

    @Test
    void futureSpikeCannotChangeDecisionsMadeBeforeSpike() {
        List<MarketCandle> baseline = candles(36);
        int spikeIndex = 28;
        Instant spikeTime = baseline.get(spikeIndex).candleTime();

        List<MarketCandle> withFutureSpike = new ArrayList<>(baseline);
        MarketCandle original = withFutureSpike.get(spikeIndex);
        BigDecimal spike = new BigDecimal("10000");
        withFutureSpike.set(spikeIndex, new MarketCandle(
            original.id(), original.instrumentId(), original.exchange(), original.tradingSymbol(),
            original.symbolToken(), original.catalogueVersion(), original.interval(), original.candleTime(),
            spike, spike.add(BigDecimal.ONE), spike.subtract(BigDecimal.ONE), spike,
            original.volume(), original.source(), original.fetchedAt()));

        var baselineResult = engine.run(baseline, config());
        var spikedResult = engine.run(List.copyOf(withFutureSpike), config());

        var baselinePreSpike = baselineResult.trades().stream()
            .filter(trade -> trade.entryAt().isBefore(spikeTime))
            .toList();
        var spikedPreSpike = spikedResult.trades().stream()
            .filter(trade -> trade.entryAt().isBefore(spikeTime))
            .toList();

        assertFalse(baselinePreSpike.isEmpty(), "fixture must exercise at least one pre-spike decision");
        assertEquals(baselinePreSpike, spikedPreSpike,
            "future candle values must not alter decisions already made before that candle");
    }

    @Test
    void boundedHistoryNeverExposesFutureIndex() {
        List<MarketCandle> history = candles(12);
        List<MarketCandle> visible = BacktestDataGuard.historyThrough(history, 6);

        assertEquals(7, visible.size());
        assertEquals(history.get(6), visible.getLast());
        assertFalse(visible.contains(history.get(7)));
    }

    @Test
    void guardRejectsDuplicateOrReorderedCandlesAndUsesNextObservedOpen() {
        List<MarketCandle> history = new ArrayList<>(candles(12));
        MarketCandle second = history.get(1);
        MarketCandle first = history.getFirst();
        history.set(1, copyAt(second, first.candleTime()));
        assertThrows(IllegalArgumentException.class, () -> BacktestDataGuard.validateChronology(history));

        List<MarketCandle> valid = candles(12);
        assertEquals(valid.get(7), BacktestDataGuard.nextExecutionCandle(valid, 6));
    }

    private static MarketCandle copyAt(MarketCandle candle, Instant time) {
        return new MarketCandle(
            candle.id(), candle.instrumentId(), candle.exchange(), candle.tradingSymbol(), candle.symbolToken(),
            candle.catalogueVersion(), candle.interval(), time, candle.open(), candle.high(), candle.low(),
            candle.close(), candle.volume(), candle.source(), candle.fetchedAt());
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
        UUID catalogueVersion = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        for (int i = 0; i < count; i++) {
            BigDecimal close = BigDecimal.valueOf(100 + (i * 0.8));
            BigDecimal open = close.subtract(new BigDecimal("0.20"));
            BigDecimal high = close.add(new BigDecimal("0.60"));
            BigDecimal low = close.subtract(new BigDecimal("0.60"));
            Instant time = start.plusSeconds(i * 300L);
            result.add(new MarketCandle(
                i + 1L, 101L, "NSE", "TEST-EQ", "test-token", catalogueVersion,
                "FIVE_MINUTE", time, open, high, low, close,
                10_000L + i * 100L, "TEST_FIXTURE", time));
        }
        return List.copyOf(result);
    }
}
