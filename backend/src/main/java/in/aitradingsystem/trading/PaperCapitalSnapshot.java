package in.aitradingsystem.trading;

import java.math.BigDecimal;

public record PaperCapitalSnapshot(
    BigDecimal startingCapital,
    BigDecimal availableCapital,
    BigDecimal committedCapital,
    BigDecimal realizedPnl
) {}
