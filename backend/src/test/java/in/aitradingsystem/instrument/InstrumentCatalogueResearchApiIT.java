package in.aitradingsystem.instrument;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import in.aitradingsystem.support.AbstractPostgresIT;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

class InstrumentCatalogueResearchApiIT extends AbstractPostgresIT {
    @Autowired MockMvc mvc;
    @Autowired InstrumentMasterService instrumentService;
    @Autowired InstrumentCatalogueResearchService researchService;
    @Autowired JdbcTemplate jdbc;

    private static final String BASE = "/api/instruments/research/cash-catalogue";
    private static final Instant FIRST_AVAILABLE = Instant.parse("2026-11-01T10:00:00Z");
    private static final Instant SECOND_AVAILABLE = Instant.parse("2026-11-02T10:00:00Z");
    private static final Instant THIRD_AVAILABLE = Instant.parse("2026-11-02T11:00:00Z");
    private static final Instant FOURTH_AVAILABLE = Instant.parse("2026-11-02T12:00:00Z");

    private static final String FIRST_MASTER = """
            [
              {"token":"2885","symbol":"RELIANCE-EQ","name":"RELIANCE","expiry":"","strike":"-1","lotsize":"1","instrumenttype":"EQ","exch_seg":"nse_cm","tick_size":"5"},
              {"token":"500112","symbol":"SBIN-A","name":"STATE BANK OF INDIA","expiry":"","strike":"-1","lotsize":"1","instrumenttype":"EQ","exch_seg":"bse_cm","tick_size":"5"},
              {"token":"999","symbol":"NIFTY27NOV26FUT","name":"NIFTY","expiry":"27NOV2026","strike":"-1","lotsize":"25","instrumenttype":"FUTIDX","exch_seg":"nse_fo","tick_size":"5"}
            ]
            """;

    private static final String SECOND_MASTER = """
            [
              {"token":"2885","symbol":"RELIANCE-EQ","name":"RELIANCE INDUSTRIES","expiry":"","strike":"-1","lotsize":"1","instrumenttype":"EQ","exch_seg":"nse_cm","tick_size":"5"},
              {"token":"3045","symbol":"SBIN-EQ","name":"STATE BANK OF INDIA","expiry":"","strike":"-1","lotsize":"1","instrumenttype":"EQ","exch_seg":"nse_cm","tick_size":"5"},
              {"token":"1000","symbol":"NIFTY27NOV26FUT","name":"NIFTY","expiry":"27NOV2026","strike":"-1","lotsize":"25","instrumenttype":"FUTIDX","exch_seg":"nse_fo","tick_size":"5"}
            ]
            """;

    @BeforeEach
    void resetInstrumentCatalogue() {
        jdbc.update("DELETE FROM instrument_cash_catalogue_history");
        jdbc.update("DELETE FROM instrument_master");
        jdbc.update("UPDATE instrument_catalogue_state SET active_version = NULL, activated_at = NULL WHERE singleton_id = 1");
        jdbc.update("DELETE FROM instrument_import_run");
    }

    @Test
    void exportsOnlyCausalCashEquitiesWithStableFingerprint() throws Exception {
        InstrumentImportSummary first = importAt(FIRST_MASTER, FIRST_AVAILABLE);
        InstrumentCatalogueResearchExport firstRead = researchService.exportCashCatalogue(
                Instant.parse("2026-11-01T12:00:00Z"), 100);
        InstrumentCatalogueResearchExport repeat = researchService.exportCashCatalogue(
                Instant.parse("2026-11-01T15:00:00Z"), 100);

        assertThat(firstRead.contractVersion()).isEqualTo("instrument-catalogue.v1");
        assertThat(firstRead.catalogueVersion()).isEqualTo(first.catalogueVersion());
        assertThat(firstRead.catalogueAvailableAt()).isEqualTo(FIRST_AVAILABLE);
        assertThat(firstRead.dataCutoff()).isEqualTo(FIRST_AVAILABLE);
        assertThat(firstRead.exchanges()).containsExactly("BSE", "NSE");
        assertThat(firstRead.instrumentType()).isEqualTo("EQ");
        assertThat(firstRead.rowCount()).isEqualTo(2);
        assertThat(firstRead.instruments()).extracting(InstrumentCatalogueResearchExport.ResearchInstrument::exchange)
                .containsExactly("BSE", "NSE");
        assertThat(firstRead.instruments()).extracting(InstrumentCatalogueResearchExport.ResearchInstrument::tradingSymbol)
                .containsExactly("SBIN-A", "RELIANCE-EQ");
        assertThat(firstRead.catalogueFingerprint()).matches("[a-f0-9]{64}");
        assertThat(repeat.catalogueFingerprint()).isEqualTo(firstRead.catalogueFingerprint());
        assertThat(firstRead.status()).isEqualTo("RESEARCH_CATALOGUE_ONLY");
        assertThat(firstRead.canAuthorizeCapital()).isFalse();

        Integer derivativeHistory = jdbc.queryForObject(
                "SELECT count(*) FROM instrument_cash_catalogue_history WHERE catalogue_version = ? AND instrument_type <> 'EQ'",
                Integer.class, first.catalogueVersion());
        assertThat(derivativeHistory).isZero();

        mvc.perform(get(BASE)
                        .param("asOf", "2026-11-01T12:00:00Z")
                        .param("maxRows", "100")
                        .header("X-API-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contractVersion").value("instrument-catalogue.v1"))
                .andExpect(jsonPath("$.rowCount").value(2))
                .andExpect(jsonPath("$.canAuthorizeCapital").value(false))
                .andExpect(jsonPath("$.instruments[0].exchange").value("BSE"));
    }

    @Test
    void futureCatalogueImportCannotMutateHistoricalExport() {
        InstrumentImportSummary first = importAt(FIRST_MASTER, FIRST_AVAILABLE);
        InstrumentCatalogueResearchExport before = researchService.exportCashCatalogue(
                Instant.parse("2026-11-01T12:00:00Z"), 100);

        InstrumentImportSummary second = importAt(SECOND_MASTER, SECOND_AVAILABLE);
        InstrumentCatalogueResearchExport after = researchService.exportCashCatalogue(
                Instant.parse("2026-11-01T12:00:00Z"), 100);
        InstrumentCatalogueResearchExport current = researchService.exportCashCatalogue(
                Instant.parse("2026-11-02T10:30:00Z"), 100);

        assertThat(after.catalogueVersion()).isEqualTo(first.catalogueVersion());
        assertThat(after.catalogueFingerprint()).isEqualTo(before.catalogueFingerprint());
        assertThat(after.instruments()).isEqualTo(before.instruments());
        assertThat(current.catalogueVersion()).isEqualTo(second.catalogueVersion());
        assertThat(current.catalogueFingerprint()).isNotEqualTo(before.catalogueFingerprint());
    }

    @Test
    void durableHistorySurvivesOperationalCatalogueRetentionPurge() {
        InstrumentImportSummary first = importAt(FIRST_MASTER, FIRST_AVAILABLE);
        InstrumentCatalogueResearchExport original = researchService.exportCashCatalogue(
                Instant.parse("2026-11-01T12:00:00Z"), 100);
        importAt(SECOND_MASTER, SECOND_AVAILABLE);
        importAt(FIRST_MASTER, THIRD_AVAILABLE);
        importAt(SECOND_MASTER, FOURTH_AVAILABLE);

        Integer operationalRows = jdbc.queryForObject(
                "SELECT count(*) FROM instrument_master WHERE catalogue_version = ?",
                Integer.class, first.catalogueVersion());
        Integer historicalRows = jdbc.queryForObject(
                "SELECT count(*) FROM instrument_cash_catalogue_history WHERE catalogue_version = ?",
                Integer.class, first.catalogueVersion());
        InstrumentCatalogueResearchExport replayed = researchService.exportCashCatalogue(
                Instant.parse("2026-11-01T12:00:00Z"), 100);

        assertThat(operationalRows).isZero();
        assertThat(historicalRows).isEqualTo(2);
        assertThat(replayed.catalogueVersion()).isEqualTo(first.catalogueVersion());
        assertThat(replayed.catalogueFingerprint()).isEqualTo(original.catalogueFingerprint());
        assertThat(replayed.instruments()).isEqualTo(original.instruments());
    }

    @Test
    void failsClosedWhenHistoricalCashEvidenceIsActuallyMissing() {
        InstrumentImportSummary first = importAt(FIRST_MASTER, FIRST_AVAILABLE);
        jdbc.update("DELETE FROM instrument_cash_catalogue_history WHERE catalogue_version = ?", first.catalogueVersion());

        assertThatThrownBy(() -> researchService.exportCashCatalogue(
                Instant.parse("2026-11-01T12:00:00Z"), 100))
                .isInstanceOf(InstrumentMasterException.class)
                .hasMessageContaining("not retained");
    }

    @Test
    void failsClosedBeforeAnyCatalogueWasAvailableAndOnSilentTruncation() {
        assertThatThrownBy(() -> researchService.exportCashCatalogue(
                Instant.parse("2026-10-01T12:00:00Z"), 100))
                .isInstanceOf(InstrumentMasterException.class)
                .hasMessageContaining("No successfully published instrument catalogue");

        importAt(FIRST_MASTER, FIRST_AVAILABLE);
        assertThatThrownBy(() -> researchService.exportCashCatalogue(
                Instant.parse("2026-11-01T12:00:00Z"), 1))
                .isInstanceOf(InstrumentMasterException.class)
                .hasMessageContaining("exceeds the requested maxRows bound");
    }

    @Test
    void researchEndpointRequiresAuthenticationAndExplicitAsOf() throws Exception {
        mvc.perform(get(BASE).param("asOf", "2026-11-01T12:00:00Z"))
                .andExpect(status().isUnauthorized());

        mvc.perform(get(BASE).header("X-API-Key", API_KEY))
                .andExpect(status().isBadRequest());
    }

    private InstrumentImportSummary importAt(String payload, Instant completedAt) {
        InstrumentImportSummary summary = instrumentService.importSource(payload.getBytes(StandardCharsets.UTF_8));
        assertThat(summary.status()).isEqualTo("SUCCEEDED");
        jdbc.update("UPDATE instrument_import_run SET completed_at = ? WHERE id = ?",
                OffsetDateTime.ofInstant(completedAt, ZoneOffset.UTC), summary.catalogueVersion());
        return summary;
    }
}
