package in.aitradingsystem.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class OfficialMarketSourceIngestionServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-02T11:00:00Z");
    private final OfficialMarketSourceArchiveService archive = mock(OfficialMarketSourceArchiveService.class);
    private final OfficialMarketSourceTransport transport = mock(OfficialMarketSourceTransport.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void buildsBoundedCausalRequestsForConfiguredOfficialFamilies() {
        OfficialMarketSourceIngestionService service = service(
                "https://www.nseindia.com/api/historicalOR/indicesHistory",
                "https://www.nseindia.com/api/historicalOR/vixhistory",
                "NIFTY BANK,NIFTY IT");

        List<OfficialMarketSourceIngestionService.SourceRequest> requests = service.scheduledRequests();

        assertThat(requests).hasSize(10);
        assertThat(requests.get(0).family()).isEqualTo(OfficialMarketSourceFamily.NIFTY50_CONSTITUENTS);
        assertThat(requests.get(0).sourceUri().toString())
                .isEqualTo("https://www.niftyindices.com/IndexConstituent/ind_nifty50list.csv");
        assertThat(requests.get(0).bootstrapUri()).isNull();

        assertThat(requests.get(1).family()).isEqualTo(OfficialMarketSourceFamily.NIFTY500_CONSTITUENTS);
        assertThat(requests.get(1).sourceUri().toString())
                .isEqualTo("https://www.niftyindices.com/IndexConstituent/ind_nifty500list.csv");
        assertThat(requests.get(1).bootstrapUri()).isNull();

        assertThat(requests.subList(2, 6))
                .extracting(OfficialMarketSourceIngestionService.SourceRequest::family)
                .containsExactly(
                        OfficialMarketSourceFamily.NIFTY100_CONSTITUENTS,
                        OfficialMarketSourceFamily.NIFTY_MIDCAP150_CONSTITUENTS,
                        OfficialMarketSourceFamily.NIFTY_SMALLCAP250_CONSTITUENTS,
                        OfficialMarketSourceFamily.NIFTY_MICROCAP250_CONSTITUENTS);
        assertThat(requests.get(5).sourceUri().toString())
                .isEqualTo("https://www.niftyindices.com/IndexConstituent/ind_niftymicrocap250_list.csv");

        OfficialMarketSourceIngestionService.SourceRequest nifty = requests.get(6);
        assertThat(nifty.family()).isEqualTo(OfficialMarketSourceFamily.NIFTY50_HISTORY);
        assertThat(nifty.sourceUri().getRawQuery())
                .contains("indexType=NIFTY+50", "from=26-10-2026", "to=02-11-2026", "csv=true");
        assertThat(nifty.bootstrapUri()).isEqualTo(URI.create("https://www.nseindia.com/"));

        assertThat(requests.stream()
                .filter(request -> request.family() == OfficialMarketSourceFamily.SECTOR_INDEX_HISTORY)
                .map(OfficialMarketSourceIngestionService.SourceRequest::variant))
                .containsExactly("NIFTY_BANK", "NIFTY_IT");
        assertThat(requests.get(9).family()).isEqualTo(OfficialMarketSourceFamily.INDIA_VIX_HISTORY);
    }

    @Test
    void historicalFamiliesRemainDisabledWhenEndpointsAreNotConfigured() {
        OfficialMarketSourceIngestionService service = service("", "", "");

        List<OfficialMarketSourceIngestionService.SourceRequest> requests = service.scheduledRequests();

        assertThat(requests)
                .extracting(OfficialMarketSourceIngestionService.SourceRequest::family)
                .containsExactly(
                        OfficialMarketSourceFamily.NIFTY50_CONSTITUENTS,
                        OfficialMarketSourceFamily.NIFTY500_CONSTITUENTS,
                        OfficialMarketSourceFamily.NIFTY100_CONSTITUENTS,
                        OfficialMarketSourceFamily.NIFTY_MIDCAP150_CONSTITUENTS,
                        OfficialMarketSourceFamily.NIFTY_SMALLCAP250_CONSTITUENTS,
                        OfficialMarketSourceFamily.NIFTY_MICROCAP250_CONSTITUENTS);
    }

    @Test
    void fetchedPayloadIsArchivedWithFetchTimeAndTransportContentType() {
        OfficialMarketSourceIngestionService service = service("", "", "");
        OfficialMarketSourceIngestionService.SourceRequest request = service.scheduledRequests().getFirst();
        byte[] body = "Company Name,Industry,Symbol,Series,ISIN Code\n".getBytes();
        when(transport.get(request.sourceUri(), null, OfficialMarketSourceFamily.NIFTY50_CONSTITUENTS.maxBytes()))
                .thenReturn(new OfficialMarketSourceTransport.Response("text/csv; charset=utf-8", body));

        service.ingest(request);

        verify(archive).archive(
                eq(OfficialMarketSourceFamily.NIFTY50_CONSTITUENTS),
                eq(""),
                eq(request.sourceUri().toString()),
                eq(NOW),
                eq("text/csv; charset=utf-8"),
                argThat(payload -> java.util.Arrays.equals(payload, body)));
    }

    @Test
    void nifty500PayloadUsesItsOwnFamilyLimitAndArchiveLineage() {
        OfficialMarketSourceIngestionService service = service("", "", "");
        OfficialMarketSourceIngestionService.SourceRequest request = service.scheduledRequests().get(1);
        byte[] body = "Company Name,Industry,Symbol,Series,ISIN Code\n".getBytes();
        when(transport.get(request.sourceUri(), null, OfficialMarketSourceFamily.NIFTY500_CONSTITUENTS.maxBytes()))
                .thenReturn(new OfficialMarketSourceTransport.Response("application/octet-stream", body));

        service.ingest(request);

        verify(archive).archive(
                eq(OfficialMarketSourceFamily.NIFTY500_CONSTITUENTS),
                eq(""),
                eq("https://www.niftyindices.com/IndexConstituent/ind_nifty500list.csv"),
                eq(NOW),
                eq("application/octet-stream"),
                argThat(payload -> java.util.Arrays.equals(payload, body)));
    }

    @Test
    void unsupportedSectorConfigurationFailsClosed() {
        assertThatThrownBy(() -> service("", "", "NIFTY_FAKE_SECTOR"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported official sector index");
    }

    private OfficialMarketSourceIngestionService service(
            String indexEndpoint,
            String vixEndpoint,
            String sectorIndices) {
        return new OfficialMarketSourceIngestionService(
                archive,
                transport,
                clock,
                "https://www.niftyindices.com/IndexConstituent/ind_nifty50list.csv",
                "https://www.niftyindices.com/IndexConstituent/ind_nifty500list.csv",
                indexEndpoint,
                vixEndpoint,
                "https://www.nseindia.com/",
                7,
                sectorIndices);
    }
}
