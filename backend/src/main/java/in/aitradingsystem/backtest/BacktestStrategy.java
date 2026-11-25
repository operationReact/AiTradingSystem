package in.aitradingsystem.backtest;

/** Strategy-neutral deterministic contract used by backtest engines. */
public interface BacktestStrategy {
    String code();

    String version();

    BacktestSignal evaluate(BacktestContext context);
}
