package in.aitradingsystem.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record TradeSignal(
    String id, String symbol, String action, String strategy, BigDecimal referencePrice,
    BigDecimal stopLoss, BigDecimal target, String timeframe, double confidence,
    List<String> reasonCodes, String source, Instant createdAt, String status
) {}
