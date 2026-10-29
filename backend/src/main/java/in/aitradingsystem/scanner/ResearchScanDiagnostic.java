package in.aitradingsystem.scanner;

import java.time.Instant;
import java.util.UUID;

public record ResearchScanDiagnostic(
        long id,
        UUID scanRunId,
        Long instrumentId,
        String strategy,
        String severity,
        String code,
        String message,
        Instant createdAt) {
}
