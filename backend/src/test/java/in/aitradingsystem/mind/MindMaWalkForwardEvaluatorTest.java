package in.aitradingsystem.mind;

import static org.junit.jupiter.api.Assertions.*;

import in.aitradingsystem.marketdata.MarketCandle;
import in.aitradingsystem.scanner.MovingAverageReclaimStrategy;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MindMaWalkForwardEvaluatorTest {
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final UUID VERSION = UUID.randomUUID();
    private final MindMaWalkForwardEvaluator simulator =
        new MindMaWalkForwardEvaluator(new MovingAverageReclaimStrategy());

    private List<MarketCandle> candles(int days) {
        List<MarketCandle> result = new ArrayList<>();
        for (int day = 0; day < days; day++) {
            var start = LocalDate.of(2026, 9, 1).plusDays(day)
                .atTime(9, 15).atZone(IST).toInstant();
            for (int i = 0; i < 75; i++) {
                BigDecimal open = new BigDecimal(
                    i == 20 ? "96" : i == 21 ? "110" : "100");
                BigDecimal high = new BigDecimal(
                    i == 20 ? "111" : i == 21 ? "112" : "101");
                BigDecimal low = new BigDecimal(
                    i == 19 ? "94" : i == 20 ? "95" : i == 21 ? "109" : "99");
                BigDecimal close = new BigDecimal(
                    i == 19 ? "95" : i == 20 ? "110" : i == 21 ? "111" : "100");
                result.add(new MarketCandle(result.size() + 1L, 42, "NSE",
                    "INFY-EQ", "1234", VERSION, "FIVE_MINUTE",
                    start.plusSeconds(i * 300L), open, high, low, close, 1000,
                    "ANGEL_ONE_SMARTAPI", start.plusSeconds(100)));
            }
        }
        return result;
    }

    @Test
    void tradesEnterOnlyAfterSignalCloseAndIncludeExplicitCosts() {
        var metrics = simulator.evaluate(candles(3));
        assertTrue(metrics.closedTrades() > 0, "At least one 21-bar MA reclaim signal");
        assertTrue(metrics.feeCost().signum() > 0);
        assertTrue(metrics.slippageCost().signum() > 0);
        assertEquals(0, metrics.grossPnl()
            .subtract(metrics.feeCost())
            .subtract(metrics.slippageCost())
            .subtract(metrics.netPnl()).abs().compareTo(new BigDecimal("0.03")));
        assertEquals(0, metrics.returnPercent()
            .subtract(metrics.netPnl().multiply(new BigDecimal("100"))
                .divide(new BigDecimal("100000"), 4, java.math.RoundingMode.HALF_UP))
            .compareTo(BigDecimal.ZERO));
        assertTrue(metrics.maxDrawdownPercent().signum() >= 0);
        assertEquals("ILLUSTRATIVE_COMPOSITE_COSTS_NOT_ACTUAL_BROKER_OR_STATUTORY_CHARGES",
            simulator.assumptions().label());
    }

    @Test
    void ambiguousSameBarStopAndTargetUsesStopFirst() {
        var bars = candles(1);
        var entry = bars.get(21);
        bars.set(21, new MarketCandle(entry.id(), entry.instrumentId(),
            entry.exchange(), entry.tradingSymbol(), entry.symbolToken(),
            entry.catalogueVersion(), entry.interval(), entry.candleTime(),
            new BigDecimal("110"), new BigDecimal("180"),
            new BigDecimal("90"), new BigDecimal("120"),
            entry.volume(), entry.source(), entry.fetchedAt()));
        var result = simulator.evaluate(bars);
        assertTrue(result.closedTrades() >= 1);
        assertTrue(result.netPnl().signum() < 0,
            "When both levels are touched the conservative stop must win");
    }

    @Test
    void missingNextCandleNeverFillsAtSignalCloseOrThroughAnOvernightGap() {
        var bars = candles(1);
        bars.remove(21); // 5-minute next-open slot disappeared
        var result = simulator.evaluate(bars);
        assertEquals(0, result.closedTrades());
        assertEquals(0, result.netPnl().signum());
    }

    @Test
    void noSignalOrInsufficientBarsMeansZeroTradesNotInventedProfit() {
        var bars = candles(1);
        assertEquals(0, simulator.evaluate(bars.subList(0, 21)).closedTrades());
        assertEquals(0, simulator.evaluate(List.of()).closedTrades());
        assertEquals(0, simulator.evaluate(bars.subList(0, 19)).netPnl().signum());
    }
}
