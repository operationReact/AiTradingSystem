package in.aitradingsystem.backtest;

import in.aitradingsystem.marketdata.MarketCandle;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Offline, deterministic, long-only SMA/volume backtest.
 *
 * Signals use completed candles and are executed at the following candle open.
 * When one candle touches both stop and target, stop-loss is assumed first.
 * This engine never calls a broker, retrieves live prices, or creates paper orders.
 */
@Component
public final class SmaBacktestEngine {
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal ZERO_MONEY = new BigDecimal("0.00");
    private static final BigDecimal ZERO_PRICE = new BigDecimal("0.000000");
    private static final int MAX_QUANTITY = 10_000;
    public static final String STRATEGY_VERSION = "SMA_TREND_VOLUME_V1";

    public record Config(
        BigDecimal initialCapital,
        int fastPeriod,
        int slowPeriod,
        BigDecimal riskPerTradePercent,
        BigDecimal feeRate,
        BigDecimal slippageRate,
        BigDecimal stopBufferPercent,
        BigDecimal targetRiskReward
    ) {
        public Config {
            if (initialCapital == null || initialCapital.signum() <= 0) {
                throw new IllegalArgumentException("initialCapital must be positive");
            }
            if (fastPeriod < 2 || slowPeriod <= fastPeriod || slowPeriod > 500) {
                throw new IllegalArgumentException("Periods must satisfy 2 <= fastPeriod < slowPeriod <= 500");
            }
            if (riskPerTradePercent == null || riskPerTradePercent.signum() <= 0
                    || riskPerTradePercent.compareTo(new BigDecimal("5")) > 0) {
                throw new IllegalArgumentException("riskPerTradePercent must be greater than 0 and at most 5");
            }
            if (feeRate == null || feeRate.signum() < 0 || feeRate.compareTo(new BigDecimal("0.01")) > 0) {
                throw new IllegalArgumentException("feeRate must be between 0 and 0.01");
            }
            if (slippageRate == null || slippageRate.signum() < 0
                    || slippageRate.compareTo(new BigDecimal("0.05")) > 0) {
                throw new IllegalArgumentException("slippageRate must be between 0 and 0.05");
            }
            if (stopBufferPercent == null || stopBufferPercent.signum() <= 0
                    || stopBufferPercent.compareTo(new BigDecimal("10")) > 0) {
                throw new IllegalArgumentException("stopBufferPercent must be greater than 0 and at most 10");
            }
            if (targetRiskReward == null || targetRiskReward.compareTo(BigDecimal.ONE) < 0
                    || targetRiskReward.compareTo(new BigDecimal("5")) > 0) {
                throw new IllegalArgumentException("targetRiskReward must be between 1 and 5");
            }
        }
    }

    private record Position(
        int tradeNumber,
        Instant entryAt,
        BigDecimal entryPrice,
        int quantity,
        BigDecimal stopLoss,
        BigDecimal targetPrice,
        BigDecimal entryFee
    ) {}

    private record Exit(BigDecimal rawPrice, String reason) {}

    public BacktestEngineResult run(List<MarketCandle> candles, Config config) {
        return run(candles, config, null);
    }

    /**
     * Runs on chronological candles while restricting new entries to the evaluation window.
     * Earlier candles are available only for indicator warm-up; no position is carried into
     * the evaluation window, preventing training-period trades from contaminating holdout metrics.
     */
    public BacktestEngineResult run(List<MarketCandle> candles, Config config, Instant evaluationStartInclusive) {
        if (candles == null || config == null || candles.size() < config.slowPeriod() + 2) {
            throw new IllegalArgumentException("Insufficient candles: at least slowPeriod + 2 are required");
        }
        if (evaluationStartInclusive != null
                && (candles.stream().noneMatch(c -> c != null && c.candleTime() != null
                        && !c.candleTime().isBefore(evaluationStartInclusive))
                    || candles.stream().noneMatch(c -> c != null && c.candleTime() != null
                        && c.candleTime().isBefore(evaluationStartInclusive)))) {
            throw new IllegalArgumentException("Evaluation boundary must leave both warm-up and evaluation candles");
        }
        validateChronology(candles);
        int evaluatedCandles = evaluationStartInclusive == null ? candles.size()
            : (int) candles.stream().filter(c -> !c.candleTime().isBefore(evaluationStartInclusive)).count();

        BigDecimal cash = money(config.initialCapital());
        BigDecimal grossPnl = ZERO_MONEY;
        BigDecimal totalFees = ZERO_MONEY;
        BigDecimal totalWins = ZERO_MONEY;
        BigDecimal totalLosses = ZERO_MONEY;
        BigDecimal peakEquity = cash;
        BigDecimal maxDrawdown = ZERO_MONEY;
        List<BacktestTradeResult> trades = new ArrayList<>();
        Position position = null;

        // A signal at candle i-1 is executed at candle i open; no same-candle lookahead.
        for (int i = config.slowPeriod() + 1; i < candles.size(); i++) {
            MarketCandle candle = candles.get(i);
            boolean exitedThisCandle = false;

            if (position != null) {
                Exit exit = exitForExistingPosition(position, candle);
                if (exit != null) {
                    CloseResult closed = close(position, candle.candleTime(), exit.rawPrice(), exit.reason(),
                        cash, config);
                    cash = closed.cashAfter();
                    BacktestTradeResult trade = closed.trade();
                    trades.add(trade);
                    grossPnl = grossPnl.add(trade.grossPnl());
                    totalFees = totalFees.add(trade.exitFee());
                    if (trade.netPnl().signum() > 0) totalWins = totalWins.add(trade.netPnl());
                    else if (trade.netPnl().signum() < 0) totalLosses = totalLosses.add(trade.netPnl().abs());
                    position = null;
                    exitedThisCandle = true;
                }
            }

            boolean inEvaluationWindow = evaluationStartInclusive == null
                || !candle.candleTime().isBefore(evaluationStartInclusive);
            if (position == null && !exitedThisCandle && inEvaluationWindow
                    && isBuySignal(candles, i - 1, config)) {
                MarketCandle signalCandle = candles.get(i - 1);
                position = openAtNextBar(signalCandle, candle, trades.size() + 1, cash, config);
                if (position != null) {
                    BigDecimal entryCost = position.entryPrice().multiply(BigDecimal.valueOf(position.quantity()));
                    cash = money(cash.subtract(entryCost).subtract(position.entryFee()));

                    // The entry bar can hit a stop/target after its open. If both hit, stop wins.
                    Exit sameBarExit = exitAfterEntry(position, candle);
                    if (sameBarExit != null) {
                        CloseResult closed = close(position, candle.candleTime(), sameBarExit.rawPrice(),
                            sameBarExit.reason(), cash, config);
                        cash = closed.cashAfter();
                        BacktestTradeResult trade = closed.trade();
                        trades.add(trade);
                        grossPnl = grossPnl.add(trade.grossPnl());
                        totalFees = totalFees.add(trade.entryFee()).add(trade.exitFee());
                        if (trade.netPnl().signum() > 0) totalWins = totalWins.add(trade.netPnl());
                        else if (trade.netPnl().signum() < 0) totalLosses = totalLosses.add(trade.netPnl().abs());
                        position = null;
                    } else {
                        totalFees = totalFees.add(position.entryFee());
                    }
                }
            }

            BigDecimal equity = position == null ? cash
                : cash.add(candle.close().multiply(BigDecimal.valueOf(position.quantity())));
            if (equity.compareTo(peakEquity) > 0) peakEquity = equity;
            BigDecimal drawdown = peakEquity.signum() == 0 ? ZERO_MONEY
                : peakEquity.subtract(equity).multiply(HUNDRED).divide(peakEquity, 6, RoundingMode.HALF_UP);
            if (drawdown.compareTo(maxDrawdown) > 0) maxDrawdown = drawdown;
        }

        if (position != null) {
            MarketCandle last = candles.getLast();
            CloseResult closed = close(position, last.candleTime(), last.close(), "END_OF_PERIOD", cash, config);
            cash = closed.cashAfter();
            BacktestTradeResult trade = closed.trade();
            trades.add(trade);
            grossPnl = grossPnl.add(trade.grossPnl());
            totalFees = totalFees.add(trade.exitFee());
            if (trade.netPnl().signum() > 0) totalWins = totalWins.add(trade.netPnl());
            else if (trade.netPnl().signum() < 0) totalLosses = totalLosses.add(trade.netPnl().abs());
            BigDecimal finalDrawdown = peakEquity.signum() == 0 ? ZERO_MONEY
                : peakEquity.subtract(cash).max(BigDecimal.ZERO).multiply(HUNDRED)
                    .divide(peakEquity, 6, RoundingMode.HALF_UP);
            if (finalDrawdown.compareTo(maxDrawdown) > 0) maxDrawdown = finalDrawdown;
        }

        int tradeCount = trades.size();
        int wins = (int) trades.stream().filter(t -> t.netPnl().signum() > 0).count();
        int losses = (int) trades.stream().filter(t -> t.netPnl().signum() < 0).count();
        BigDecimal winRate = tradeCount == 0 ? ZERO_MONEY
            : BigDecimal.valueOf(wins).multiply(HUNDRED).divide(BigDecimal.valueOf(tradeCount), 4, RoundingMode.HALF_UP);
        BigDecimal netPnl = money(cash.subtract(config.initialCapital()));
        BigDecimal totalReturn = config.initialCapital().signum() == 0 ? ZERO_MONEY
            : netPnl.multiply(HUNDRED).divide(config.initialCapital(), 4, RoundingMode.HALF_UP);
        BigDecimal profitFactor = totalLosses.signum() == 0
            ? null : totalWins.divide(totalLosses, 4, RoundingMode.HALF_UP);

        return new BacktestEngineResult(
            evaluatedCandles, tradeCount, wins, losses, money(winRate), money(grossPnl), money(totalFees),
            netPnl, money(cash), totalReturn, money(maxDrawdown), profitFactor, List.copyOf(trades));
    }

    private Position openAtNextBar(MarketCandle signalCandle, MarketCandle entryCandle, int tradeNumber,
                                   BigDecimal cash, Config config) {
        BigDecimal entryPrice = price(entryCandle.open().multiply(BigDecimal.ONE.add(config.slippageRate())));
        BigDecimal stop = price(signalCandle.close()
            .multiply(BigDecimal.ONE.subtract(config.stopBufferPercent().divide(HUNDRED, 8, RoundingMode.HALF_UP))));
        if (stop.signum() <= 0 || stop.compareTo(entryPrice) >= 0) return null;

        BigDecimal riskPerShare = entryPrice.subtract(stop);
        BigDecimal riskBudget = cash.multiply(config.riskPerTradePercent())
            .divide(HUNDRED, 8, RoundingMode.HALF_UP);
        int byRisk = riskBudget.divide(riskPerShare, 0, RoundingMode.DOWN).min(BigDecimal.valueOf(MAX_QUANTITY)).intValue();
        BigDecimal costPerShare = entryPrice.multiply(BigDecimal.ONE.add(config.feeRate()));
        int byCash = cash.divide(costPerShare, 0, RoundingMode.DOWN).min(BigDecimal.valueOf(MAX_QUANTITY)).intValue();
        int quantity = Math.min(byRisk, byCash);
        if (quantity < 1) return null;

        BigDecimal entryFee = money(entryPrice.multiply(BigDecimal.valueOf(quantity)).multiply(config.feeRate()));
        BigDecimal target = price(entryPrice.add(riskPerShare.multiply(config.targetRiskReward())));
        if (target.compareTo(entryPrice) <= 0) return null;
        return new Position(tradeNumber, entryCandle.candleTime(), entryPrice, quantity, stop, target, entryFee);
    }

    private boolean isBuySignal(List<MarketCandle> candles, int index, Config config) {
        if (index < config.slowPeriod()) return false;
        BigDecimal fast = averageClose(candles, index - config.fastPeriod() + 1, index + 1);
        BigDecimal slow = averageClose(candles, index - config.slowPeriod() + 1, index + 1);
        BigDecimal averageVolume = averageVolume(candles, index - config.slowPeriod(), index);
        MarketCandle current = candles.get(index);
        return fast.compareTo(slow) > 0
            && current.close().compareTo(slow) > 0
            && averageVolume.signum() > 0
            && BigDecimal.valueOf(current.volume()).compareTo(averageVolume) >= 0;
    }

    private Exit exitForExistingPosition(Position position, MarketCandle candle) {
        if (candle.open().compareTo(position.stopLoss()) <= 0) return new Exit(candle.open(), "STOP_LOSS_GAP");
        if (candle.open().compareTo(position.targetPrice()) >= 0) return new Exit(candle.open(), "TAKE_PROFIT_GAP");
        return exitAfterEntry(position, candle);
    }

    private Exit exitAfterEntry(Position position, MarketCandle candle) {
        boolean stopTouched = candle.low().compareTo(position.stopLoss()) <= 0;
        boolean targetTouched = candle.high().compareTo(position.targetPrice()) >= 0;
        if (stopTouched) return new Exit(position.stopLoss(), "STOP_LOSS");
        if (targetTouched) return new Exit(position.targetPrice(), "TAKE_PROFIT");
        return null;
    }

    private CloseResult close(Position position, Instant exitAt, BigDecimal rawExitPrice, String reason,
                              BigDecimal cashBeforeExit, Config config) {
        BigDecimal exitPrice = price(rawExitPrice.multiply(BigDecimal.ONE.subtract(config.slippageRate())));
        BigDecimal quantity = BigDecimal.valueOf(position.quantity());
        BigDecimal exitFee = money(exitPrice.multiply(quantity).multiply(config.feeRate()));
        BigDecimal gross = money(exitPrice.subtract(position.entryPrice()).multiply(quantity));
        BigDecimal net = money(gross.subtract(position.entryFee()).subtract(exitFee));
        BigDecimal cashAfter = money(cashBeforeExit.add(exitPrice.multiply(quantity)).subtract(exitFee));
        BacktestTradeResult trade = new BacktestTradeResult(
            position.tradeNumber(), position.entryAt(), exitAt, position.entryPrice(), exitPrice,
            position.quantity(), position.stopLoss(), position.targetPrice(), gross, position.entryFee(),
            exitFee, net, reason);
        return new CloseResult(cashAfter, trade);
    }

    private record CloseResult(BigDecimal cashAfter, BacktestTradeResult trade) {}

    private BigDecimal averageClose(List<MarketCandle> candles, int startInclusive, int endExclusive) {
        BigDecimal total = BigDecimal.ZERO;
        for (int i = startInclusive; i < endExclusive; i++) total = total.add(candles.get(i).close());
        return total.divide(BigDecimal.valueOf(endExclusive - startInclusive), 8, RoundingMode.HALF_UP);
    }

    private BigDecimal averageVolume(List<MarketCandle> candles, int startInclusive, int endExclusive) {
        BigDecimal total = BigDecimal.ZERO;
        for (int i = startInclusive; i < endExclusive; i++) total = total.add(BigDecimal.valueOf(candles.get(i).volume()));
        return total.divide(BigDecimal.valueOf(endExclusive - startInclusive), 2, RoundingMode.HALF_UP);
    }

    private static void validateChronology(List<MarketCandle> candles) {
        Instant previous = null;
        for (MarketCandle candle : candles) {
            if (candle == null || candle.candleTime() == null || candle.open() == null || candle.high() == null
                    || candle.low() == null || candle.close() == null) {
                throw new IllegalArgumentException("Candle history contains incomplete rows");
            }
            if (previous != null && !candle.candleTime().isAfter(previous)) {
                throw new IllegalArgumentException("Candle history must be strictly chronological");
            }
            previous = candle.candleTime();
        }
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal price(BigDecimal value) {
        return value.setScale(6, RoundingMode.HALF_UP);
    }
}