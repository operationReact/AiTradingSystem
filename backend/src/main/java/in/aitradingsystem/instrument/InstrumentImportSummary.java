package in.aitradingsystem.instrument;

import java.time.Instant;
import java.util.UUID;

public record InstrumentImportSummary(
        UUID id,
        String status,
        Instant startedAt,
        Instant completedAt,
        long downloadedBytes,
        int parsedCount,
        int acceptedCount,
        int rejectedCount,
        int duplicateCount,
        int activeCount,
        String errorCode,
        String errorMessage,
        UUID catalogueVersion) {}
