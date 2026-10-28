package in.aitradingsystem.scanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.aitradingsystem.marketdata.MarketCandle;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ExtendedScannerStrategiesTest {

    @Test
    void openingRangeBreakoutIsDeterministicAndResearchOnly() {
        List<MarketCandle> bars = List.of(
                candle(1, "2026-10-28T03:45:00Z", "100", "101", "99.5", "100.5", 1000),
                candle(2, "2026-10-28T03:50:00Z", "100.5", "101.5", "100", "101", 1100),
                candle(3, "2026-10-28T03:55:00Z", "101", "101.4", "100.8", "101.2", 900),
                candle(4, "2026-10-28T04:00:00Z", "101.4", "103.5", "101.3", "103", 1500));
        ScanContext context = context(bars, "2026-10-28T04:01:00Z");
        OpeningRangeBreakoutStrategy strategy = new OpeningRangeBreakoutStrategy();

        ScanCandidate first = strategy.evaluate(context).orElseThrow();
        ScanCandidate second = strategy.evaluate(context).orElseThrow();

        assertEquals(first, second);
        assertEquals(OpeningRangeBreakoutStrategy.NAME, first.strategy());
        assertTrue(first.reasons().contains("RESEARCH_CANDIDATE_ONLY"));
        assertTrue(first.suggestedStop().compareTo(first.referencePrice()) < 0);
        assertTrue(first.suggestedTarget().compareTo(first.referencePrice()) > 0);
    }

    @Test
    void vwapMomentumRequiresPriceAndVolumeConfirmation() {
        List<MarketCandle> bars = new ArrayList<>();
        Instant start = Instant.parse("2026-10-28T03:45:00Z");
        for (int i = 0; i < 19; i++) {
            BigDecimal close = new BigDecimal("100").add(BigDecimal.valueOf(i).multiply(new BigDecimal("0.02")));
            bars.add(candle(i + 1, start.plusSeconds(i * 300L), close, close.add(new BigDecimal("0.20")),
                    close.subtract(new BigDecimal("0.20")), close, 1000));
        }
        bars.add(candle(20, start.plusSeconds(19 * 300L), new BigDecimal("102"), new BigDecimal("105.5"),
                new BigDecimal("101.8"), new BigDecimal("105"), 2500));
        ScanCandidate candidate = new VwapMomentumStrategy()
                .evaluate(context(List.copyOf(bars), start.plusSeconds(20 * 300L))).orElseThrow();

        assertEquals(VwapMomentumStrategy.NAME, candidate.strategy());
        assertTrue(candidate.reasons().contains("VOLUME_CONFIRMED"));
        assertTrue(candidate.reasons().contains("RESEARCH_CANDIDATE_ONLY"));
    }

    @Test
    void rsiReversalTriggersOnlyAfterRecoveryAboveThirty() {
        List<MarketCandle> bars = new ArrayList<>();
        Instant start = Instant.parse("2026-10-28T03:45:00Z");
        BigDecimal close = new BigDecimal("100");
        bars.add(candle(1, start, close, close.add(new BigDecimal("0.2")), close.subtract(new BigDecimal("0.2")), close, 1000));
        for (int i = 1; i <= 14; i++) {
            close = close.subtract(BigDecimal.ONE);
            bars.add(candle(i + 1, start.plusSeconds(i * 300L), close.add(new BigDecimal("0.2")),
                    close.add(new BigDecimal("0.3")), close.subtract(new BigDecimal("0.5")), close, 1000));
        }
        close = close.add(new BigDecimal("7"));
        bars.add(candle(16, start.plusSeconds(15 * 300L), close.subtract(new BigDecimal("1")),
                close.add(new BigDecimal("0.5")), close.subtract(new BigDecimal("0.4")), close, 1600));

        ScanCandidate candidate = new RsiReversalStrategy()
                .evaluate(context(List.copyOf(bars), start.plusSeconds(16 * 300L))).orElseThrow();

        assertEquals(RsiReversalStrategy.NAME, candidate.strategy());
        assertTrue(candidate.reasons().contains("RSI_RECOVERED_ABOVE_30"));
        assertTrue(candidate.reasons().contains("RESEARCH_CANDIDATE_ONLY"));
    }

    @Test
    void scannerEngineRanksByScoreThenStrategyNameDeterministically() {
        List<MarketCandle> bars = List.of(
                candle(1, "2026-10-28T03:45:00Z", "100", "101", "99", "100", 1000));
        ScanContext context = context(bars, "2026-10-28T03:46:00Z");
        ScannerEngine engine = new ScannerEngine(List.of(
                fixed("B_STRATEGY", 0.75),
                fixed("A_STRATEGY", 0.75),
                fixed("TOP_STRATEGY", 0.90)), Duration.ofMinutes(10));

        List<ScanCandidate> ranked = engine.scan(context);

        assertEquals(List.of("TOP_STRATEGY", "A_STRATEGY", "B_STRATEGY"),
                ranked.stream().map(ScanCandidate::strategy).toList());
    }

    private static ScannerStrategy fixed(String name, double score) {
        return new ScannerStrategy() {
            @Override public String name() { return name; }
            @Override public int minimumCandles() { return 1; }
            @Override public Optional<ScanCandidate> evaluate(ScanContext context) {
                MarketCandle latest = context.candles().getLast();
                return Optional.of(new ScanCandidate(context.instrumentId(), context.tradingSymbol(), context.exchange(),
                        context.interval(), name, score, latest.close(), latest.close().subtract(BigDecimal.ONE),
                        latest.close().add(BigDecimal.ONE), latest.candleTime(), List.of("RESEARCH_CANDIDATE_ONLY")));
            }
        };
    }

    private static ScanContext context(List<MarketCandle> bars, String evaluatedAt) {
        return context(bars, Instant.parse(evaluatedAt));
    }

    private static ScanContext context(List<MarketCandle> bars, Instant evaluatedAt) {
        return new ScanContext(1L, "RELIANCE-EQ", "NSE", "FIVE_MINUTE", evaluatedAt, bars);
    }

    private static MarketCandle candle(long id, String time, String open, String high, String low, String close, long volume) {
        return candle(id, Instant.parse(time), new BigDecimal(open), new BigDecimal(high), new BigDecimal(low), new BigDecimal(close), volume);
    }

    private static MarketCandle candle(long id, Instant time, BigDecimal open, BigDecimal high, BigDecimal low,
                                       BigDecimal close, long volume) {
        return new MarketCandle(id, 1L, "NSE", "RELIANCE-EQ", "2885", null, "FIVE_MINUTE",
                time, open, high, low, close, volume, "TEST_FIXTURE", time.plusSeconds(1));
    }
}
