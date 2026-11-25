package in.aitradingsystem.backtest;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record BacktestWalkForwardRun(
        UUID id,
        long instrumentId,
        String exchange,
        String tradingSymbol,
        String interval,
        String strategyCode,
        String strategyVersion,
        LocalDate fromDate,
        LocalDate toDate,
        int trainingWindowDays,
        int validationWindowDays,
        int stepDays,
        String status,
        Instant createdAt,
        Instant completedAt,
        String errorMessage) {}
