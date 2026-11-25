package in.aitradingsystem.risk;

import java.time.Instant;
import java.util.UUID;

public record RiskPolicyView(
        UUID id,
        int version,
        String policyName,
        boolean active,
        RiskPolicySnapshot snapshot,
        Instant effectiveFrom,
        Instant effectiveTo,
        String createdBy,
        Instant createdAt) {
}
