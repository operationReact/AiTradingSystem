package in.aitradingsystem.backtest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record BacktestWalkForwardFold(
        UUID walkForwardId,
        int foldNumber,
        UUID trainingRunId,
        UUID validationRunId,
        LocalDate trainingFrom,
        LocalDate trainingTo,
        LocalDate validationFrom,
        LocalDate validationTo,
        BigDecimal trainingReturnPercent,
        BigDecimal validationReturnPercent,
        BigDecimal validationMaxDrawdownPercent) {}
