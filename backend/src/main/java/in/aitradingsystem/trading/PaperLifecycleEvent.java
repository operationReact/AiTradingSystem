package in.aitradingsystem.trading;

import java.math.BigDecimal;

public record PaperLifecycleEvent(
    String accountId,
    String symbol,
    String state,
    BigDecimal pnl,
    String message
) {}
