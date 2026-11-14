package in.aitradingsystem.canonical;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import in.aitradingsystem.marketdata.GoldenSourceRightsRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class CanonicalManifestReceiptSummaryTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final GoldenSourceRightsRepository rights = mock(GoldenSourceRightsRepository.class);
    private final CanonicalManifestReceiptSummary summary = new CanonicalManifestReceiptSummary(jdbc, rights);
    private final UUID listing = UUID.randomUUID();
    private final Instant asOf = Instant.parse("2026-11-12T10:00:00Z");
    private final Instant usageAt = Instant.parse("2026-11-14T00:00:00Z");

    private void receipts(List<CanonicalManifestReceiptSummary.ProviderReceipt> rows) {
        when(jdbc.query(anyString(),
                org.mockito.ArgumentMatchers.<RowMapper<CanonicalManifestReceiptSummary.ProviderReceipt>>any(),
                eq(listing), eq("DAY"), any(), any())).thenReturn(rows);
    }

    @Test
    void emptyPersistedReceiptsAreNotFabricatedCoverage() {
        receipts(List.of());
        assertEquals(List.of(), summary.findEligible(listing, "DAY", asOf, usageAt));
    }

    @Test
    void missingInferenceGrantSuppressesRealReceipt() {
        var row = new CanonicalManifestReceiptSummary.ProviderReceipt(
                "UPSTOX", "historical", 2, 200, asOf.minusSeconds(200), asOf.minusSeconds(10));
        receipts(List.of(row));
        when(rights.isPermitted("UPSTOX", "historical",
                GoldenSourceRightsRepository.Use.RAW_RETENTION, usageAt)).thenReturn(true);
        assertEquals(List.of(), summary.findEligible(listing, "DAY", asOf, usageAt));
    }

    @Test
    void independentGrantsAllowReceiptMetadataWithoutImplyingReadback() {
        var row = new CanonicalManifestReceiptSummary.ProviderReceipt(
                "ANGEL_ONE", "historical", 1, 50, asOf.minusSeconds(200), asOf.minusSeconds(10));
        receipts(List.of(row));
        when(rights.isPermitted(eq("ANGEL_ONE"), eq("historical"),
                any(GoldenSourceRightsRepository.Use.class), eq(usageAt))).thenReturn(true);
        assertEquals(List.of(row), summary.findEligible(listing, "DAY", asOf, usageAt));
    }

    @Test
    void receiptAggregationUsesExistenceNotManifestJoinToAvoidInflatedRows() {
        receipts(List.of());
        summary.findEligible(listing, "DAY", asOf, usageAt);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(),
                org.mockito.ArgumentMatchers.<RowMapper<CanonicalManifestReceiptSummary.ProviderReceipt>>any(),
                eq(listing), eq("DAY"), any(), any());
        assertTrue(sql.getValue().contains("COUNT(DISTINCT c.chunk_id)"));
        assertTrue(sql.getValue().contains("EXISTS ("));
        assertTrue(sql.getValue().contains("m.object_sha256 = c.dataset_digest"));
        assertTrue(sql.getValue().contains("m.published_at <= ?"));
        assertTrue(sql.getValue().contains("m.published_at >= c.last_available_at"));
        assertTrue(sql.getValue().contains("c.first_available_at <= c.last_available_at"));
        assertTrue(sql.getValue().contains("c.first_available_at IS NOT NULL"));
        assertTrue(sql.getValue().contains("c.last_available_at IS NOT NULL"));
        assertTrue(!sql.getValue().contains("JOIN golden_dataset_object_manifest"));
    }

    @Test
    void invalidScopeAndFutureAsOfFailBeforeQuery() {
        assertThrows(IllegalArgumentException.class,
                () -> summary.findEligible(listing, "DAY;DROP", asOf, usageAt));
        assertThrows(IllegalArgumentException.class,
                () -> summary.findEligible(listing, "DAY", usageAt.plusSeconds(1), usageAt));
        verifyNoInteractions(jdbc);
    }
}
