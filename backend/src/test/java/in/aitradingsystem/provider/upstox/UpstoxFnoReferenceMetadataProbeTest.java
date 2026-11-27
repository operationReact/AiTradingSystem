package in.aitradingsystem.provider.upstox;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TreeSet;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;

/**
 * Read-only reference probe over the real published instrument master (env-gated). Prints only
 * public instrument metadata: stock-future underlyings and field names. No quotes or account data.
 */
class UpstoxFnoReferenceMetadataProbeTest {
    @Test
    void reportsStockFuturesAndDeliverabilityFields() throws Exception {
        String env = System.getenv("FNO_INSTRUMENT_MASTER_GZ");
        assumeTrue(env != null && !env.isBlank(), "reference master not provided");
        JsonNode root;
        try (InputStream in = new GZIPInputStream(Files.newInputStream(Path.of(env)))) {
            root = new ObjectMapper().readTree(in);
        }
        var fieldNames = new TreeSet<String>();
        var deliveryish = new TreeSet<String>();
        int stockFuts = 0;
        int shown = 0;
        for (JsonNode row : root) {
            if (!"NSE_FO".equals(row.path("segment").asText()) || !"FUT".equals(row.path("instrument_type").asText())) continue;
            row.fieldNames().forEachRemaining(name -> {
                fieldNames.add(name);
                String lower = name.toLowerCase();
                if (lower.contains("deliver") || lower.contains("settle") || lower.contains("physical")) deliveryish.add(name);
            });
            if (row.path("underlying_key").asText().startsWith("NSE_EQ|")) {
                stockFuts++;
                if (shown < 6) {
                    shown++;
                    System.out.println("FNO_REF_STOCK_FUT underlying_key=" + row.path("underlying_key").asText()
                            + " underlying_symbol=" + row.path("underlying_symbol").asText()
                            + " instrument_key=" + row.path("instrument_key").asText()
                            + " trading_symbol=" + row.path("trading_symbol").asText()
                            + " lot_size=" + row.path("lot_size").asText()
                            + " expiry_ms=" + row.path("expiry").asText());
                }
            }
        }
        System.out.println("FNO_REF_STOCK_FUT_ROWS=" + stockFuts);
        System.out.println("FNO_REF_NSE_FO_FUT_FIELDS=" + fieldNames);
        System.out.println("FNO_REF_DELIVERY_RELATED_FIELDS=" + deliveryish);
    }
}
