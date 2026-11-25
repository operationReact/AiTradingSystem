package in.aitradingsystem.backtest;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record BacktestValidationSummary(
    UUID id,
    long instrumentId,
    String exchange,
    String tradingSymbol,
    String symbolToken,
    UUID catalogueVersion,
    String interval,
    LocalDate fromDate,
    LocalDate toDate,
    LocalDate splitDate,
    int outOfSamplePercent,
    UUID trainingRunId,
    UUID outOfSampleRunId,
    int trainingCandles,
    int outOfSampleCandles,
    BigDecimal trainingReturnPercent,
    BigDecimal outOfSampleReturnPercent,
    BigDecimal returnDeltaPercentagePoints,
    Instant createdAt
) {}
