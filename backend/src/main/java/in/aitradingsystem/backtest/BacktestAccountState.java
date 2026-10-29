package in.aitradingsystem.backtest;

import java.math.BigDecimal;

/** Account state visible to a strategy at the current backtest cutoff only. */
public record BacktestAccountState(
        BigDecimal cash,
        BigDecimal equity,
        BigDecimal positionQuantity) {

    public BacktestAccountState {
        if (cash == null) throw new IllegalArgumentException("cash is required");
        if (equity == null) throw new IllegalArgumentException("equity is required");
        if (positionQuantity == null) throw new IllegalArgumentException("positionQuantity is required");
    }
}
