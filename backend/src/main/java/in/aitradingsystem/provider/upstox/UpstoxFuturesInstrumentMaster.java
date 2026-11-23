package in.aitradingsystem.provider.upstox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;

/**
 * Parses the Upstox published instrument master and extracts NSE_FO index and
 * stock FUTURE contracts only. Fails CLOSED: any malformed, ambiguous or
 * unexpected FUT row rejects the whole file, so a schema drift produces zero
 * contracts rather than a wrong instrument identity.
 *
 * <p>Field names follow the Upstox instrument master as documented in the
 * developer instruments page. That schema is NOT verified in this repository:
 * no official file has been fetched by these tests. Real verification requires
 * a protected read of the published file (see the PR evidence section).
 */
public final class UpstoxFuturesInstrumentMaster {
    public static final String OFFICIAL_URL =
        "https://assets.upstox.com/market-quote/instruments/exchange/complete.json.gz";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final long MAX_UNCOMPRESSED_BYTES = 200L * 1024 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();

    private UpstoxFuturesInstrumentMaster() {}

    /** One NSE_FO future. Lot size is in underlying units. */
    public record FuturesContract(String instrumentKey, String underlyingKey,
                                  String underlyingSymbol, String tradingSymbol,
                                  LocalDate expiry, int lotSize) {}

    public static final class MalformedInstrumentMasterException extends IOException {
        public MalformedInstrumentMasterException(String message) { super(message); }
    }

    /**
     * Reads a plain or gzip JSON array of instruments (gzip detected by magic
     * bytes, not extension) and returns NSE_FO FUT contracts with expiry on or
     * after {@code asOf}, sorted by underlying then expiry.
     */
    public static List<FuturesContract> parse(InputStream raw, LocalDate asOf) throws IOException {
        byte[] bytes = readBounded(raw);
        JsonNode root = JSON.readTree(bytes);
        if (root == null || !root.isArray()) {
            throw new MalformedInstrumentMasterException("INSTRUMENT_MASTER_NOT_ARRAY");
        }
        List<FuturesContract> out = new ArrayList<>();
        for (JsonNode row : root) {
            if (!"NSE_FO".equals(text(row, "segment")) && !"NSE_FO".equals(text(row, "exchange"))) {
                continue;
            }
            if (!"FUT".equals(text(row, "instrument_type"))) continue;
            FuturesContract c = toContract(row);
            if (!c.expiry().isBefore(asOf)) out.add(c);
        }
        out.sort(Comparator.comparing(FuturesContract::underlyingKey)
            .thenComparing(FuturesContract::expiry)
            .thenComparing(FuturesContract::instrumentKey));
        return List.copyOf(out);
    }

    /** Groups contracts as underlying → expiry → contract; duplicates are rejected. */
    public static Map<String, Map<LocalDate, FuturesContract>> index(List<FuturesContract> contracts)
            throws MalformedInstrumentMasterException {
        Map<String, Map<LocalDate, FuturesContract>> out = new TreeMap<>();
        for (FuturesContract c : contracts) {
            Map<LocalDate, FuturesContract> byExpiry = out.computeIfAbsent(c.underlyingKey(), k -> new TreeMap<>());
            if (byExpiry.put(c.expiry(), c) != null) {
                throw new MalformedInstrumentMasterException("DUPLICATE_FUTURE_UNDERLYING_EXPIRY");
            }
        }
        return out;
    }

    static FuturesContract toContract(JsonNode row) throws MalformedInstrumentMasterException {
        String key = text(row, "instrument_key");
        String underlying = text(row, "underlying_key");
        String underlyingSymbol = text(row, "underlying_symbol");
        String tradingSymbol = text(row, "trading_symbol");
        if (key == null || !key.matches("NSE_FO\\|[A-Za-z0-9._-]{1,64}")
                || underlying == null || !underlying.matches(
                    "(?:NSE_EQ\\|[A-Za-z0-9._-]{1,64}|NSE_INDEX\\|[A-Za-z0-9 ._-]{1,64})")
                || underlyingSymbol == null || underlyingSymbol.isBlank()
                || tradingSymbol == null || tradingSymbol.isBlank()) {
            throw new MalformedInstrumentMasterException("FUTURE_ROW_IDENTITY_INVALID");
        }
        JsonNode lot = row.get("lot_size");
        if (lot == null || !lot.canConvertToInt() || lot.asInt() <= 0 || lot.asInt() > 100000) {
            throw new MalformedInstrumentMasterException("FUTURE_ROW_LOT_SIZE_INVALID");
        }
        JsonNode exp = row.get("expiry");
        if (exp == null || !exp.isIntegralNumber() || exp.asLong() <= 0) {
            throw new MalformedInstrumentMasterException("FUTURE_ROW_EXPIRY_INVALID");
        }
        LocalDate expiry = Instant.ofEpochMilli(exp.asLong()).atZone(IST).toLocalDate();
        return new FuturesContract(key, underlying, underlyingSymbol, tradingSymbol, expiry, lot.asInt());
    }

    private static String text(JsonNode row, String field) {
        JsonNode n = row.get(field);
        return n == null || !n.isTextual() ? null : n.asText();
    }

    // Shared bounded gzip/plain reader for futures and the full CE/PE universe.
    static byte[] readBounded(InputStream raw) throws IOException {
        PushbackInputStream in = new PushbackInputStream(raw, 2);
        int b1 = in.read();
        int b2 = in.read();
        if (b2 != -1) in.unread(b2);
        if (b1 != -1) in.unread(b1);
        InputStream source = (b1 == 0x1f && b2 == 0x8b) ? new GZIPInputStream(in) : in;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        long total = 0;
        int n;
        while ((n = source.read(buf)) != -1) {
            total += n;
            if (total > MAX_UNCOMPRESSED_BYTES) {
                throw new MalformedInstrumentMasterException("INSTRUMENT_MASTER_TOO_LARGE");
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}
