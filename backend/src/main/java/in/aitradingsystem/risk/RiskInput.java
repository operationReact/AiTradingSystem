package in.aitradingsystem.risk;

import in.aitradingsystem.domain.Side;
import java.math.BigDecimal;
import java.time.Instant;

public record RiskInput(
    Side side,
    int quantity,
    BigDecimal referencePrice,
    BigDecimal stopLoss,
    BigDecimal targetPrice,
    Instant signalGeneratedAt,
    Instant now,
    BigDecimal startingCapital,
    BigDecimal availableCash,
    int heldQuantity,
    int openPositionCount,
    BigDecimal realizedPnlToday,
    boolean newOrdersHalted
) {}