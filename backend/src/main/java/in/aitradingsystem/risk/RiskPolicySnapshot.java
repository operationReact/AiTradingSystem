package in.aitradingsystem.risk;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Immutable policy values consumed by RiskContext. Persistence/version lifecycle is implemented in P05-PR02.
 */
public record RiskPolicySnapshot(
        UUID id,
        int version,
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

    public RiskPolicySnapshot {
        if (id == null) throw new IllegalArgumentException("policy id is required");
        if (version < 1) throw new IllegalArgumentException("policy version must be positive");
        if (riskPerTradePercent == null || maxDailyDrawdownPercent == null || feeRate == null || slippageRate == null) {
            throw new IllegalArgumentException("core policy monetary/rate values are required");
        }
        if (maxOpenPositions < 1 || signalMaxAgeMinutes < 1 || marketStateMaxAgeSeconds < 1) {
            throw new IllegalArgumentException("core policy limits must be positive");
        }
        if (feeSlippageProfileVersion == null || feeSlippageProfileVersion.isBlank()) {
            throw new IllegalArgumentException("fee/slippage profile version is required");
        }
    }
}
