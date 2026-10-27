package in.aitradingsystem.instrument;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import in.aitradingsystem.support.AbstractPostgresIT;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

class InstrumentMasterApiIT extends AbstractPostgresIT {
    @Autowired MockMvc mvc;
    @Autowired InstrumentMasterService service;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void resetInstrumentCatalogue() {
        jdbc.update("DELETE FROM instrument_master");
        jdbc.update("UPDATE instrument_catalogue_state SET active_version = NULL, activated_at = NULL WHERE singleton_id = 1");
        jdbc.update("DELETE FROM instrument_import_run");
    }

    private static final String BASE = "/api/instruments";
    private static final String FIRST_MASTER = """
            [
              {"token":"2885","symbol":"RELIANCE-EQ","name":"RELIANCE","expiry":"","strike":"-1.000000","lotsize":"1","instrumenttype":"EQ","exch_seg":"nse_cm","tick_size":"5.000000"},
              {"token":"3045","symbol":"SBIN-EQ","name":"STATE BANK OF INDIA","expiry":"","strike":"-1.000000","lotsize":"1","instrumenttype":"EQ","exch_seg":"nse_cm","tick_size":"5.000000"},
              {"token":"11128","symbol":"SBIN-AF","name":"STATE BANK OF INDIA","expiry":"","strike":"-1.000000","lotsize":"1","instrumenttype":"EQ","exch_seg":"nse_cm","tick_size":"5.000000"}
            ]
            """;

    @Test
    void searchRequiresOperatorApiKey() throws Exception {
        mvc.perform(get(BASE + "/search").param("q", "REL"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void importPublishesSearchableCatalogueAndStatus() throws Exception {
        var summary = service.importSource(FIRST_MASTER.getBytes(StandardCharsets.UTF_8));

        assertThat(summary.status()).isEqualTo("SUCCEEDED");
        assertThat(summary.activeCount()).isEqualTo(3);
        mvc.perform(get(BASE + "/search")
                .param("q", "RELI")
                .param("exchange", "NSE")
                .param("page", "0")
                .param("size", "10")
                .header("X-API-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].symbolToken").value("2885"))
                .andExpect(jsonPath("$.content[0].tickSize").value(0.05));

        mvc.perform(get(BASE + "/status").header("X-API-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeCount").value(3))
                .andExpect(jsonPath("$.version").value(summary.catalogueVersion().toString()));
    }

    @Test
    void failedIncompleteImportLeavesPreviousCatalogueActive() throws Exception {
        var first = service.importSource(FIRST_MASTER.getBytes(StandardCharsets.UTF_8));
        assertThat(first.status()).isEqualTo("SUCCEEDED");

        String truncated = """
                [{"token":"2885","symbol":"RELIANCE-EQ","name":"RELIANCE","expiry":"","strike":"-1.000000","lotsize":"1","instrumenttype":"EQ","exch_seg":"nse_cm","tick_size":"5.000000"}]
                """;
        var second = service.importSource(truncated.getBytes(StandardCharsets.UTF_8));

        assertThat(second.status()).isEqualTo("FAILED");
        assertThat(second.errorCode()).isEqualTo("INCOMPLETE_CATALOGUE");
        var state = service.catalogueSummary();
        assertThat(state.version()).isEqualTo(first.catalogueVersion());
        assertThat(state.activeCount()).isEqualTo(3);

        mvc.perform(get(BASE + "/search").param("q", "SBIN").header("X-API-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void repeatedIdenticalRowsAreDeduplicatedAndConflictingDuplicatesFail() {
        String repeated = """
                [
                  {"token":"1","symbol":"ABC-EQ","name":"ABC","expiry":"","strike":"-1","lotsize":"1","instrumenttype":"EQ","exch_seg":"nse_cm","tick_size":"5"},
                  {"token":"1","symbol":"ABC-EQ","name":"ABC","expiry":"","strike":"-1.000000","lotsize":"1","instrumenttype":"EQ","exch_seg":"nse_cm","tick_size":"5.000000"}
                ]
                """;
        var okay = service.importSource(repeated.getBytes(StandardCharsets.UTF_8));
        assertThat(okay.status()).isEqualTo("SUCCEEDED");
        assertThat(okay.duplicateCount()).isEqualTo(1);
        assertThat(okay.activeCount()).isEqualTo(1);

        String conflict = """
                [
                  {"token":"2","symbol":"XYZ-EQ","name":"XYZ","expiry":"","strike":"-1","lotsize":"1","instrumenttype":"EQ","exch_seg":"nse_cm","tick_size":"5"},
                  {"token":"2","symbol":"XYZ-EQ","name":"XYZ","expiry":"","strike":"-1","lotsize":"1","instrumenttype":"EQ","exch_seg":"nse_cm","tick_size":"10"}
                ]
                """;
        var bad = service.importSource(conflict.getBytes(StandardCharsets.UTF_8));
        assertThat(bad.status()).isEqualTo("FAILED");
        assertThat(bad.errorCode()).isEqualTo("CONFLICTING_DUPLICATE");
        assertThat(service.catalogueSummary().version()).isEqualTo(okay.catalogueVersion());
    }

    @Test
    void rejectsOversizedPageAndBadQueryAtApiBoundary() throws Exception {
        mvc.perform(get(BASE + "/search").param("size", "1000").header("X-API-Key", API_KEY))
                .andExpect(status().isBadRequest());
        mvc.perform(get(BASE + "/search").param("q", "%").header("X-API-Key", API_KEY))
                .andExpect(status().isBadRequest());
    }
}
