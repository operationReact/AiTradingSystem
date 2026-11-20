package in.aitradingsystem.provider.upstox;

import static org.junit.jupiter.api.Assertions.*;

import in.aitradingsystem.provider.upstox.UpstoxFuturesInstrumentMaster.FuturesContract;
import in.aitradingsystem.provider.upstox.UpstoxFuturesInstrumentMaster.MalformedInstrumentMasterException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;

/**
 * Uses a SYNTHETIC instrument-master fixture (src/test/resources/fno/synthetic-futures-instrument-master.json).
 * It proves parser behaviour only. It is not provider data and not evidence of the live Upstox schema.
 */
class UpstoxFuturesInstrumentMasterTest {
    private static final LocalDate AS_OF = LocalDate.of(2026, 11, 20);

    private static InputStream fixture() {
        InputStream in = UpstoxFuturesInstrumentMasterTest.class.getResourceAsStream(
            "/fno/synthetic-futures-instrument-master.json");
        assertNotNull(in);
        return in;
    }

    private static InputStream json(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void keepsOnlyNseFoFuturesWithCurrentOrFutureExpiry() throws IOException {
        List<FuturesContract> contracts = UpstoxFuturesInstrumentMaster.parse(fixture(), AS_OF);
        assertEquals(2, contracts.size());
        assertEquals("NSE_FO|SYN1001", contracts.get(0).instrumentKey());
        assertEquals(LocalDate.of(2026, 12, 29), contracts.get(0).expiry());
        assertEquals(75, contracts.get(0).lotSize());
        assertEquals("NSE_FO|SYN1002", contracts.get(1).instrumentKey());
        assertEquals(LocalDate.of(2027, 1, 26), contracts.get(1).expiry());
        // Options, equity rows and the expired FUT are excluded.
        assertTrue(contracts.stream().noneMatch(c -> c.instrumentKey().equals("NSE_FO|SYN2001")));
        assertTrue(contracts.stream().noneMatch(c -> c.instrumentKey().equals("NSE_FO|SYN0999")));
    }

    @Test
    void gzipAndPlainInputProduceIdenticalResults() throws IOException {
        byte[] plain = fixture().readAllBytes();
        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(gz)) { out.write(plain); }
        assertEquals(UpstoxFuturesInstrumentMaster.parse(new ByteArrayInputStream(plain), AS_OF),
            UpstoxFuturesInstrumentMaster.parse(new ByteArrayInputStream(gz.toByteArray()), AS_OF));
    }

    @Test
    void indexGroupsByUnderlyingAndExpiry() throws IOException {
        var index = UpstoxFuturesInstrumentMaster.index(
            UpstoxFuturesInstrumentMaster.parse(fixture(), AS_OF));
        assertEquals(1, index.size());
        assertEquals(2, index.get("NSE_INDEX|Nifty 50").size());
    }

    @Test
    void duplicateUnderlyingExpiryFailsClosed() throws IOException {
        var contracts = UpstoxFuturesInstrumentMaster.parse(fixture(), AS_OF);
        var dup = List.of(contracts.get(0), new FuturesContract("NSE_FO|DUP", contracts.get(0).underlyingKey(),
            "NIFTY", "NIFTY DUP", contracts.get(0).expiry(), 75));
        assertThrows(MalformedInstrumentMasterException.class,
            () -> UpstoxFuturesInstrumentMaster.index(dup));
    }

    @Test
    void malformedFutureRowRejectsWholeFile() {
        String bad = "[{\"segment\":\"NSE_FO\",\"instrument_type\":\"FUT\",\"instrument_key\":\"NSE_FO|X\","
            + "\"underlying_key\":\"NSE_INDEX|Nifty 50\",\"underlying_symbol\":\"NIFTY\","
            + "\"trading_symbol\":\"NIFTY FUT\",\"expiry\":1798147200000,\"lot_size\":0}]";
        assertThrows(MalformedInstrumentMasterException.class,
            () -> UpstoxFuturesInstrumentMaster.parse(json(bad), AS_OF));
    }

    @Test
    void badInstrumentKeyPrefixRejectsWholeFile() {
        String bad = "[{\"segment\":\"NSE_FO\",\"instrument_type\":\"FUT\",\"instrument_key\":\"BSE_FO|X\","
            + "\"underlying_key\":\"NSE_INDEX|Nifty 50\",\"underlying_symbol\":\"NIFTY\","
            + "\"trading_symbol\":\"NIFTY FUT\",\"expiry\":1798147200000,\"lot_size\":75}]";
        assertThrows(MalformedInstrumentMasterException.class,
            () -> UpstoxFuturesInstrumentMaster.parse(json(bad), AS_OF));
    }

    @Test
    void nonArrayDocumentIsRejected() {
        assertThrows(MalformedInstrumentMasterException.class,
            () -> UpstoxFuturesInstrumentMaster.parse(json("{\"data\":[]}"), AS_OF));
    }
}
