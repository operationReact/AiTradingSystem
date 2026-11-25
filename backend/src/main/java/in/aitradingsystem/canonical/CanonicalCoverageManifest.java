package in.aitradingsystem.canonical;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Provider-neutral coverage evidence v1. This is a pure contract: no ingestion,
 * storage, grant issuance, or strategy/execution behavior.
 */
public final class CanonicalCoverageManifest {
    public static final String SCHEMA_VERSION = "minttick.canonical.coverage.v1";

    public enum Status { ACQUIRED, VALIDATED, GAP, STALE, DENIED }

    public record Partition(String instrumentId, String exchange, String listingEpisodeId,
                            int year, String interval, String provider, String datasetVersion) {
        public Partition {
            require(instrumentId, "instrumentId");
            require(exchange, "exchange");
            require(listingEpisodeId, "listingEpisodeId");
            require(interval, "interval");
            require(provider, "provider");
            require(datasetVersion, "datasetVersion");
            if (year < 1900 || year > 2200) throw new IllegalArgumentException("invalid year");
        }
    }

    public record Evidence(Partition partition, Status status, long observedRows,
                           Instant observedAt, Instant availableAt, String checksum,
                           String sourceEvidenceId, boolean retentionGrantVerified) {
        public Evidence {
            Objects.requireNonNull(partition, "partition");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(observedAt, "observedAt");
            Objects.requireNonNull(availableAt, "availableAt");
            require(sourceEvidenceId, "sourceEvidenceId");
            if (observedRows < 0) throw new IllegalArgumentException("negative row count");
            if (availableAt.isAfter(observedAt)) throw new IllegalArgumentException("future availability");
            if (status == Status.VALIDATED) {
                if (observedRows == 0 || !retentionGrantVerified)
                    throw new IllegalArgumentException("validation requires positive rows and verified retention grant");
                require(checksum, "checksum");
            }
            if (status == Status.DENIED && retentionGrantVerified)
                throw new IllegalArgumentException("denied partition cannot assert retention grant");
        }

        public boolean researchInputEligible(Instant asOf) {
            return status == Status.VALIDATED && retentionGrantVerified
                    && !availableAt.isAfter(Objects.requireNonNull(asOf, "asOf"));
        }
    }

    public record Summary(long acquired, long validated, long gap, long stale, long denied) {
        public long total() { return Math.addExact(Math.addExact(Math.addExact(acquired, validated),
                Math.addExact(gap, stale)), denied); }
    }

    public static Summary summarize(List<Evidence> evidence) {
        Objects.requireNonNull(evidence, "evidence");
        long[] counts = new long[Status.values().length];
        for (Evidence row : evidence) {
            Objects.requireNonNull(row, "row");
            counts[row.status().ordinal()]++;
        }
        return new Summary(counts[0], counts[1], counts[2], counts[3], counts[4]);
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " required");
    }

    private CanonicalCoverageManifest() { }
}
