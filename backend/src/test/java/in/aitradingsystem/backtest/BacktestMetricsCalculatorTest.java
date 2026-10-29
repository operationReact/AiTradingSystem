package in.aitradingsystem.backtest;

import static org.assertj.core.api.Assertions.assertThat;

import in.aitradingsystem.marketdata.MarketCandle;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BacktestMetricsCalculatorTest {

    @Test
    void calculatesDeterministicProvenanceMetricsAndEquityCurve() {
        Instant start = Instant.parse("2026-01-02T03:45:00Z");
        List<MarketCandle> candles = List.of(
                candle(start, "100"),
                candle(start.plusSeconds(300), "105"),
                candle(start.plusSeconds(600), "110"));
        BacktestTradeResult win = new BacktestTradeResult(
                1, start, start.plusSeconds(300), bd("100"), bd("105"), 10,
                bd("98"), bd("106"), bd("50"), bd("1"), bd("1"), bd("48"), "TARGET");
        BacktestTradeResult loss = new BacktestTradeResult(
                2, start.plusSeconds(300), start.plusSeconds(600), bd("105"), bd("103"), 10,
                bd("103"), bd("110"), bd("-20"), bd("1"), bd("1"), bd("-22"), "STOP");

        BacktestMetricsCalculator.Calculated calculated = BacktestMetricsCalculator.calculate(
                candles, List.of(win, loss), bd("100000"));

        BacktestRunAnalytics metrics = calculated.analytics();
        assertThat(metrics.methodologyVersion()).isEqualTo("BACKTEST_V1");
        assertThat(metrics.dataCutoffAt()).isEqualTo(start.plusSeconds(600));
        assertThat(metrics.sourceCandleCount()).isEqualTo(3);
        assertThat(metrics.benchmarkReturnPercent()).isEqualByComparingTo("10.0000");
        assertThat(metrics.turnover()).isEqualByComparingTo("4130.00");
        assertThat(metrics.maxConsecutiveLosses()).isEqualTo(1);
        assertThat(metrics.assumptionsJson()).contains("BUY_AND_HOLD_CLOSE_TO_CLOSE");
        assertThat(calculated.equityPoints()).hasSize(3);
        assertThat(calculated.equityPoints().getLast().equity()).isEqualByComparingTo("100026.00");
    }

    @Test
    void noTradesProducesZeroTradeMetricsAndBoundaryEquityPoints() {
        Instant start = Instant.parse("2026-01-02T03:45:00Z");
        List<MarketCandle> candles = List.of(candle(start, "100"), candle(start.plusSeconds(300), "100"));

        BacktestMetricsCalculator.Calculated calculated = BacktestMetricsCalculator.calculate(
                candles, List.of(), bd("50000"));

        assertThat(calculated.analytics().expectancyR()).isEqualByComparingTo("0");
        assertThat(calculated.analytics().averageR()).isEqualByComparingTo("0");
        assertThat(calculated.analytics().exposurePercent()).isEqualByComparingTo("0.0000");
        assertThat(calculated.analytics().turnover()).isEqualByComparingTo("0.00");
        assertThat(calculated.equityPoints()).hasSize(2);
    }

    private static MarketCandle candle(Instant time, String close) {
        BigDecimal value = bd(close);
        return new MarketCandle(1L, 7L, "NSE", "TEST", "7", UUID.randomUUID(), "FIVE_MINUTE",
                time, value, value, value, value, 100L, "TEST", time);
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
