package in.aitradingsystem.canonical;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.aitradingsystem.marketdata.GoldenSourceRightsRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import static org.mockito.Mockito.mock;

class CanonicalAsOfManifestReceiptsTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final GoldenSourceRightsRepository rights =
            mock(GoldenSourceRightsRepository.class);
    private final CanonicalAsOfManifestReceipts repository =
            new CanonicalAsOfManifestReceipts(jdbc, rights);
    private final UUID listing = UUID.randomUUID();
    private final Instant asOf = Instant.parse("2026-11-12T10:00:00Z");
    private final Instant usageAt = Instant.parse("2026-11-14T00:00:00Z");

    @Test
    void absentPersistedReceiptsReturnEmptyNotFabricatedCounts() {
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<CanonicalAsOfManifestReceipts.Receipt>>any(),
                eq(listing), eq("DAY"), any(), any())).thenReturn(List.of());
        assertEquals(List.of(), repository.findResearchInputs(listing, "DAY", asOf, usageAt));
    }

    @Test
    void revokedOrMissingInferenceRightsDenyPersistedReceipt() {
        var receipt = new CanonicalAsOfManifestReceipts.Receipt(
                UUID.randomUUID(), listing, "UPSTOX", "upstox_historical", "DAY",
                1, "digest", "v1", asOf.minusSeconds(100),
                asOf.minusSeconds(50), asOf.minusSeconds(10), 2);
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<CanonicalAsOfManifestReceipts.Receipt>>any(),
                eq(listing), eq("DAY"), any(), any())).thenReturn(List.of(receipt));
        when(rights.isPermitted("UPSTOX", "upstox_historical",
                GoldenSourceRightsRepository.Use.RAW_RETENTION, usageAt)).thenReturn(true);
        assertEquals(List.of(), repository.findResearchInputs(listing, "DAY", asOf, usageAt));
        verify(rights).isPermitted("UPSTOX", "upstox_historical",
                GoldenSourceRightsRepository.Use.MODEL_INFERENCE, usageAt);
    }

    @Test
    void independentCurrentGrantsPermitMetadataOnly() {
        var receipt = new CanonicalAsOfManifestReceipts.Receipt(
                UUID.randomUUID(), listing, "ANGEL_ONE", "angel_historical", "DAY",
                2, "digest", "v1", asOf.minusSeconds(100),
                asOf.minusSeconds(50), asOf.minusSeconds(10), 4);
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<CanonicalAsOfManifestReceipts.Receipt>>any(),
                eq(listing), eq("DAY"), any(), any())).thenReturn(List.of(receipt));
        when(rights.isPermitted(eq("ANGEL_ONE"), eq("angel_historical"),
                any(GoldenSourceRightsRepository.Use.class), eq(usageAt))).thenReturn(true);
        assertEquals(List.of(receipt), repository.findResearchInputs(listing, "DAY", asOf, usageAt));
    }

    @Test
    void invalidAsOfAndIntervalFailClosedBeforeDatabaseRead() {
        assertThrows(IllegalArgumentException.class,
                () -> repository.findResearchInputs(listing, "DAY;DROP", asOf, usageAt));
        assertThrows(IllegalArgumentException.class,
                () -> repository.findResearchInputs(listing, "DAY", usageAt.plusSeconds(1), usageAt));
    }
}
