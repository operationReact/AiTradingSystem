package in.aitradingsystem.trading;

import java.math.BigDecimal;

public record PaperAutomationSnapshot(
    TradingMode tradingMode,
    ExecutionMode executionMode,
    boolean liveAvailable,
    boolean tradingPaused,
    String safetyLabel,
    BigDecimal unrealizedPnl,
    BigDecimal realizedPnl
) {}
