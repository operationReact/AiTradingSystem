package in.aitradingsystem.risk;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Backward-compatible list shape plus Phase 5 V2 evidence. */
public record RiskDecisionSummary(
        UUID id,
        UUID orderId,
        String decision,
        List<String> reasonCodes,
        BigDecimal riskBudget,
        BigDecimal estimatedRisk,
        BigDecimal availableCash,
        int openPositions,
        BigDecimal realizedPnlToday,
        BigDecimal dailyLossLimit,
        String policyVersion,
        Instant evaluatedAt,
        RiskDecisionClass resultClass,
        UUID policyConfigId,
        BigDecimal rewardRiskRatio,
        BigDecimal grossExposureAfter,
        BigDecimal symbolExposureAfter,
        Integer tradesTodayAfter,
        long failedRuleCount,
        String correlationId) {
    public RiskDecisionSummary {
        reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes);
    }
}
