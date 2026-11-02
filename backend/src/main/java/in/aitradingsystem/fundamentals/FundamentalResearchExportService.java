package in.aitradingsystem.fundamentals;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class FundamentalResearchExportService {
    static final String CONTRACT_VERSION = "fundamental-snapshots.v1";
    private static final int MAX_ROWS = 20_000;

    private final FundamentalSnapshotRepository repository;

    public FundamentalResearchExportService(FundamentalSnapshotRepository repository) {
        this.repository = repository;
    }

    public FundamentalResearchExport export(Instant fromInclusive, Instant toExclusive, int limit) {
        if (fromInclusive == null || toExclusive == null || !fromInclusive.isBefore(toExclusive)) {
            throw new IllegalArgumentException("fromInclusive must be before toExclusive");
        }
        if (limit < 1 || limit > MAX_ROWS) {
            throw new IllegalArgumentException("Fundamental snapshot limit must be between 1 and " + MAX_ROWS);
        }

        List<FundamentalSnapshotRecord> rows = repository.researchSnapshots(fromInclusive, toExclusive, limit);
        List<FundamentalResearchExport.ResearchSnapshot> snapshots = rows.stream()
            .map(row -> new FundamentalResearchExport.ResearchSnapshot(
                row.id(),
                row.symbol(),
                row.provider(),
                row.providerRecordId(),
                row.periodEnd(),
                row.availableAt(),
                row.metrics(),
                row.contentHash()))
            .toList();
        Instant dataCutoff = snapshots.stream()
            .map(FundamentalResearchExport.ResearchSnapshot::availableAt)
            .max(Comparator.naturalOrder())
            .orElse(null);
        List<String> sources = snapshots.stream()
            .map(FundamentalResearchExport.ResearchSnapshot::provider)
            .filter(Objects::nonNull)
            .distinct()
            .sorted()
            .toList();

        return new FundamentalResearchExport(
            CONTRACT_VERSION,
            Instant.now(),
            fromInclusive,
            toExclusive,
            dataCutoff,
            sources,
            snapshots);
    }
}
