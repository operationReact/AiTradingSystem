package in.aitradingsystem.scanner;

import static org.assertj.core.api.Assertions.assertThat;

import in.aitradingsystem.instrument.Instrument;
import in.aitradingsystem.instrument.InstrumentMasterService;
import in.aitradingsystem.marketdata.UniverseCandleCoverageRepository;
import in.aitradingsystem.support.AbstractPostgresIT;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Angel One cash-equity master rows commonly omit instrumenttype entirely. */
class OfficialCashEquityUniverseIT extends AbstractPostgresIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired InstrumentMasterService instruments;

    @BeforeEach
    void resetCatalogue() {
        jdbc.update("DELETE FROM instrument_cash_catalogue_history");
        jdbc.update("DELETE FROM instrument_master");
        jdbc.update("UPDATE instrument_catalogue_state SET active_version = NULL, activated_at = NULL WHERE singleton_id = 1");
        jdbc.update("DELETE FROM instrument_import_run");
    }

    @Test
    void nullableOfficialCashTypeStillPopulatesFeedResearchHistoryAndCoverage() {
        String official = """
                [
                  {"token":"2885","symbol":"RELIANCE-EQ","name":"RELIANCE","expiry":"","strike":"-1",
                   "lotsize":"1","instrumenttype":"","exch_seg":"nse_cm","tick_size":"5"},
                  {"token":"3045","symbol":"SBIN-EQ","name":"STATE BANK OF INDIA","expiry":"","strike":"-1",
                   "lotsize":"1","instrumenttype":"","exch_seg":"nse_cm","tick_size":"5"},
                  {"token":"8111","symbol":"SBIN-AF","name":"NON EQ SERIES","expiry":"","strike":"-1",
                   "lotsize":"1","instrumenttype":"","exch_seg":"nse_cm","tick_size":"5"}
                ]
                """;
        var imported = instruments.importSource(official.getBytes(StandardCharsets.UTF_8));
        assertThat(imported.status()).isEqualTo("SUCCEEDED");
        assertThat(imported.activeCount()).isEqualTo(3);

        var scans = new ResearchScanRepository(jdbc);
        assertThat(scans.marketFeedPriorityUniverse(3000))
                .extracting(Instrument::tradingSymbol)
                .containsExactly("RELIANCE-EQ", "SBIN-EQ");
        assertThat(scans.activeCashEquityUniverse(3000))
                .extracting(Instrument::tradingSymbol)
                .containsExactly("RELIANCE-EQ", "SBIN-EQ");

        var coverage = new UniverseCandleCoverageRepository(jdbc).coverage(
                Instant.parse("2026-10-27T00:00:00Z"),
                Instant.parse("2026-11-10T00:00:00Z"));
        assertThat(coverage.activeEquities()).isEqualTo(2);
        assertThat(coverage.with64Bars()).isZero();
        assertThat(coverage.under64Bars()).isEqualTo(2);
        assertThat(coverage.withNoBars()).isEqualTo(2);

        Long retained = jdbc.queryForObject("""
                SELECT count(*) FROM instrument_cash_catalogue_history
                 WHERE catalogue_version = ?
                """, Long.class, imported.catalogueVersion());
        assertThat(retained).isEqualTo(2L);
    }
}
