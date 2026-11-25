package in.aitradingsystem.mind;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.aitradingsystem.marketdata.MarketCandle;
import in.aitradingsystem.scanner.OpeningRangeBreakoutStrategy;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MindOrbOutOfSampleEvaluatorTest {
    private final MindOrbOutOfSampleEvaluator evaluator =
            new MindOrbOutOfSampleEvaluator(new OpeningRangeBreakoutStrategy());
    private static final UUID CATALOGUE = UUID.randomUUID();

    private List<MarketCandle> session(boolean trigger) {
        List<MarketCandle> bars = new ArrayList<>();
        Instant open = Instant.parse("2026-11-25T03:45:00Z"); // 09:15 IST
        for (int i = 0; i < 76; i++) {
            Instant time = open.plusSeconds(i * 300L);
            boolean signal = trigger && i == 3;
            boolean target = trigger && i == 5;
            BigDecimal o = signal ? new BigDecimal("102") : new BigDecimal("100");
            BigDecimal h = i < 3 ? new BigDecimal("101")
                    : target ? new BigDecimal("108")
                    : signal ? new BigDecimal("104") : new BigDecimal("100.5");
            BigDecimal l = i < 3 ? new BigDecimal("99") : new BigDecimal("99.5");
            BigDecimal c = signal ? new BigDecimal("103")
                    : target ? new BigDecimal("106") : new BigDecimal("100");
            bars.add(new MarketCandle(i + 1L, 42L, "NSE", "INFY-EQ", "1234",
                    CATALOGUE, "FIVE_MINUTE", time, o, h, l, c,
                    signal ? 2000L : 1000L, "ANGEL_ONE_SMARTAPI", time.plusSeconds(30)));
        }
        return bars;
    }

    @Test
    void chargesExplicitCostsAndEntersAfterSignalBar() {
        var metrics = evaluator.evaluate(session(true), "FIVE_MINUTE");

        assertEquals(1, metrics.closedTrades());
        assertTrue(metrics.grossPnl().signum() > 0);
        assertTrue(metrics.feeCost().signum() > 0);
        assertTrue(metrics.slippageCost().signum() > 0);
        assertTrue(metrics.netPnl().compareTo(metrics.grossPnl()) < 0);
    }

    @Test
    void noSignalProducesZeroTradeMetrics() {
        var metrics = evaluator.evaluate(session(false), "FIVE_MINUTE");

        assertEquals(0, metrics.closedTrades());
        assertEquals(BigDecimal.ZERO.setScale(2), metrics.netPnl());
    }

    @Test
    void rejectsMismatchedIntervalAndUnorderedBars() {
        var bars = session(false);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> evaluator.evaluate(bars, "ONE_MINUTE"));
        var unordered = new ArrayList<>(bars);
        unordered.set(2, unordered.get(1));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> evaluator.evaluate(unordered, "FIVE_MINUTE"));
    }
}
