package in.aitradingsystem.scanner;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

public record StrategyConfigSnapshot(
        UUID id,
        String strategyCode,
        int configVersion,
        boolean enabled,
        JsonNode config,
        Instant effectiveFrom,
        Instant effectiveTo,
        String createdBy,
        Instant createdAt) {
}
