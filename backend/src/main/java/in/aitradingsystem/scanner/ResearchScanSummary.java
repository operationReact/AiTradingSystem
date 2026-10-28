package in.aitradingsystem.scanner;

import java.time.Instant;
import java.util.UUID;

public record ResearchScanSummary(
        UUID id,
        String interval,
        String status,
        int requestedLimit,
        int scannedCount,
        int candidateCount,
        String strategySet,
        Instant startedAt,
        Instant completedAt,
        String errorCode,
        String errorMessage) {}
