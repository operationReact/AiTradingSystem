package in.aitradingsystem.fundamentals;

import java.time.Instant;
import java.util.Map;

public record FundamentalSnapshotRecord(
    String id,
    String symbol,
    String provider,
    String providerRecordId,
    Instant periodEnd,
    Instant availableAt,
    Map<String, Double> metrics,
    String contentHash,
    Instant ingestedAt
) {}
