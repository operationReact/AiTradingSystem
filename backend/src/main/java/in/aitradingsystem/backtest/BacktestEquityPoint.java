package in.aitradingsystem.backtest;

import java.math.BigDecimal;
import java.time.Instant;

public record BacktestEquityPoint(
        Instant pointTime,
        BigDecimal equity,
        BigDecimal cash,
        BigDecimal openValue) {

    public BacktestEquityPoint {
        if (pointTime == null) throw new IllegalArgumentException("pointTime is required");
        if (equity == null || cash == null || openValue == null) {
            throw new IllegalArgumentException("equity, cash and openValue are required");
        }
    }
}
