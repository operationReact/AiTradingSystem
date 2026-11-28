package in.aitradingsystem.scanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.aitradingsystem.backtest.BacktestAccountState;
import in.aitradingsystem.backtest.BacktestContext;
import in.aitradingsystem.backtest.BacktestSignal;
import in.aitradingsystem.backtest.ScannerBacktestAdapter;
import in.aitradingsystem.marketdata.HistoricalCandleInterval;
import in.aitradingsystem.marketdata.MarketCandle;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Deterministic unit coverage for the daily swing strategy family. Each strategy is exercised with a
 * hand-built daily series that satisfies its exact rule, plus the shared invariants every swing
 * strategy must honour: daily-only interval, long-only tradable levels, determinism, and failing
 * closed on a flat series.
 */
class SwingStrategiesTest {
    private static final Instant DAY0 = Instant.parse("2025-01-01T10:00:00Z");
    private static final String DAILY = HistoricalCandleInterval.ONE_DAY.name();

    // ---- SWING_DONCHIAN_BREAKOUT_V1 ----
    @Test
    void donchianBreakoutFiresOnNewTwentyDayHighInUptrend() {
        List<MarketCandle> bars = risingSeries(55, 100.0, 0.6, 0.002, 1000);
        assertCandidate(new SwingDonchianBreakoutStrategy(), bars, SwingDonchianBreakoutStrategy.NAME);
    }

    // ---- SWING_EMA_PULLBACK_V1 ----
    @Test
    void emaPullbackFiresWhenPriceReclaimsTwentyEmaAfterDip() {
        List<MarketCandle> bars = risingSeries(60, 100.0, 0.6, 0.004, 1000);
        int last = bars.size() - 1;
        double ema20Prev = SwingIndicators.ema(bars, last - 1, 20);
        // Make the prior bar dip to the 20-EMA, then the latest bar reclaim and close up.
        bars.set(last - 1, withLow(bars.get(last - 1), ema20Prev - 0.05));
        MarketCandle p = bars.get(last - 1);
        bars.set(last, candle(last + 1, DAY0.plus(Duration.ofDays(last)),
                p.close().doubleValue(), p.close().doubleValue() + 2.0,
                p.close().doubleValue() - 0.1, p.close().doubleValue() + 1.5, 1000));
        assertCandidate(new SwingEmaPullbackStrategy(), bars, SwingEmaPullbackStrategy.NAME);
    }

    // ---- SWING_RSI2_MEANREV_V1 ----
    @Test
    void rsi2MeanReversionFiresWhenOversoldThenTurnsUpAboveTwoHundredSma() {
        List<MarketCandle> bars = risingSeries(205, 100.0, 0.5, 0.004, 1000);
        int last = bars.size() - 1;
        // Three sharp down closes make RSI(2) collapse below 10 at bar last-1, then turn up.
        double base = bars.get(last - 4).close().doubleValue();
        for (int k = 3; k >= 1; k--) {
            int idx = last - k;
            double c = base - (4 - k) * 3.0; // successively lower closes
            bars.set(idx, candle(idx + 1, DAY0.plus(Duration.ofDays(idx)), c + 1.0, c + 1.2, c - 1.2, c, 1000));
        }
        double prevClose = bars.get(last - 1).close().doubleValue();
        bars.set(last, candle(last + 1, DAY0.plus(Duration.ofDays(last)), prevClose, prevClose + 2.0,
                prevClose - 0.2, prevClose + 1.5, 1000));
        assertCandidate(new SwingRsi2MeanReversionStrategy(), bars, SwingRsi2MeanReversionStrategy.NAME);
    }

    // ---- SWING_MACD_CROSS_V1 ----
    @Test
    void macdCrossFiresOnBullishReCrossAboveZero() {
        // Strong uptrend (MACD well positive) with a shallow 6-bar dip then an up bar: the signal line
        // re-crosses while the MACD line stays above zero.
        List<MarketCandle> bars = new ArrayList<>();
        double c = 100.0;
        int i = 0;
        for (int j = 0; j < 40; j++, i++) { c += 1.0; bars.add(dailyClose(i, c)); } // uptrend: MACD line well positive
        for (int j = 0; j < 45; j++, i++) { bars.add(dailyClose(i, c)); } // long flat: line and signal both decay toward zero
        c += 3.0; bars.add(dailyClose(i, c)); // up bar: line re-crosses above signal while still positive
        assertCandidate(new SwingMacdCrossStrategy(), bars, SwingMacdCrossStrategy.NAME);
    }

    // ---- SWING_BOLLINGER_REVERSION_V1 ----
    @Test
    void bollingerReversionFiresWhenCloseReentersLowerBand() {
        List<MarketCandle> bars = risingSeries(101, 100.0, 0.5, 0.004, 1000);
        int last = bars.size() - 1;
        // A single deep outlier close at bar last-1 falls below its own lower band (an extreme enough
        // one-bar drop is below mid - 2*sd). The latest bar bounces back above the band and the prior
        // close while staying above the 100-SMA regime level.
        double normal = bars.get(last - 1).close().doubleValue();
        double pClose = normal - 15.0;
        bars.set(last - 1, candle(last, DAY0.plus(Duration.ofDays(last - 1)), normal - 14.0, normal - 13.0,
                normal - 16.0, pClose, 1000));
        double close = normal - 3.5; // above prior close and above the 100-SMA, re-entering the band
        bars.set(last, candle(last + 1, DAY0.plus(Duration.ofDays(last)), pClose, close + 1.0,
                pClose - 0.5, close, 1000));
        assertCandidate(new SwingBollingerReversionStrategy(), bars, SwingBollingerReversionStrategy.NAME);
    }

    // ---- SWING_NEW_HIGH_VOLUME_V1 ----
    @Test
    void newHighVolumeFiresOnSixtyDayHighWithVolumeExpansion() {
        List<MarketCandle> bars = risingSeries(62, 100.0, 0.6, 0.002, 1000);
        int last = bars.size() - 1;
        bars.set(last, withVolume(bars.get(last), 5000));
        assertCandidate(new SwingNewHighVolumeStrategy(), bars, SwingNewHighVolumeStrategy.NAME);
    }

    // ---- SWING_GOLDEN_REGIME_PULLBACK_V1 ----
    @Test
    void goldenRegimePullbackFiresOnFiftySmaReclaim() {
        List<MarketCandle> bars = risingSeries(201, 100.0, 0.5, 0.004, 1000);
        int last = bars.size() - 1;
        double sma50 = SwingIndicators.sma(bars, last - 1, 50); // approx level of the 50-SMA near the end
        // Build a pullback bar that dips to the 50-SMA and closes just above it with a bullish body.
        double close = sma50 + 1.0;
        bars.set(last, candle(last + 1, DAY0.plus(Duration.ofDays(last)), sma50 - 0.3, close + 0.5,
                sma50 - 0.8, close, 1000));
        assertCandidate(new SwingGoldenRegimePullbackStrategy(), bars, SwingGoldenRegimePullbackStrategy.NAME);
    }

    // ---- SWING_ATR_TREND_V1 ----
    @Test
    void atrTrendFiresOnRisingTrendContinuation() {
        List<MarketCandle> bars = risingSeries(75, 100.0, 0.6, 0.002, 1000);
        assertCandidate(new SwingAtrTrendStrategy(), bars, SwingAtrTrendStrategy.NAME);
    }

    // ---- shared invariants ----
    @Test
    void allSwingStrategiesAreDailyOnlyAndFailClosedOnFlatSeries() {
        for (ScannerStrategy strategy : swingStrategies()) {
            // Flat series -> no candidate.
            List<MarketCandle> flat = flatSeries(strategy.minimumCandles() + 5, 100.0);
            assertTrue(strategy.evaluate(dailyContext(flat)).isEmpty(), "flat/" + strategy.name());

            // Intraday interval -> inert even on an otherwise-triggering series.
            List<MarketCandle> rising = risingSeries(strategy.minimumCandles() + 20, 100.0, 0.5, 0.01, 2000);
            ScanContext intraday = new ScanContext(1L, "RELIANCE-EQ", "NSE",
                    HistoricalCandleInterval.FIVE_MINUTE.name(), lastTimePlus(rising), rising);
            assertTrue(strategy.evaluate(intraday).isEmpty(), "intraday/" + strategy.name());

            // Descriptor contract.
            StrategyDescriptor d = strategy.descriptor();
            assertEquals(strategy.name(), d.code());
            assertEquals(TradingStyle.SWING, d.tradingStyle());
            assertEquals(List.of(HistoricalCandleInterval.ONE_DAY), List.copyOf(d.supportedIntervals()));
            assertTrue(d.longCapable());
            assertFalse(d.shortCapable());
        }
    }

    @Test
    void swingStrategyFlowsThroughBacktestSpiWithoutLookahead() {
        List<MarketCandle> bars = risingSeries(60, 100.0, 0.6, 0.002, 1000);
        MarketCandle latest = bars.get(bars.size() - 1);
        BacktestContext context = new BacktestContext(1L, "RELIANCE-EQ", "NSE", DAILY, latest.candleTime(), bars, null,
                new BacktestAccountState(new BigDecimal("30000"), new BigDecimal("30000"), BigDecimal.ZERO));
        ScannerBacktestAdapter adapter = new ScannerBacktestAdapter(new SwingDonchianBreakoutStrategy());

        assertEquals(SwingDonchianBreakoutStrategy.NAME, adapter.code());
        BacktestSignal signal = adapter.evaluate(context);
        assertTrue(signal.triggered(), "swing strategy should trigger through the backtest SPI");
        assertTrue(signal.stopPrice().compareTo(signal.referencePrice()) < 0);
        assertTrue(signal.targetPrice().compareTo(signal.referencePrice()) > 0);
    }

    @Test
    void swingStrategiesRegisterWithoutDuplicateCodes() {
        StrategyRegistry registry = new StrategyRegistry(new ArrayList<>(swingStrategies()));
        assertEquals(swingStrategies().size(), registry.descriptors().size());
    }

    // ---- helpers ----
    private static List<ScannerStrategy> swingStrategies() {
        return List.of(
                new SwingDonchianBreakoutStrategy(),
                new SwingEmaPullbackStrategy(),
                new SwingRsi2MeanReversionStrategy(),
                new SwingMacdCrossStrategy(),
                new SwingBollingerReversionStrategy(),
                new SwingNewHighVolumeStrategy(),
                new SwingGoldenRegimePullbackStrategy(),
                new SwingAtrTrendStrategy());
    }

    private static void assertCandidate(ScannerStrategy strategy, List<MarketCandle> bars, String code) {
        ScanContext ctx = dailyContext(bars);
        ScanCandidate first = strategy.evaluate(ctx).orElseThrow(() -> new AssertionError("no candidate for " + code));
        ScanCandidate second = strategy.evaluate(ctx).orElseThrow();
        assertEquals(first, second, "determinism/" + code);
        assertEquals(code, first.strategy());
        assertTrue(first.reasons().contains(SwingSupport.RESEARCH_ONLY), "research-only/" + code);
        assertTrue(first.suggestedStop().signum() > 0, "stop>0/" + code);
        assertTrue(first.suggestedStop().compareTo(first.referencePrice()) < 0, "stop<ref/" + code);
        assertTrue(first.suggestedTarget().compareTo(first.referencePrice()) > 0, "target>ref/" + code);
        assertTrue(first.score() >= 0.0 && first.score() <= 1.0, "score/" + code);
    }

    private static ScanContext dailyContext(List<MarketCandle> bars) {
        return new ScanContext(1L, "RELIANCE-EQ", "NSE", DAILY, lastTimePlus(bars), bars);
    }

    private static Instant lastTimePlus(List<MarketCandle> bars) {
        return bars.get(bars.size() - 1).candleTime().plus(Duration.ofHours(6));
    }

    private static List<MarketCandle> risingSeries(int n, double start, double step, double rangePct, long vol) {
        List<MarketCandle> bars = new ArrayList<>();
        double close = start;
        for (int i = 0; i < n; i++) {
            double open = i == 0 ? close - step : bars.get(i - 1).close().doubleValue();
            double high = close * (1 + rangePct);
            double low = Math.min(open, close) * (1 - rangePct);
            bars.add(candle(i + 1, DAY0.plus(Duration.ofDays(i)), open, high, low, close, vol));
            close += step;
        }
        return bars;
    }

    private static List<MarketCandle> flatSeries(int n, double price) {
        List<MarketCandle> bars = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            bars.add(candle(i + 1, DAY0.plus(Duration.ofDays(i)), price, price + 0.1, price - 0.1, price, 1000));
        }
        return bars;
    }

    private static MarketCandle dailyClose(int i, double close) {
        double high = close + 1.0;
        double low = close - 1.0;
        double open = close - 0.3;
        return candle(i + 1, DAY0.plus(Duration.ofDays(i)), open, high, low, close, 1000);
    }

    private static MarketCandle withLow(MarketCandle c, double low) {
        return new MarketCandle(c.id(), c.instrumentId(), c.exchange(), c.tradingSymbol(), c.symbolToken(),
                c.catalogueVersion(), c.interval(), c.candleTime(), c.open(), c.high(), bd(low), c.close(),
                c.volume(), c.source(), c.fetchedAt());
    }

    private static MarketCandle withVolume(MarketCandle c, long volume) {
        return new MarketCandle(c.id(), c.instrumentId(), c.exchange(), c.tradingSymbol(), c.symbolToken(),
                c.catalogueVersion(), c.interval(), c.candleTime(), c.open(), c.high(), c.low(), c.close(),
                volume, c.source(), c.fetchedAt());
    }

    private static MarketCandle candle(long id, Instant time, double open, double high, double low,
            double close, long volume) {
        return new MarketCandle(id, 1L, "NSE", "RELIANCE-EQ", "2885", UUID.fromString("00000000-0000-0000-0000-000000000001"),
                DAILY, time, bd(open), bd(high), bd(low), bd(close), volume, "TEST_FIXTURE", time);
    }

    private static BigDecimal bd(double v) {
        return new BigDecimal(Double.toString(v));
    }
}
