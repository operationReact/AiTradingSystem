package in.aitradingsystem.trading;

import java.math.BigDecimal;

public record PaperClosedTrade(
    String orderId,
    String symbol,
    String side,
    int quantity,
    BigDecimal entryPrice,
    BigDecimal exitPrice,
    BigDecimal realizedPnl
) {}
