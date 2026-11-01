package in.aitradingsystem.trading;

import java.math.BigDecimal;

public record PaperTradeIntent(
    String idempotencyKey,
    String symbol,
    String side,
    int quantity,
    BigDecimal entryPrice,
    BigDecimal stopLoss,
    BigDecimal target,
    TradeIntentSource source
) {}
