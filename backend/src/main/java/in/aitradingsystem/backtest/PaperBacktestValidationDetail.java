package in.aitradingsystem.backtest;

public record PaperBacktestValidationDetail(
    BacktestValidationSummary validation,
    PaperBacktestDetail training,
    PaperBacktestDetail outOfSample
) {}
