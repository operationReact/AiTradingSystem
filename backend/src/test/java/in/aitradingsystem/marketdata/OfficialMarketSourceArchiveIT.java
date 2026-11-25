package in.aitradingsystem.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.aitradingsystem.support.AbstractPostgresIT;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class OfficialMarketSourceArchiveIT extends AbstractPostgresIT {
    @Autowired OfficialMarketSourceArchiveService service;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM official_market_source_snapshot");
    }

    @Test
    void identicalPayloadRetainsItsFirstObservedIdentity() {
        byte[] payload = "Date,Close\n02-Nov-2026,14.0\n".getBytes(StandardCharsets.UTF_8);
        Instant firstObserved = Instant.parse("2026-11-02T10:35:00Z");

        OfficialMarketSourceSnapshot first = service.archive(
                OfficialMarketSourceFamily.INDIA_VIX_HISTORY,
                null,
                "https://www.nseindia.com/api/historicalOR/vixhistory?from=02-11-2026&to=02-11-2026",
                firstObserved,
                "application/json; charset=utf-8",
                payload);
        OfficialMarketSourceSnapshot duplicate = service.archive(
                OfficialMarketSourceFamily.INDIA_VIX_HISTORY,
                null,
                "https://www.nseindia.com/api/historicalOR/vixhistory?from=02-11-2026&to=02-11-2026",
                firstObserved.plusSeconds(600),
                "application/json",
                payload);

        assertThat(duplicate.snapshotId()).isEqualTo(first.snapshotId());
        assertThat(duplicate.observedAt()).isEqualTo(firstObserved);
        assertThat(duplicate.payloadSha256()).isEqualTo(first.payloadSha256());
        assertThat(duplicate.payload()).containsExactly(payload);
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM official_market_source_snapshot", Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void historicalLookupIgnoresFutureSnapshotAndPreservesRawBytes() {
        byte[] historical = "old-csv".getBytes(StandardCharsets.UTF_8);
        byte[] future = "future-csv".getBytes(StandardCharsets.UTF_8);
        Instant historicalObserved = Instant.parse("2026-11-01T12:00:00Z");
        Instant futureObserved = Instant.parse("2026-11-02T13:00:00Z");

        service.archive(
                OfficialMarketSourceFamily.NIFTY50_CONSTITUENTS,
                null,
                "https://www.niftyindices.com/IndexConstituent/ind_nifty50list.csv",
                historicalObserved,
                "text/csv",
                historical);
        service.archive(
                OfficialMarketSourceFamily.NIFTY50_CONSTITUENTS,
                null,
                "https://www.niftyindices.com/IndexConstituent/ind_nifty50list.csv",
                futureObserved,
                "text/csv",
                future);

        OfficialMarketSourceSnapshot selected = service.requireLatestAt(
                OfficialMarketSourceFamily.NIFTY50_CONSTITUENTS,
                null,
                Instant.parse("2026-11-02T12:00:00Z"));

        assertThat(selected.observedAt()).isEqualTo(historicalObserved);
        assertThat(selected.payload()).containsExactly(historical);
        assertThat(selected.byteCount()).isEqualTo(historical.length);
    }

    @Test
    void sectorVariantsRemainIndependentPointInTimeStreams() {
        Instant observed = Instant.parse("2026-11-02T10:35:00Z");
        service.archive(
                OfficialMarketSourceFamily.SECTOR_INDEX_HISTORY,
                "NIFTY BANK",
                "https://www.nseindia.com/reports-indices-historical-index-data",
                observed,
                "text/csv",
                "bank".getBytes(StandardCharsets.UTF_8));
        service.archive(
                OfficialMarketSourceFamily.SECTOR_INDEX_HISTORY,
                "NIFTY IT",
                "https://www.nseindia.com/reports-indices-historical-index-data",
                observed,
                "text/csv",
                "it".getBytes(StandardCharsets.UTF_8));

        assertThat(service.requireLatestAt(
                OfficialMarketSourceFamily.SECTOR_INDEX_HISTORY,
                "NIFTY_BANK",
                observed).payload()).containsExactly("bank".getBytes(StandardCharsets.UTF_8));
        assertThat(service.requireLatestAt(
                OfficialMarketSourceFamily.SECTOR_INDEX_HISTORY,
                "NIFTY_IT",
                observed).payload()).containsExactly("it".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void unsafeSourceMetadataFailsBeforePersistence() {
        Instant observed = Instant.parse("2026-11-02T10:35:00Z");

        assertThatThrownBy(() -> service.archive(
                OfficialMarketSourceFamily.NIFTY50_HISTORY,
                null,
                "http://www.nseindia.com/reports-indices-historical-index-data",
                observed,
                "text/csv",
                new byte[] {1}))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("allowlisted HTTPS NSE host");
        assertThatThrownBy(() -> service.archive(
                OfficialMarketSourceFamily.NIFTY50_HISTORY,
                "NIFTY_BANK",
                "https://www.nseindia.com/reports-indices-historical-index-data",
                observed,
                "text/csv",
                new byte[] {1}))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("does not accept a source variant");
        assertThatThrownBy(() -> service.archive(
                OfficialMarketSourceFamily.SECTOR_INDEX_HISTORY,
                null,
                "https://www.nseindia.com/reports-indices-historical-index-data",
                observed,
                "text/csv",
                new byte[] {1}))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("variant");

        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM official_market_source_snapshot", Integer.class);
        assertThat(count).isZero();
    }

    @Test
    void queryBeforeFirstObservationFailsClosed() {
        service.archive(
                OfficialMarketSourceFamily.NIFTY50_HISTORY,
                null,
                "https://www.nseindia.com/reports-indices-historical-index-data",
                Instant.parse("2026-11-02T10:35:00Z"),
                "text/csv",
                "history".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service.requireLatestAt(
                OfficialMarketSourceFamily.NIFTY50_HISTORY,
                null,
                Instant.parse("2026-11-02T10:34:59Z")))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("requested asOf");
    }
}
