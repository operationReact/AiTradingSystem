package in.aitradingsystem.instrument;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record InstrumentSearchResponse(
        List<Instrument> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext,
        UUID catalogueVersion,
        Instant activatedAt) {}
