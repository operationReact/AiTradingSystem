package in.aitradingsystem.canonical;

import in.aitradingsystem.marketdata.GoldenSourceBackfillCoordinator;
import in.aitradingsystem.marketdata.GoldenSourceRightsRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CanonicalCheckpointHandoffTest {
    @Test
    void exactJobAndIndependentRightsAreRequired() {
        var producer = mock(GoldenSourceBackfillCoordinator.class);
        var consumer = mock(CanonicalManifestReceiptSummary.class);
        var rights = mock(GoldenSourceRightsRepository.class);
        var service = new CanonicalCheckpointHandoff(producer, consumer, rights);
        var job = UUID.randomUUID();
        var listing = UUID.randomUUID();
        var at = Instant.parse("2025-01-01T00:00:00Z");
        when(producer.checkpointReadback(job)).thenReturn(Optional.empty());
        assertTrue(service.read(job, at, at).isEmpty());
        when(producer.checkpointReadback(job)).thenReturn(Optional.of(
                new GoldenSourceBackfillCoordinator.CheckpointReadback(
                        "UPSTOX", "historical", listing, "DAY", 2, 3, 2, 0, 0, 1)));
        assertTrue(service.read(job, at, at).isEmpty());
        when(rights.isPermitted("UPSTOX", "historical",
                GoldenSourceRightsRepository.Use.RAW_RETENTION, at)).thenReturn(true);
        assertTrue(service.read(job, at, at).isEmpty());
        verifyNoInteractions(consumer);
        when(rights.isPermitted("UPSTOX", "historical",
                GoldenSourceRightsRepository.Use.MODEL_INFERENCE, at)).thenReturn(true);
        when(consumer.findEligibleForJob(job, listing, "DAY", at, at)).thenReturn(List.of(
                new CanonicalManifestReceiptSummary.ProviderReceipt("UPSTOX", "historical",
                        2, 100, at, at)));
        assertTrue(service.read(job, at, at).isEmpty());
        when(consumer.findEligibleForJob(job, listing, "DAY", at, at)).thenReturn(List.of(
                new CanonicalManifestReceiptSummary.ProviderReceipt("UPSTOX", "historical",
                        1, 50, at, at)));
        var receipt = service.read(job, at, at).orElseThrow();
        assertEquals(job, receipt.jobId());
        assertEquals(2, receipt.revision());
        assertEquals(1, receipt.eligibleConsumerManifests());
        assertEquals(50, receipt.eligibleManifestDeclaredRows());
    }
}
