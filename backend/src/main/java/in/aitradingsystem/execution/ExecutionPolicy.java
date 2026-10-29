package in.aitradingsystem.execution;

import in.aitradingsystem.risk.ExecutionMode;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record ExecutionPolicy(
    UUID id,
    int policyVersion,
    ExecutionMode executionMode,
    String strategy,
    ExecutionPolicyTradingStyle tradingStyle,
    ApprovalMode approvalMode,
    BigDecimal maxOrderNotional,
    int maxDailyTrades,
    BigDecimal maxGrossExposure,
    BigDecimal maxDailyLoss,
    List<String> symbolAllowlist,
    List<String> symbolDenylist,
    LocalTime sessionStart,
    LocalTime sessionEnd,
    OffsetDateTime effectiveFrom,
    OffsetDateTime effectiveTo
) {}
