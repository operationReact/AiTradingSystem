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
