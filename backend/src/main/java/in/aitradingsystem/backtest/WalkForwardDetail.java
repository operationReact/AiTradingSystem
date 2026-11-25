package in.aitradingsystem.backtest;

import java.util.List;

public record WalkForwardDetail(
        BacktestWalkForwardRun run,
        List<BacktestWalkForwardFold> folds) {
    public WalkForwardDetail {
        folds = folds == null ? List.of() : List.copyOf(folds);
    }
}
