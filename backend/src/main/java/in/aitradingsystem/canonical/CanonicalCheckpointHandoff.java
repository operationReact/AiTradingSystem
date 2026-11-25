package in.aitradingsystem.canonical;

import in.aitradingsystem.marketdata.GoldenSourceBackfillCoordinator;
import in.aitradingsystem.marketdata.GoldenSourceRightsRepository;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Internal read-only metadata handoff. Not physical candle or coverage evidence. */
@Service
public class CanonicalCheckpointHandoff {
    private final GoldenSourceBackfillCoordinator producer;
    private final CanonicalManifestReceiptSummary consumer;
    private final GoldenSourceRightsRepository rights;

    public CanonicalCheckpointHandoff(GoldenSourceBackfillCoordinator producer,
            CanonicalManifestReceiptSummary consumer, GoldenSourceRightsRepository rights) {
        this.producer = Objects.requireNonNull(producer);
        this.consumer = Objects.requireNonNull(consumer);
        this.rights = Objects.requireNonNull(rights);
    }

    public record Receipt(UUID jobId, String provider, String sourceDomain, UUID listingId,
            String interval, int revision, Instant asOf, Instant usageAt,
            long producerCheckpointReceipts, long eligibleConsumerManifests,
            long eligibleManifestDeclaredRows) {}

    /** Unknown or denied scope returns empty; never grants rights or exposes object contents. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ,
            propagation = Propagation.REQUIRES_NEW)
    public Optional<Receipt> read(UUID jobId, Instant asOf, Instant usageAt) {
        Objects.requireNonNull(jobId, "jobId");
        if (asOf == null || usageAt == null || asOf.isAfter(usageAt)
                || usageAt.isAfter(Instant.now())) {
            throw new IllegalArgumentException("valid non-future as-of and usage times required");
        }
        var checkpoint = producer.checkpointReadback(jobId);
        if (checkpoint.isEmpty()) return Optional.empty();
        var p = checkpoint.orElseThrow();
        if (!rights.isPermitted(p.providerCode(), p.sourceDomain(),
                    GoldenSourceRightsRepository.Use.RAW_RETENTION, usageAt)
                || !rights.isPermitted(p.providerCode(), p.sourceDomain(),
                    GoldenSourceRightsRepository.Use.MODEL_INFERENCE, usageAt)) {
            return Optional.empty();
        }
        var rows = consumer.findEligibleForJob(jobId, p.listingId(), p.intervalName(), asOf, usageAt);
        if (rows.size() != 1) return Optional.empty();
        var row = rows.get(0);
        if (!p.providerCode().equals(row.provider()) || !p.sourceDomain().equals(row.sourceDomain())
                || row.verifiedManifests() <= 0
                || row.verifiedManifests() > p.verifiedCheckpointReceipts()
                || row.verifiedRows() <= 0) return Optional.empty();
        return Optional.of(new Receipt(jobId, p.providerCode(), p.sourceDomain(), p.listingId(),
                p.intervalName(), p.revision(), asOf, usageAt, p.verifiedCheckpointReceipts(),
                row.verifiedManifests(), row.verifiedRows()));
    }
}
