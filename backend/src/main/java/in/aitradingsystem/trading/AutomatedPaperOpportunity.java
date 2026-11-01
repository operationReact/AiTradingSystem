package in.aitradingsystem.trading;

import java.math.BigDecimal;

public record AutomatedPaperOpportunity(
    String opportunityId,
    String symbol,
    String side,
    int quantity,
    BigDecimal entryPrice,
    BigDecimal stopLoss,
    BigDecimal target,
    int confidencePercent,
    boolean eligible
) {}
