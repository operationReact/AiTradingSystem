package in.aitradingsystem.marketdata;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record CandleBackfillRun(
        UUID id,
        long instrumentId,
        String exchange,
        String tradingSymbol,
        String symbolToken,
        UUID catalogueVersion,
        String interval,
        LocalDate fromDate,
        LocalDate toDate,
        LocalDate nextFromDate,
        String status,
        int chunksCompleted,
        long fetchedRows,
        long persistedRows,
        Instant startedAt,
        Instant updatedAt,
        Instant completedAt,
        String errorCode,
        String errorMessage) {}
