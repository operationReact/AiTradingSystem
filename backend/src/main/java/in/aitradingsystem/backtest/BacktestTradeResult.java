package in.aitradingsystem.backtest;

import java.math.BigDecimal;
import java.time.Instant;

public record BacktestTradeResult(
    int tradeNumber,
    Instant entryAt,
    Instant exitAt,
    BigDecimal entryPrice,
    BigDecimal exitPrice,
    int quantity,
    BigDecimal stopLoss,
    BigDecimal targetPrice,
    BigDecimal grossPnl,
    BigDecimal entryFee,
    BigDecimal exitFee,
    BigDecimal netPnl,
    String exitReason
) {}