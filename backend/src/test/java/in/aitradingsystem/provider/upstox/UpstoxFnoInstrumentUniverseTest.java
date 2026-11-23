package in.aitradingsystem.provider.upstox;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;

/** Deterministic schema tests. A fixture is NOT proof that the actual BOD was fetched. */
class UpstoxFnoInstrumentUniverseTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 23);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private static ObjectNode row(String key, String underlying,
            String type, LocalDate expiry, int lot, double strike) {
        ObjectNode row = MAPPER.createObjectNode();
        row.put("segment", "NSE_FO");
        row.put("exchange", "NSE");
        row.put("instrument_type", type);
        row.put("instrument_key", key);
        row.put("underlying_key", underlying);
        row.put("underlying_symbol", underlying.startsWith("NSE_INDEX") ?
            "NIFTY" : "RELIANCE");
        row.put("trading_symbol", "VERIFIED_FIXTURE " + type + " " + key);
        row.put("expiry", expiry.atStartOfDay(IST).toInstant().toEpochMilli());
        row.put("lot_size", lot);
        row.put("tick_size", 5.0); // Upstox publishes ticks in PAISE.
        row.put("freeze_quantity", 100000.0);
        row.put("strike_price", strike);
        row.put("weekly", type.equals("CE") || type.equals("PE"));
        return row;
    }

    private static byte[] json(ObjectNode... rows) {
        ArrayNode list = MAPPER.createArrayNode();
        for (ObjectNode row : rows) list.add(row);
        return list.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static UpstoxFnoInstrumentUniverse.Catalog parse(byte[] body)
            throws IOException {
        return UpstoxFnoInstrumentUniverse.parse(
            new ByteArrayInputStream(body), TODAY);
    }

    @Test
    void fullCatalogueContainsAllListedIndexStockCallsPutsAndFutures() throws Exception {
        LocalDate expiry = LocalDate.of(2026, 12, 1);
        var index = "NSE_INDEX|Nifty 50";
        var stock = "NSE_EQ|INE002A01018";
        var catalog = parse(json(
            row("NSE_FO|101", index, "CE", expiry, 65, 26000),
            row("NSE_FO|102", index, "PE", expiry, 65, 26000),
            row("NSE_FO|103", index, "FUT", expiry, 65, 0),
            row("NSE_FO|104", stock, "CE", expiry, 250, 1400),
            row("NSE_FO|105", stock, "PE", expiry, 250, 1400),
            row("NSE_FO|106", stock, "FUT", expiry, 250, 0),
            row("NSE_FO|expired", index, "CE", TODAY.minusDays(1), 65, 25000)));
        assertEquals(6, catalog.size());
        assertEquals(2, catalog.futures());
        assertEquals(2, catalog.calls());
        assertEquals(2, catalog.puts());
        assertEquals(2, catalog.underlyings());
        assertTrue(catalog.byInstrumentKey().containsKey("NSE_FO|105"));
        assertEquals("NSE_FO|101",
            catalog.query(index, expiry, "CE", 0, 50).contracts()
                .getFirst().instrumentKey());
        assertEquals(new BigDecimal("0.05"),
            catalog.byInstrumentKey().get("NSE_FO|101").tickSizeRupees());
        assertEquals(100000, catalog.byInstrumentKey().get("NSE_FO|101")
            .freezeQuantity().intValueExact());
        assertTrue(catalog.byInstrumentKey().get("NSE_FO|101").weekly());
    }

    @Test
    void listIsPaginatedAndFilteringNeverInventsMissingExpiryOrSide() throws Exception {
        var expiry = LocalDate.of(2026, 12, 1);
        var key = "NSE_INDEX|Nifty 50";
        var catalog = parse(json(
            row("NSE_FO|101", key, "CE", expiry, 65, 26000),
            row("NSE_FO|102", key, "PE", expiry, 65, 26000),
            row("NSE_FO|103", key, "FUT", expiry, 65, 0)));
        var first = catalog.query(key, null, null, 0, 2);
        var second = catalog.query(key, null, null, 1, 2);
        assertEquals(3, first.total());
        assertEquals(2, first.contracts().size());
        assertEquals(1, second.contracts().size());
        assertEquals(0, catalog.query(key, expiry.plusDays(7),
            "CE", 0, 100).total());
        assertThrows(IllegalArgumentException.class,
            () -> catalog.query(null, null, null, 0, 101));
        assertThrows(IllegalArgumentException.class,
            () -> catalog.query(null, null, null, -1, 1));
        assertThrows(IllegalArgumentException.class,
            () -> catalog.query(null, null, "ALL", 0, 10));
    }

    @Test
    void localSymbolSearchCoversIndexAndStockWithoutProviderHttpRequests() throws Exception {
        var expiry = LocalDate.of(2026, 12, 1);
        var catalog = parse(json(
            row("NSE_FO|101", "NSE_INDEX|Nifty 50", "CE", expiry, 65, 26000),
            row("NSE_FO|102", "NSE_INDEX|Nifty 50", "PE", expiry, 65, 26000),
            row("NSE_FO|103", "NSE_EQ|INE002A01018", "FUT", expiry, 250, 0)));
        assertEquals(2, catalog.search("nifty", null, 0, 20).total());
        assertEquals("NSE_FO|103",
            catalog.search("reliance", "FUT", 0, 20)
                .contracts().getFirst().instrumentKey());
        assertEquals(1, catalog.search("nifty", "CE", 0, 20).total());
        assertEquals(0, catalog.search("reliance", "CE", 0, 20).total());
        assertThrows(IllegalArgumentException.class,
            () -> catalog.search("NIFTY/../", null, 0, 50));
        assertThrows(IllegalArgumentException.class,
            () -> catalog.search("NIFTY", null, 0, 101));
    }

    @Test
    void gzipAndPlainProduceSameCountAndContractKeys() throws Exception {
        byte[] body = json(row("NSE_FO|101", "NSE_INDEX|Nifty 50",
            "FUT", LocalDate.of(2026, 12, 1), 65, 0));
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (var gz = new GZIPOutputStream(buf)) { gz.write(body); }
        var plain = parse(body);
        var compressed = parse(buf.toByteArray());
        assertEquals(plain, compressed);
    }

    @Test
    void duplicateKeyAndConflictingUnderlyingExpirySideStrikeRejectEntireMaster() {
        var expiry = LocalDate.of(2026, 12, 1);
        var index = "NSE_INDEX|Nifty 50";
        var call = row("NSE_FO|101", index, "CE", expiry, 65, 26000);
        assertThrows(UpstoxFuturesInstrumentMaster.MalformedInstrumentMasterException.class,
            () -> parse(json(call, call.deepCopy())));
        var anotherKeySameStrike = row("NSE_FO|999", index, "CE", expiry, 65, 26000);
        assertThrows(UpstoxFuturesInstrumentMaster.MalformedInstrumentMasterException.class,
            () -> parse(json(call, anotherKeySameStrike)));
    }

    @Test
    void invalidLotTickExpiryStrikeOrExchangeCannotProduceTradingUniverse() {
        var expiry = LocalDate.of(2026, 12, 1);
        String index = "NSE_INDEX|Nifty 50";
        var negativeTick = row("NSE_FO|101", index, "CE", expiry, 65, 26000);
        negativeTick.put("tick_size", -5);
        assertThrows(IOException.class, () -> parse(json(negativeTick)));
        var wrongExchange = row("NSE_FO|101", index, "PE", expiry, 65, 26000);
        wrongExchange.put("exchange", "BSE");
        assertThrows(IOException.class, () -> parse(json(wrongExchange)));
        var badStrike = row("NSE_FO|101", index, "PE", expiry, 65, 0);
        assertThrows(IOException.class, () -> parse(json(badStrike)));
        var badLot = row("NSE_FO|101", index, "FUT", expiry, 0, 0);
        assertThrows(IOException.class, () -> parse(json(badLot)));
        var badExpiry = row("NSE_FO|101", index, "FUT", expiry, 65, 0);
        badExpiry.put("expiry", 0);
        assertThrows(IOException.class, () -> parse(json(badExpiry)));
    }

    @Test
    void unrelatedCashAndBseContractsDoNotBecomeNseFno() throws Exception {
        var expiry = LocalDate.of(2026, 12, 1);
        var nse = row("NSE_FO|101", "NSE_INDEX|Nifty 50", "FUT", expiry, 65, 0);
        var bse = row("BSE_FO|101", "NSE_INDEX|Nifty 50", "CE", expiry, 65, 27000);
        bse.put("segment", "BSE_FO");
        bse.put("exchange", "BSE");
        var cash = row("NSE_EQ|101", "NSE_INDEX|Nifty 50", "EQ", expiry, 1, 0);
        cash.put("segment", "NSE_EQ");
        var result = parse(json(nse, bse, cash));
        assertEquals(1, result.size());
        assertEquals(1, result.futures());
    }
}
