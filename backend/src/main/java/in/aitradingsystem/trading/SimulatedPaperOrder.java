package in.aitradingsystem.trading;

import java.math.BigDecimal;

public record SimulatedPaperOrder(
    String orderId,
    String idempotencyKey,
    String symbol,
    String side,
    int quantity,
    BigDecimal fillPrice,
    BigDecimal stopLoss,
    BigDecimal target,
    PaperOrderState state
) {}
