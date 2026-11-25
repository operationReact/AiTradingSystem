package in.aitradingsystem.backtest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.aitradingsystem.marketdata.HistoricalCandleInterval;
import in.aitradingsystem.marketdata.MarketCandle;
import in.aitradingsystem.scanner.ScanCandidate;
import in.aitradingsystem.scanner.ScanContext;
import in.aitradingsystem.scanner.ScannerStrategy;
import in.aitradingsystem.scanner.StrategyDescriptor;
import in.aitradingsystem.scanner.TradingStyle;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BacktestStrategySpiTest {
    private static final Instant CUTOFF = Instant.parse("2026-10-29T08:45:00Z");

    @Test
    void contextRejectsFutureCandles() {
        MarketCandle future = candle(CUTOFF.plusSeconds(300));
        assertThrows(IllegalArgumentException.class, () -> new BacktestContext(
                1L, "TEST-EQ", "NSE", "FIVE_MINUTE", CUTOFF, List.of(future), null,
                new BacktestAccountState(BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ZERO)));
    }

    @Test
    void adapterReusesScannerMetadataAndCandidateWithoutChangingMath() {
        ScannerStrategy strategy = new ScannerStrategy() {
            private final StrategyDescriptor descriptor = new StrategyDescriptor(
                    "TEST_V1", "1", "Test", Set.of(HistoricalCandleInterval.FIVE_MINUTE), 1,
                    TradingStyle.INTRADAY, false, "HEALTHY", true, false);

            @Override public String name() { return descriptor.code(); }
            @Override public int minimumCandles() { return 1; }
            @Override public StrategyDescriptor descriptor() { return descriptor; }
            @Override public Optional<ScanCandidate> evaluate(ScanContext context) {
                MarketCandle last = context.candles().getLast();
                return Optional.of(new ScanCandidate(
                        context.instrumentId(), context.tradingSymbol(), context.exchange(), context.interval(), name(),
                        0.9, last.close(), last.close().subtract(BigDecimal.ONE), last.close().add(BigDecimal.TEN),
                        last.candleTime(), List.of("fixture")));
            }
        };
        ScannerBacktestAdapter adapter = new ScannerBacktestAdapter(strategy);
        BacktestSignal signal = adapter.evaluate(context(List.of(candle(CUTOFF))));

        assertEquals("TEST_V1", adapter.code());
        assertEquals("1", adapter.version());
        assertTrue(signal.triggered());
        assertEquals(new BigDecimal("100"), signal.referencePrice());
        assertEquals(List.of("fixture"), signal.reasons());
    }

    @Test
    void adapterMapsEmptyCandidateToExplicitNoSignal() {
        ScannerStrategy strategy = new ScannerStrategy() {
            private final StrategyDescriptor descriptor = new StrategyDescriptor(
                    "EMPTY_V1", "1", "Empty", Set.of(HistoricalCandleInterval.FIVE_MINUTE), 1,
                    TradingStyle.INTRADAY, false, "HEALTHY", true, false);
            @Override public String name() { return descriptor.code(); }
            @Override public int minimumCandles() { return 1; }
            @Override public StrategyDescriptor descriptor() { return descriptor; }
            @Override public Optional<ScanCandidate> evaluate(ScanContext context) { return Optional.empty(); }
        };

        BacktestSignal signal = new ScannerBacktestAdapter(strategy).evaluate(context(List.of(candle(CUTOFF))));
        assertFalse(signal.triggered());
        assertTrue(signal.reasons().isEmpty());
    }

    private static BacktestContext context(List<MarketCandle> candles) {
        return new BacktestContext(1L, "TEST-EQ", "NSE", "FIVE_MINUTE", CUTOFF, candles, null,
                new BacktestAccountState(new BigDecimal("100000"), new BigDecimal("100000"), BigDecimal.ZERO));
    }

    private static MarketCandle candle(Instant time) {
        return new MarketCandle(1L, 1L, "NSE", "TEST-EQ", "token-1", UUID.randomUUID(), "FIVE_MINUTE", time,
                new BigDecimal("100"), new BigDecimal("101"), new BigDecimal("99"), new BigDecimal("100"),
                1000L, "TEST_FIXTURE", time);
    }
}
