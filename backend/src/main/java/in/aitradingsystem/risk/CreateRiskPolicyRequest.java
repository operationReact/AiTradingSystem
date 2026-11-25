package in.aitradingsystem.risk;

import java.math.BigDecimal;

/** A new immutable policy version. policyVersion is deliberately server-assigned. */
public record CreateRiskPolicyRequest(
        String policyName,
        BigDecimal riskPerTradePercent,
        BigDecimal maxDailyDrawdownPercent,
        int maxOpenPositions,
        BigDecimal minRewardRiskRatio,
        BigDecimal maxOrderNotional,
        BigDecimal maxGrossExposurePercent,
        BigDecimal maxSymbolExposurePercent,
        int maxTradesPerDay,
        int signalMaxAgeMinutes,
        int marketStateMaxAgeSeconds,
        boolean enforceMarketSession,
        boolean rejectExistingSymbolPosition,
        boolean rejectDuplicatePendingExposure,
        BigDecimal feeRate,
        BigDecimal slippageRate,
        String feeSlippageProfileVersion) {
}
