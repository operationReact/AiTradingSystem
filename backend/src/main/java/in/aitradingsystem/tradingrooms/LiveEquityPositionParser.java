package in.aitradingsystem.tradingrooms;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Normalizes a broker's own day position book into rows the preflight can use
 * for verified SELL inventory and day realized P&amp;L. The whole snapshot is
 * returned as {@code null} on ANY malformed or unrecognised row, so the caller
 * fails closed instead of trusting a partial book.
 *
 * <p>Field names follow the broker position responses as used by this
 * codebase. They must be confirmed against a read-only live probe before LIVE
 * dispatch is enabled; until then a schema mismatch yields {@code null} and
 * blocks every SELL and every daily-loss-dependent order.
 */
public final class LiveEquityPositionParser {
    private static final int MAX_ROWS = 500;

    /**
     * @param exchange   NSE or BSE
     * @param key        Angel One symboltoken, or Upstox instrument_token
     * @param intraday   true only for an intraday (MIS / "I") product
     * @param netQuantity signed net quantity (negative = short)
     * @param realised   day realized P&amp;L as reported by the broker
     */
    public record Row(String exchange, String key, boolean intraday,
            long netQuantity, BigDecimal realised) {}

    private LiveEquityPositionParser() {}

    /** Angel One {@code getPosition} data array. */
    public static List<Row> angelOne(JsonNode data) {
        return parse(data, node -> new Row(
            exchange(text(node, "exchange")),
            digits(text(node, "symboltoken")),
            "INTRADAY".equalsIgnoreCase(text(node, "producttype")),
            longValue(node.path("netqty")),
            decimal(node.path("realised"))));
    }

    /** Upstox short-term positions data array. */
    public static List<Row> upstox(JsonNode data) {
        return parse(data, node -> new Row(
            exchange(text(node, "exchange")),
            instrumentToken(text(node, "instrument_token")),
            "I".equals(text(node, "product")),
            longValue(node.path("quantity")),
            decimal(node.path("realised"))));
    }

    private interface RowMapper {
        Row map(JsonNode node);
    }

    private static List<Row> parse(JsonNode data, RowMapper mapper) {
        if (data == null || !data.isArray() || data.size() > MAX_ROWS) {
            return null;
        }
        List<Row> rows = new ArrayList<>(data.size());
        for (JsonNode node : data) {
            if (node == null || !node.isObject()) return null;
            Row row = mapper.map(node);
            // Any unparseable identity, quantity or P&L makes the whole book unverifiable.
            if (row.exchange() == null || row.key() == null
                    || row.netQuantity() == Long.MIN_VALUE || row.realised() == null) {
                return null;
            }
            rows.add(row);
        }
        return List.copyOf(rows);
    }

    /** Verified net intraday long inventory for one instrument; null when unverifiable. */
    public static Long intradayNetQuantity(List<Row> rows, String exchange, String key) {
        if (rows == null) return null;
        long net = 0;
        for (Row row : rows) {
            if (row.intraday() && row.exchange().equals(exchange) && row.key().equals(key)) {
                net = Math.addExact(net, row.netQuantity());
            }
        }
        return net;
    }

    /**
     * Day realized loss as a positive INR magnitude (0 when the day is flat or
     * profitable). Null when the book is unverifiable.
     */
    public static BigDecimal realisedLoss(List<Row> rows) {
        if (rows == null) return null;
        BigDecimal total = BigDecimal.ZERO;
        for (Row row : rows) {
            total = total.add(row.realised());
        }
        return total.signum() < 0 ? total.negate() : BigDecimal.ZERO;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual()) return null;
        String s = value.asText().trim();
        return s.isEmpty() || s.length() > 64 ? null : s;
    }

    private static String exchange(String raw) {
        if (raw == null) return null;
        String v = raw.toUpperCase(Locale.ROOT);
        return "NSE".equals(v) || "BSE".equals(v) ? v : null;
    }

    private static String digits(String raw) {
        return raw != null && raw.matches("[0-9]{1,15}") ? raw : null;
    }

    private static String instrumentToken(String raw) {
        return raw != null && raw.matches("(?:NSE|BSE)_EQ\\|[A-Z]{2}[A-Z0-9]{9}[0-9]") ? raw : null;
    }

    /** Returns Long.MIN_VALUE as an explicit "unparseable" sentinel. */
    private static long longValue(JsonNode value) {
        if (value.isIntegralNumber() && value.canConvertToLong()) return value.asLong();
        if (value.isTextual() && value.asText().trim().matches("-?[0-9]{1,12}")) {
            return Long.parseLong(value.asText().trim());
        }
        return Long.MIN_VALUE;
    }

    private static BigDecimal decimal(JsonNode value) {
        if (value.isNumber()) return value.decimalValue();
        if (value.isTextual() && value.asText().trim().matches("-?[0-9]{1,12}(?:\\.[0-9]{1,4})?")) {
            return new BigDecimal(value.asText().trim());
        }
        return null;
    }
}
