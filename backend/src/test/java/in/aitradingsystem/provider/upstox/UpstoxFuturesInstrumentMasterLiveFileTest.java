package in.aitradingsystem.provider.upstox;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.aitradingsystem.provider.upstox.UpstoxFuturesInstrumentMaster.FuturesContract;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Verifies the parser against the REAL published Upstox instrument master.
 * Runs only when FNO_INSTRUMENT_MASTER_GZ points to a downloaded file (the
 * fno-futures-master-verify workflow sets it). Otherwise it is skipped.
 * Prints contract counts and schema facts with the FNO_MASTER_ prefix. It
 * prints no quotes, no tokens and no account data.
 */
class UpstoxFuturesInstrumentMasterLiveFileTest {
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Test
    void parserMatchesRealInstrumentMasterSchema() throws Exception {
        String env = System.getenv("FNO_INSTRUMENT_MASTER_GZ");
        assumeTrue(env != null && !env.isBlank(), "real instrument master file not provided");
        Path file = Path.of(env);
        assertTrue(Files.isRegularFile(file), "instrument master file missing");
        LocalDate asOf = LocalDate.now(IST);

        // Raw schema facts (field names and counts only).
        JsonNode root;
        try (InputStream in = gzipOrPlain(file)) {
            root = new ObjectMapper().readTree(in);
        }
        assertTrue(root.isArray(), "top-level document is not an array");
        System.out.println("FNO_MASTER_ROWS=" + root.size());
        Set<String> segments = new TreeSet<>();
        Set<String> types = new TreeSet<>();
        Set<String> fut = new TreeSet<>();
        int futRows = 0;
        JsonNode sampleFut = null;
        for (JsonNode row : root) {
            segments.add(row.path("segment").asText(""));
            types.add(row.path("instrument_type").asText(""));
            if ("NSE_FO".equals(row.path("segment").asText("")) && "FUT".equals(row.path("instrument_type").asText(""))) {
                futRows++;
                if (sampleFut == null) sampleFut = row;
            }
        }
        System.out.println("FNO_MASTER_SEGMENTS=" + segments);
        System.out.println("FNO_MASTER_INSTRUMENT_TYPES=" + types);
        System.out.println("FNO_MASTER_NSE_FO_FUT_RAW_ROWS=" + futRows);
        if (sampleFut != null) {
            List<String> names = new ArrayList<>();
            Iterator<Map.Entry<String, JsonNode>> it = sampleFut.fields();
            while (it.hasNext()) names.add(it.next().getKey());
            System.out.println("FNO_MASTER_FUT_FIELD_NAMES=" + names);
        }
        assertTrue(futRows > 0, "no NSE_FO FUT rows found: schema or segment naming differs");

        // Parser under test. Any malformed FUT row throws (fail closed).
        List<FuturesContract> contracts;
        try (InputStream in = gzipOrPlain(file)) {
            contracts = UpstoxFuturesInstrumentMaster.parse(in, asOf);
        }
        var index = UpstoxFuturesInstrumentMaster.index(contracts);
        System.out.println("FNO_MASTER_PARSED_CURRENT_FUT=" + contracts.size() + " as_of=" + asOf);
        System.out.println("FNO_MASTER_UNDERLYINGS=" + index.size());

        var nifty = index.get("NSE_INDEX|Nifty 50");
        System.out.println("FNO_MASTER_NIFTY_EXPIRIES=" + (nifty == null ? 0 : nifty.size()));
        assertNotNull(nifty, "NIFTY index futures not discovered");
        assertTrue(nifty.size() >= 2, "need near and far NIFTY futures");
        var expiries = new ArrayList<>(nifty.keySet());
        FuturesContract near = nifty.get(expiries.get(0));
        FuturesContract far = nifty.get(expiries.get(1));
        System.out.println("FNO_MASTER_NEAR=" + near.instrumentKey() + " expiry=" + near.expiry() + " lot=" + near.lotSize());
        System.out.println("FNO_MASTER_FAR=" + far.instrumentKey() + " expiry=" + far.expiry() + " lot=" + far.lotSize());
        assertEquals(near.lotSize(), far.lotSize(), "near and far lot sizes differ");
        assertTrue(near.expiry().isBefore(far.expiry()));
    }

    private static InputStream gzipOrPlain(Path file) throws Exception {
        return Files.newInputStream(file);
    }
}
