package in.aitradingsystem.instrument;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import org.springframework.stereotype.Component;

/**
 * Upstox's published BSE beginning-of-day instrument file is a SECONDARY BROKER
 * REFERENCE, not the official BSE security master. Its exchange_token may be reused:
 * the caller must also match the exact trading symbol. ISIN and instrument_key
 * are retained for source custody. No order instrument IDs are inferred here.
 */
@Component
public final class UpstoxBseCrosswalkParser {
    private static final int MAX_UNCOMPRESSED = 80 * 1024 * 1024;
    private static final int MAX_ROWS = 100_000;
    private static final Pattern ISIN = Pattern.compile("[A-Z]{2}[A-Z0-9]{9}[0-9]");
    private static final Pattern TOKEN = Pattern.compile("[0-9]{1,15}");
    private static final Pattern SYMBOL = Pattern.compile("[A-Za-z0-9&._/+-]{1,100}");
    private final ObjectMapper mapper;

    public record Equity(String code, String symbol, String isin, String key) {}
    public record Parsed(int totalRows, int eligibleRows, int excludedRows,
                         Map<String, Equity> verifiedEquities) {}

    public UpstoxBseCrosswalkParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public Parsed parseGzip(byte[] gzip) {
        if (gzip == null || gzip.length < 12 || gzip.length > 32 * 1024 * 1024
                || (gzip[0] & 0xff) != 0x1f || (gzip[1] & 0xff) != 0x8b) {
            throw invalid("Upstox BSE source must be a bounded gzip file");
        }
        byte[] decoded;
        try (var input = new GZIPInputStream(new ByteArrayInputStream(gzip));
             var output = new ByteArrayOutputStream()) {
            var buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (read > MAX_UNCOMPRESSED - output.size()) {
                    throw invalid("Uncompressed Upstox BSE file exceeds configured limit");
                }
                output.write(buffer, 0, read);
            }
            decoded = output.toByteArray();
        } catch (IOException e) {
            throw invalid("Upstox BSE gzip is corrupt");
        }
        try {
            JsonNode root = mapper.readTree(decoded);
            if (root == null || !root.isArray() || root.isEmpty() || root.size() > MAX_ROWS) {
                throw invalid("Upstox BSE instrument JSON must be a bounded nonempty array");
            }
            Map<String, Equity> accepted = new HashMap<>();
            int excluded = 0;
            for (JsonNode row : root) {
                if (!row.isObject()) throw invalid("Malformed Upstox instrument row");
                String segment = value(row,"segment");
                String exchange = value(row,"exchange");
                String type = value(row,"instrument_type");
                String security = value(row,"security_type");
                // BSE A/EQ series is explicit equity evidence. Upstox's BSE
                // BOD JSON (verified 2026-11-13) omits security_type on
                // EVERY BSE_EQ row, unlike its NSE examples. A missing
                // optional field is not suspended evidence; any explicitly
                // non-NORMAL security type still fails closed.
                if (!"BSE_EQ".equals(segment) || !"BSE".equals(exchange)
                        || !("A".equals(type) || "EQ".equals(type))
                        || (security != null && !"NORMAL".equals(security))) {
                    excluded++;
                    continue;
                }
                String token = value(row,"exchange_token");
                String symbol = value(row,"trading_symbol");
                String isin = value(row,"isin");
                String key = value(row,"instrument_key");
                if (token == null || !TOKEN.matcher(token).matches()
                        || symbol == null || !SYMBOL.matcher(symbol).matches()
                        || isin == null || !ISIN.matcher(isin).matches()
                        || key == null || !key.equals("BSE_EQ|" + isin)) {
                    throw invalid("Invalid Upstox verified equity identity");
                }
                var equity = new Equity(token,symbol,isin,key);
                if (accepted.putIfAbsent(token,equity) != null) {
                    throw invalid("Duplicate BSE exchange token in Upstox source");
                }
            }
            return new Parsed(root.size(),accepted.size(),excluded,Map.copyOf(accepted));
        } catch (InstrumentMasterException e) {
            throw e;
        } catch (IOException e) {
            throw invalid("Upstox BSE source is not valid JSON");
        }
    }

    private static String value(JsonNode row, String field) {
        JsonNode value = row.get(field);
        return value == null || value.isNull() || !value.isValueNode()
                ? null : value.asText().trim();
    }

    private static InstrumentMasterException invalid(String message) {
        return new InstrumentMasterException("UPSTOX_BSE_REFERENCE_INVALID",message);
    }
}
