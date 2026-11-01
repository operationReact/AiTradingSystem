package in.aitradingsystem.trading;

import java.math.BigDecimal;

public record PaperPosition(
    String orderId,
    String symbol,
    String side,
    int quantity,
    BigDecimal entryPrice,
    BigDecimal stopLoss,
    BigDecimal target,
    BigDecimal lastPrice,
    BigDecimal unrealizedPnl,
    BigDecimal realizedPnl,
    boolean open
) {}
