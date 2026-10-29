package in.aitradingsystem.execution;

import java.time.Instant;
import java.util.UUID;

public record OrderApprovalView(
    UUID id,
    UUID orderId,
    UUID policyId,
    int policyVersion,
    OrderApprovalState state,
    String decisionReason,
    Instant requestedAt,
    Instant expiresAt,
    Instant decidedAt,
    String decidedBy,
    int rowVersion,
    UUID riskDecisionId,
    String requestSnapshot
) {}
