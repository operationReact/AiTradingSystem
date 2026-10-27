package in.aitradingsystem.risk;

import java.math.BigDecimal;
import java.util.List;

public record RiskDecision(
    boolean approved,
    List<RiskReason> reasons,
    BigDecimal fillPrice,
    BigDecimal grossAmount,
    BigDecimal feeAmount,
    BigDecimal slippageAmount,
    BigDecimal netAmount,
    BigDecimal estimatedRisk,
    BigDecimal riskBudget,
    BigDecimal dailyLossLimit
) {}