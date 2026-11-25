package in.aitradingsystem.backtest;

import in.aitradingsystem.marketdata.MarketCandle;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class BacktestMetricsCalculator {
    private static final MathContext MC = MathContext.DECIMAL64;
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final String ASSUMPTIONS = "{\"benchmark\":\"BUY_AND_HOLD_CLOSE_TO_CLOSE\",\"equitySampling\":\"TRADE_EXIT\",\"positionModel\":\"LONG_ONLY\",\"rMultipleBasis\":\"INITIAL_STOP_DISTANCE\"}";

    private BacktestMetricsCalculator() {}

    static Calculated calculate(List<MarketCandle> candles, List<BacktestTradeResult> trades, BigDecimal initialCapital) {
        if (candles == null || candles.isEmpty()) throw new IllegalArgumentException("candles are required");
        if (initialCapital == null || initialCapital.signum() <= 0) throw new IllegalArgumentException("initialCapital must be positive");
        List<BacktestTradeResult> safeTrades = trades == null ? List.of() : List.copyOf(trades);

        MarketCandle first = candles.getFirst();
        MarketCandle last = candles.getLast();
        BigDecimal benchmark = percentChange(first.close(), last.close(), 4);

        BigDecimal totalR = BigDecimal.ZERO;
        int rCount = 0;
        BigDecimal turnover = BigDecimal.ZERO;
        long exposureMillis = 0L;
        int consecutiveLosses = 0;
        int maxConsecutiveLosses = 0;

        for (BacktestTradeResult trade : safeTrades) {
            BigDecimal quantity = BigDecimal.valueOf(trade.quantity());
            BigDecimal initialRisk = trade.entryPrice().subtract(trade.stopLoss()).abs().multiply(quantity, MC);
            if (initialRisk.signum() > 0) {
                totalR = totalR.add(trade.netPnl().divide(initialRisk, 10, RoundingMode.HALF_UP), MC);
                rCount++;
            }
            turnover = turnover
                    .add(trade.entryPrice().multiply(quantity, MC), MC)
                    .add(trade.exitPrice().multiply(quantity, MC), MC);
            if (trade.entryAt() != null && trade.exitAt() != null && !trade.exitAt().isBefore(trade.entryAt())) {
                exposureMillis += Duration.between(trade.entryAt(), trade.exitAt()).toMillis();
            }
            if (trade.netPnl().signum() < 0) {
                consecutiveLosses++;
                maxConsecutiveLosses = Math.max(maxConsecutiveLosses, consecutiveLosses);
            } else {
                consecutiveLosses = 0;
            }
        }

        BigDecimal averageR = rCount == 0 ? BigDecimal.ZERO
                : totalR.divide(BigDecimal.valueOf(rCount), 6, RoundingMode.HALF_UP);
        BigDecimal expectancyR = averageR;
        long totalMillis = Math.max(1L, Duration.between(first.candleTime(), last.candleTime()).toMillis());
        BigDecimal exposure = BigDecimal.valueOf(exposureMillis)
                .multiply(HUNDRED)
                .divide(BigDecimal.valueOf(totalMillis), 4, RoundingMode.HALF_UP)
                .min(HUNDRED);

        BacktestRunAnalytics analytics = new BacktestRunAnalytics(
                null,
                "BACKTEST_V1",
                last.candleTime(),
                candles.size(),
                benchmark,
                expectancyR,
                averageR,
                exposure,
                turnover.setScale(2, RoundingMode.HALF_UP),
                maxConsecutiveLosses,
                ASSUMPTIONS);

        return new Calculated(analytics, equityPoints(candles, safeTrades, initialCapital));
    }

    private static BigDecimal percentChange(BigDecimal from, BigDecimal to, int scale) {
        if (from == null || to == null || from.signum() == 0) return BigDecimal.ZERO.setScale(scale);
        return to.subtract(from, MC).multiply(HUNDRED, MC).divide(from, scale, RoundingMode.HALF_UP);
    }

    private static List<BacktestEquityPoint> equityPoints(
            List<MarketCandle> candles, List<BacktestTradeResult> trades, BigDecimal initialCapital) {
        Map<Instant, BacktestEquityPoint> points = new LinkedHashMap<>();
        Instant firstTime = candles.getFirst().candleTime();
        points.put(firstTime, new BacktestEquityPoint(firstTime, money(initialCapital), money(initialCapital), BigDecimal.ZERO.setScale(2)));
        BigDecimal equity = initialCapital;
        for (BacktestTradeResult trade : trades) {
            if (trade.exitAt() == null) continue;
            equity = equity.add(trade.netPnl(), MC);
            BigDecimal rounded = money(equity);
            points.put(trade.exitAt(), new BacktestEquityPoint(trade.exitAt(), rounded, rounded, BigDecimal.ZERO.setScale(2)));
        }
        Instant lastTime = candles.getLast().candleTime();
        BigDecimal rounded = money(equity);
        points.put(lastTime, new BacktestEquityPoint(lastTime, rounded, rounded, BigDecimal.ZERO.setScale(2)));
        return new ArrayList<>(points.values());
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    record Calculated(BacktestRunAnalytics analytics, List<BacktestEquityPoint> equityPoints) {
        Calculated {
            equityPoints = List.copyOf(equityPoints);
        }
    }
}
