package in.aitradingsystem.tradingrooms;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import in.aitradingsystem.provider.upstox.FuturesInstrumentMasterFetcher;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;

/** Uses the SYNTHETIC instrument-master fixture: proves registry logic only, not provider data. */
class FnoFuturesContractRegistryTest {
    private static final Instant NOW = Instant.parse("2026-11-20T05:00:00Z");

    private static byte[] fixtureGz() throws IOException {
        byte[] plain = Files.readAllBytes(Path.of("src/test/resources/fno/synthetic-futures-instrument-master.json"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) { gz.write(plain); }
        return out.toByteArray();
    }

    private static FnoFuturesContractRegistry registry(FuturesInstrumentMasterFetcher fetcher, boolean verified) {
        return new FnoFuturesContractRegistry(fetcher, Clock.fixed(NOW, ZoneId.of("UTC")), verified);
    }

    private static byte[] completeSyntheticUniverse() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var rows = (com.fasterxml.jackson.databind.node.ArrayNode)
            mapper.readTree(Files.readAllBytes(
                Path.of("src/test/resources/fno/synthetic-futures-instrument-master.json")));
        for (var row : rows) {
            if ("NSE_FO".equals(row.path("segment").asText())) {
                var mutable = (com.fasterxml.jackson.databind.node.ObjectNode) row;
                mutable.put("tick_size", 5.0);
                mutable.put("freeze_quantity", 100000.0);
            }
        }
        var put = ((com.fasterxml.jackson.databind.node.ObjectNode) rows.get(3)).deepCopy();
        put.put("instrument_key", "NSE_FO|SYN2002");
        put.put("instrument_type", "PE");
        put.put("trading_symbol", "NIFTY 25000 PE");
        rows.add(put);
        return mapper.writeValueAsBytes(rows);
    }

    @Test
    void oneSourceFetchProvidesBothFuturesAndCompleteCallPutUniverse() throws Exception {
        var fetcher = mock(FuturesInstrumentMasterFetcher.class);
        when(fetcher.fetch()).thenReturn(new FuturesInstrumentMasterFetcher.Fetched(
            completeSyntheticUniverse(), "verified-digest"));
        var reg = registry(fetcher, true);
        reg.refresh();
        verify(fetcher, times(1)).fetch();
        assertEquals("LOADED", reg.status().state());
        var status = reg.universeStatus();
        assertEquals("LOADED", status.state());
        assertEquals("verified-digest", status.sha256());
        assertEquals(4, status.contracts());
        assertEquals(2, status.futures());
        assertEquals(1, status.calls());
        assertEquals(1, status.puts());
        assertEquals(1, status.underlyings());
        var call = reg.universe("NSE_INDEX|Nifty 50", null, "CE", 0, 50);
        assertEquals(1, call.totalMatches());
        assertEquals("NSE_FO|SYN2001", call.contracts().getFirst().instrumentKey());
        assertTrue(reg.universe("NSE_INDEX|Nifty 50", null, "PE", 0, 50)
            .contracts().stream().anyMatch(c -> c.instrumentKey().equals("NSE_FO|SYN2002")));
    }

    @Test
    void universeIsNotLoadedWhenSourcePermissionIsNotVerified() throws Exception {
        var fetcher = mock(FuturesInstrumentMasterFetcher.class);
        var reg = registry(fetcher, false);
        reg.refresh();
        assertEquals("DISCOVERY_NOT_VERIFIED", reg.universeStatus().state());
        assertEquals(0, reg.universe(null, null, null, 0, 50).totalMatches());
        verifyNoInteractions(fetcher);
    }

    @Test
    void malformedOptionMetadataDoesNotCorruptPreviouslyVerifiedFuturePairs() throws Exception {
        var fetcher = mock(FuturesInstrumentMasterFetcher.class);
        when(fetcher.fetch()).thenReturn(new FuturesInstrumentMasterFetcher.Fetched(
            fixtureGz(), "fut-only"));
        var reg = registry(fetcher, true);
        reg.refresh();
        assertEquals("LOADED", reg.status().state());
        assertEquals("NOT_LOADED", reg.universeStatus().state());
        assertEquals("FNO_UNIVERSE_UNVERIFIED_SCHEMA", reg.universeStatus().lastFailure());
        assertEquals(0, reg.universe(null, null, null, 0, 50).totalMatches());
        assertTrue(reg.nearFar("NSE_INDEX|Nifty 50",
            LocalDate.of(2026, 11, 20)).isPresent());
    }

    @Test
    void independentlyVerifiedDisplayRightsAlsoPermitListedIdentityDiscovery()
            throws Exception {
        var fetcher = mock(FuturesInstrumentMasterFetcher.class);
        when(fetcher.fetch()).thenReturn(new FuturesInstrumentMasterFetcher.Fetched(
            completeSyntheticUniverse(), "display-approved"));
        // Even when the separate research-only discovery flag is false,
        // existing verified F&O display rights include contract identities.
        var approved = new FnoFuturesContractRegistry(fetcher, false, true);
        approved.refresh();
        assertEquals("LOADED", approved.universeStatus().state());
        assertEquals("display-approved", approved.universeStatus().sha256());

        var deniedFetcher = mock(FuturesInstrumentMasterFetcher.class);
        var denied = new FnoFuturesContractRegistry(deniedFetcher, false, false);
        denied.refresh();
        assertEquals("DISCOVERY_NOT_VERIFIED", denied.universeStatus().state());
        verifyNoInteractions(deniedFetcher);
    }

    @Test
    void startupBootstrapLoadsOnceWithoutWaitingForNextMorning() throws Exception {
        var fetcher = mock(FuturesInstrumentMasterFetcher.class);
        when(fetcher.fetch()).thenReturn(new FuturesInstrumentMasterFetcher.Fetched(
            completeSyntheticUniverse(), "bootstrap-sha"));
        var reg = registry(fetcher, true);
        reg.bootstrapIfNeeded();
        assertEquals("LOADED", reg.universeStatus().state());
        assertEquals("bootstrap-sha", reg.universeStatus().sha256());
        reg.bootstrapIfNeeded();
        verify(fetcher, times(1)).fetch();
    }

    @Test
    void refreshIsNoOpWithoutVerifiedDiscoveryPermission() throws Exception {
        FuturesInstrumentMasterFetcher fetcher = mock(FuturesInstrumentMasterFetcher.class);
        var reg = registry(fetcher, false);
        reg.refresh();
        verifyNoInteractions(fetcher);
        assertEquals("DISCOVERY_NOT_VERIFIED", reg.status().state());
        assertTrue(reg.nearFar("NSE_INDEX|Nifty 50", LocalDate.of(2026, 11, 20)).isEmpty());
    }

    @Test
    void loadsSnapshotAndSelectsNearFarWithEqualLots() throws Exception {
        FuturesInstrumentMasterFetcher fetcher = mock(FuturesInstrumentMasterFetcher.class);
        when(fetcher.fetch()).thenReturn(new FuturesInstrumentMasterFetcher.Fetched(fixtureGz(), "abc123"));
        var reg = registry(fetcher, true);
        reg.refresh();
        var status = reg.status();
        assertEquals("LOADED", status.state());
        assertEquals("abc123", status.sha256());
        assertEquals(2, status.currentFutRows());
        var pair = reg.nearFar("NSE_INDEX|Nifty 50", LocalDate.of(2026, 11, 20)).orElseThrow();
        assertEquals("NSE_FO|SYN1001", pair.near().instrumentKey());
        assertEquals("NSE_FO|SYN1002", pair.far().instrumentKey());
        assertEquals(75, pair.near().lotSize());
        assertEquals(pair.near().lotSize(), pair.far().lotSize());
    }

    @Test
    void nearLegWithinMinimumDaysIsNotSelected() throws Exception {
        FuturesInstrumentMasterFetcher fetcher = mock(FuturesInstrumentMasterFetcher.class);
        when(fetcher.fetch()).thenReturn(new FuturesInstrumentMasterFetcher.Fetched(fixtureGz(), "abc123"));
        var reg = registry(fetcher, true);
        reg.refresh();
        // On 2026-12-27 only the Jan contract is >= 3 days away, so no near/far pair exists.
        assertTrue(reg.nearFar("NSE_INDEX|Nifty 50", LocalDate.of(2026, 12, 27)).isEmpty());
    }

    @Test
    void failedRefreshKeepsLastSnapshotAndReportsStaleCode() throws Exception {
        FuturesInstrumentMasterFetcher fetcher = mock(FuturesInstrumentMasterFetcher.class);
        when(fetcher.fetch()).thenReturn(new FuturesInstrumentMasterFetcher.Fetched(fixtureGz(), "abc123"))
            .thenThrow(new IOException("INSTRUMENT_MASTER_HTTP_503"));
        var reg = registry(fetcher, true);
        reg.refresh();
        reg.refresh();
        var status = reg.status();
        assertEquals("STALE_LAST_REFRESH_FAILED", status.state());
        assertEquals("INSTRUMENT_MASTER_FETCH_FAILED", status.lastFailure());
        assertTrue(reg.nearFar("NSE_INDEX|Nifty 50", LocalDate.of(2026, 11, 20)).isPresent());
    }

    @Test
    void malformedMasterIsRejectedAndNeverReplacesSnapshot() throws Exception {
        FuturesInstrumentMasterFetcher fetcher = mock(FuturesInstrumentMasterFetcher.class);
        when(fetcher.fetch()).thenReturn(new FuturesInstrumentMasterFetcher.Fetched(
            "{\"not\":\"array\"}".getBytes(), "bad"));
        var reg = registry(fetcher, true);
        reg.refresh();
        assertEquals("NOT_LOADED", reg.status().state());
        assertEquals("INSTRUMENT_MASTER_MALFORMED", reg.status().lastFailure());
        assertTrue(reg.nearFar("NSE_INDEX|Nifty 50", LocalDate.of(2026, 11, 20)).isEmpty());
    }
}
