package in.aitradingsystem.provider.upstox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * All non-expired NSE_FO index and equity options AND futures from one
 * published, SHA-audited Upstox BOD master. A contract identity is not an
 * exchange-timed quote, a valid market-data subscription, or an order grant.
 *
 * Tick size in the official JSON is in PAISE, not rupees. Preserve it as-is
 * and convert explicitly at the presentation boundary. Duplicate identities,
 * overlapping option sides/strikes, malformed keys or lot/expiry changes
 * fail the entire candidate snapshot closed.
 */
public final class UpstoxFnoInstrumentUniverse {
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_FNO_ROWS = 100_000;
    private static final int MAX_PAGE_SIZE = 100;

    private UpstoxFnoInstrumentUniverse() {}

    public record Contract(String instrumentKey, String underlyingKey,
            String underlyingSymbol, String tradingSymbol, String type,
            LocalDate expiry, BigDecimal strikePrice, int lotSize,
            BigDecimal tickSizePaise, BigDecimal freezeQuantity, boolean weekly) {
        public BigDecimal tickSizeRupees() {
            return tickSizePaise.movePointLeft(2).stripTrailingZeros();
        }
    }

    public record Catalog(List<Contract> contracts,
            Map<String, Contract> byInstrumentKey,
            Map<String, List<Contract>> byUnderlying,
            int futures, int calls, int puts) {
        public int underlyings() { return byUnderlying.size(); }
        public int size() { return contracts.size(); }

        /** Paginated full-universe discovery; the complete catalog stays server-side. */
        public Page query(String underlyingKey, LocalDate expiry,
                String type, int page, int size) {
            if (page < 0 || size < 1 || size > MAX_PAGE_SIZE ||
                    (type != null && !Set.of("FUT", "CE", "PE").contains(type)) ||
                    (underlyingKey != null && underlyingKey.length() > 96)) {
                throw new IllegalArgumentException("FNO_UNIVERSE_INVALID_QUERY");
            }
            List<Contract> candidates = underlyingKey == null ? contracts :
                    byUnderlying.getOrDefault(underlyingKey, List.of());
            long offset = (long) page * size;
            int matched = 0;
            var rows = new ArrayList<Contract>();
            for (Contract candidate : candidates) {
                if ((expiry != null && !expiry.equals(candidate.expiry())) ||
                        (type != null && !type.equals(candidate.type()))) continue;
                if (matched >= offset && rows.size() < size) rows.add(candidate);
                matched++;
            }
            return new Page(matched, List.copyOf(rows));
        }
    }

    public record Page(int total, List<Contract> contracts) {}

    public static Catalog parse(InputStream source, LocalDate asOf) throws IOException {
        if (asOf == null || source == null)
            throw new IllegalArgumentException("FNO_UNIVERSE_INPUT_REQUIRED");
        // Reuse existing gzip detection and bounded decompression, so neither
        // corrupt gzip nor >200 MiB sources can bypass the master-file cap.
        byte[] content = UpstoxFuturesInstrumentMaster.readBounded(source);
        JsonNode root = JSON.readTree(content);
        if (root == null || !root.isArray())
            throw new UpstoxFuturesInstrumentMaster.MalformedInstrumentMasterException(
                "FNO_UNIVERSE_NOT_ARRAY");

        var ordered = new TreeMap<String, Contract>();
        var combinations = new HashSet<String>();
        int allFno = 0;
        for (JsonNode row : root) {
            if (!"NSE_FO".equals(text(row, "segment"))) continue;
            String type = text(row, "instrument_type");
            if (!Set.of("FUT", "CE", "PE").contains(type)) continue;
            if (++allFno > MAX_FNO_ROWS)
                throw malformed("FNO_UNIVERSE_TOO_MANY_CONTRACTS");
            Contract contract = decode(row, type);
            if (contract.expiry().isBefore(asOf)) continue;
            if (ordered.putIfAbsent(contract.instrumentKey(), contract) != null)
                throw malformed("FNO_UNIVERSE_DUPLICATE_INSTRUMENT_KEY");
            String semantic = contract.underlyingKey() + "|" + contract.expiry() +
                "|" + type + "|" + contract.strikePrice().stripTrailingZeros().toPlainString();
            if (!combinations.add(semantic))
                throw malformed("FNO_UNIVERSE_CONFLICTING_CONTRACT_IDENTITY");
        }
        // Do not report a source with no current NSE_FO contracts as complete.
        if (ordered.isEmpty()) throw malformed("FNO_UNIVERSE_EMPTY");

        List<Contract> sorted = ordered.values().stream().sorted(
            Comparator.comparing(Contract::underlyingKey)
                .thenComparing(Contract::expiry)
                .thenComparing(Contract::type)
                .thenComparing(Contract::strikePrice)
                .thenComparing(Contract::instrumentKey)).toList();
        var byUnderlying = new TreeMap<String, List<Contract>>();
        for (Contract c : sorted) {
            byUnderlying.computeIfAbsent(c.underlyingKey(),
                ignored -> new ArrayList<>()).add(c);
        }
        byUnderlying.replaceAll((k, v) -> List.copyOf(v));
        int futures = 0, calls = 0, puts = 0;
        for (Contract c : sorted) {
            switch (c.type()) {
                case "FUT" -> futures++;
                case "CE" -> calls++;
                case "PE" -> puts++;
                default -> throw new IllegalStateException("FNO_UNIVERSE_BAD_TYPE");
            }
        }
        return new Catalog(List.copyOf(sorted), Map.copyOf(ordered),
            Map.copyOf(byUnderlying), futures, calls, puts);
    }

    private static Contract decode(JsonNode row, String type) throws IOException {
        String key = text(row, "instrument_key");
        String underlying = text(row, "underlying_key");
        String underlyingSymbol = text(row, "underlying_symbol");
        String symbol = text(row, "trading_symbol");
        if (key == null || !key.matches("NSE_FO\\|[A-Za-z0-9._-]{1,64}") ||
                underlying == null || !underlying.matches(
                    "(?:NSE_EQ\\|[A-Za-z0-9._-]{1,64}|NSE_INDEX\\|[A-Za-z0-9 ._-]{1,64})") ||
                underlyingSymbol == null || underlyingSymbol.isBlank() ||
                underlyingSymbol.length() > 96 ||
                symbol == null || symbol.isBlank() || symbol.length() > 128)
            throw malformed("FNO_UNIVERSE_BAD_IDENTITY");
        if (!"NSE".equals(text(row, "exchange")))
            throw malformed("FNO_UNIVERSE_BAD_EXCHANGE");
        JsonNode expiryMillis = row.path("expiry");
        if (!expiryMillis.isIntegralNumber() ||
                expiryMillis.asLong() < 946684800000L ||
                expiryMillis.asLong() > 4102444800000L)
            throw malformed("FNO_UNIVERSE_BAD_EXPIRY");
        LocalDate expiry = Instant.ofEpochMilli(expiryMillis.asLong())
            .atZone(IST).toLocalDate();
        JsonNode lotNode = row.path("lot_size");
        if (!lotNode.canConvertToInt() || lotNode.asInt() < 1 ||
                lotNode.asInt() > 1_000_000)
            throw malformed("FNO_UNIVERSE_BAD_LOT");
        BigDecimal tick = positiveDecimal(row, "tick_size", true);
        BigDecimal freeze = positiveDecimal(row, "freeze_quantity", false);
        BigDecimal strike = "FUT".equals(type)
            ? BigDecimal.ZERO : positiveDecimal(row, "strike_price", true);
        JsonNode weeklyNode = row.path("weekly");
        if (!weeklyNode.isMissingNode() && !weeklyNode.isNull() &&
                !weeklyNode.isBoolean()) throw malformed("FNO_UNIVERSE_BAD_WEEKLY");
        return new Contract(key, underlying, underlyingSymbol, symbol, type, expiry,
            strike, lotNode.asInt(), tick, freeze, weeklyNode.asBoolean(false));
    }

    private static BigDecimal positiveDecimal(JsonNode row, String field,
            boolean required) throws IOException {
        JsonNode n = row.path(field);
        if (!required && (n.isMissingNode() || n.isNull())) return null;
        if (!n.isNumber() || n.decimalValue().signum() <= 0 ||
                n.decimalValue().precision() > 18)
            throw malformed("FNO_UNIVERSE_BAD_" + field.toUpperCase());
        return n.decimalValue();
    }

    private static String text(JsonNode row, String key) {
        JsonNode n = row.path(key);
        return n.isTextual() ? n.textValue() : null;
    }

    private static UpstoxFuturesInstrumentMaster.MalformedInstrumentMasterException malformed(
            String code) {
        return new UpstoxFuturesInstrumentMaster.MalformedInstrumentMasterException(code);
    }
}
