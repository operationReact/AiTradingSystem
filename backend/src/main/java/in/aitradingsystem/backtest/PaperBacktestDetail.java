package in.aitradingsystem.backtest;

import java.util.List;

public record PaperBacktestDetail(
    BacktestRunSummary run,
    List<BacktestTradeResult> trades
) {}