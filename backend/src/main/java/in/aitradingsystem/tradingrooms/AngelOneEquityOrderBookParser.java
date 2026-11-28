package in.aitradingsystem.tradingrooms;

import com.fasterxml.jackson.databind.JsonNode;
import in.aitradingsystem.tradingrooms.BrokerOrderRecord.BrokerReportedStatus;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure adapter that normalizes the Angel One {@code getOrderBook} data array
 * into {@link BrokerOrderRecord}s. It never throws and makes no safety decision.
 *
 * <p>Note: Angel One NSE cash symbols are suffixed ("-EQ"). The reconciler
 * matches the stored trading symbol exactly, so any per-broker symbol
 * normalization must be verified and applied before LIVE dispatch is enabled;
 * until then an un-normalized symbol safely reserves the order.
 */
public final class AngelOneEquityOrderBookParser {
    private static final int MAX_ENTRIES = 500;

    private AngelOneEquityOrderBookParser() {}

    /** @param data the {@code data} node from getOrderBook (array of orders). */
    public static List<BrokerOrderRecord> parse(JsonNode data) {
        List<BrokerOrderRecord> out = new ArrayList<>();
        if (data == null || !data.isArray() || data.size() > MAX_ENTRIES) {
            return out;
        }
        for (JsonNode node : data) {
            if (node == null || !node.isObject()) continue;
            out.add(new BrokerOrderRecord(
                text(node, "orderid"),
                text(node, "ordertag"),
                exchange(text(node, "exchange")),
                text(node, "tradingsymbol"),
                side(text(node, "transactiontype")),
                intOrNull(node.path("quantity")),
                decimalOrNull(node.path("price")),
                product(text(node, "producttype")),
                orderType(text(node, "ordertype")),
                duration(text(node, "duration")),
                status(text(node, firstPresent(node, "orderstatus", "status"))),
                intOrNull(node.path("filledshares")),
                decimalOrNull(node.path("averageprice"))));
        }
        return out;
    }

    private static String firstPresent(JsonNode node, String a, String b) {
        return node.path(a).isTextual() ? a : b;
    }

    private static BrokerReportedStatus status(String raw) {
        if (raw == null) return BrokerReportedStatus.UNMAPPED;
        return switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "complete" -> BrokerReportedStatus.FILLED;
            case "rejected" -> BrokerReportedStatus.REJECTED;
            case "cancelled", "cancelled after market order" -> BrokerReportedStatus.CANCELLED;
            case "open", "open pending", "trigger pending", "validation pending",
                 "put order req received", "modified", "modify pending",
                 "after market order req received" -> BrokerReportedStatus.OPEN;
            default -> BrokerReportedStatus.UNMAPPED;
        };
    }

    private static String exchange(String raw) {
        if (raw == null) return null;
        String v = raw.trim().toUpperCase(java.util.Locale.ROOT);
        return ("NSE".equals(v) || "BSE".equals(v)) ? v : null;
    }

    private static String side(String raw) {
        if (raw == null) return null;
        String v = raw.trim().toUpperCase(java.util.Locale.ROOT);
        return ("BUY".equals(v) || "SELL".equals(v)) ? v : null;
    }

    private static String product(String raw) {
        if (raw == null) return null;
        return "INTRADAY".equals(raw.trim().toUpperCase(java.util.Locale.ROOT)) ? "INTRADAY" : null;
    }

    private static String orderType(String raw) {
        if (raw == null) return null;
        return "LIMIT".equals(raw.trim().toUpperCase(java.util.Locale.ROOT)) ? "LIMIT" : null;
    }

    private static String duration(String raw) {
        if (raw == null) return null;
        return "DAY".equals(raw.trim().toUpperCase(java.util.Locale.ROOT)) ? "DAY" : null;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (!v.isTextual()) return null;
        String s = v.asText();
        return s.isBlank() || s.length() > 128 ? null : s;
    }

    private static Integer intOrNull(JsonNode v) {
        if (v == null || v.isMissingNode() || v.isNull()) return null;
        if (v.isInt() || v.isLong()) {
            long l = v.asLong();
            return (l < 0 || l > 1_000_000) ? null : (int) l;
        }
        if (v.isTextual()) {
            String s = v.asText().trim();
            if (!s.matches("[0-9]{1,7}")) return null;
            try { return Integer.parseInt(s); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    private static BigDecimal decimalOrNull(JsonNode v) {
        if (v == null || v.isMissingNode() || v.isNull()) return null;
        try {
            if (v.isNumber()) return v.decimalValue();
            if (v.isTextual()) {
                String s = v.asText().trim();
                if (!s.matches("[0-9]{1,10}(?:\\.[0-9]{1,4})?")) return null;
                return new BigDecimal(s);
            }
        } catch (RuntimeException malformed) {
            return null;
        }
        return null;
    }
}
