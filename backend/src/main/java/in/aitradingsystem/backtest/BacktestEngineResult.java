package in.aitradingsystem.backtest;

import java.math.BigDecimal;
import java.util.List;

public record BacktestEngineResult(
    int candlesEvaluated,
    int tradeCount,
    int winningTrades,
    int losingTrades,
    BigDecimal winRatePercent,
    BigDecimal grossPnl,
    BigDecimal totalFees,
    BigDecimal netPnl,
    BigDecimal finalEquity,
    BigDecimal totalReturnPercent,
    BigDecimal maxDrawdownPercent,
    BigDecimal profitFactor,
    List<BacktestTradeResult> trades
) {}