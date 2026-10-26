package in.aitradingsystem.domain;

import java.math.BigDecimal;
import java.time.Instant;

public record PaperTrade(
    String id, String symbol, String side, int quantity, BigDecimal entryPrice,
    BigDecimal stopLoss, BigDecimal target, BigDecimal estimatedCost,
    BigDecimal riskAmount, String status, Instant createdAt
) {}
