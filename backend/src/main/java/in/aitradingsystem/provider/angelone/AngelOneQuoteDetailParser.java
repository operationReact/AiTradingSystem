package in.aitradingsystem.provider.angelone;

import com.fasterxml.jackson.databind.JsonNode;
import in.aitradingsystem.instrument.Instrument;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Strict parser for one row of a SmartAPI FULL market quote. Only the OHLC and volume fields
 * are exposed; depth is deliberately ignored here. {@code close} in the provider payload is the
 * previous session's close, so it is named {@code previousClose} and is not range-checked against
 * the current day's high and low.
 */
public final class AngelOneQuoteDetailParser {
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter FEED_TIME =
            DateTimeFormatter.ofPattern("dd-MMM-uuuu HH:mm:ss", Locale.ENGLISH);

    /** One best-five level. Price and quantity are provider values; zero-priced levels are never kept. */
    public record DepthLevel(BigDecimal price, long quantity, long orders) {}

    /**
     * Day OHLC from the same FULL reply. {@code averagePrice} (VWAP), {@code week52High} and
     * {@code week52Low} are null when the provider sends zero, so the UI shows them as unavailable.
     */
    public record Detail(BigDecimal ltp, BigDecimal open, BigDecimal high, BigDecimal low,
                         BigDecimal previousClose, long volume, Instant exchangeAt,
                         BigDecimal averagePrice, BigDecimal week52High, BigDecimal week52Low,
                         long totalBuyQuantity, long totalSellQuantity,
                         List<DepthLevel> bids, List<DepthLevel> asks) {
        public Detail {
            bids = List.copyOf(bids);
            asks = List.copyOf(asks);
        }
    }

    private AngelOneQuoteDetailParser() {}

    public static Detail parse(JsonNode data, Instrument expected, Instant availableAt) {
        JsonNode fetched = data == null ? null : data.get("fetched");
        JsonNode unfetched = data == null ? null : data.get("unfetched");
        if (fetched == null || !fetched.isArray() || unfetched == null || !unfetched.isArray()) {
            throw invalid("QUOTE_DETAIL_SHAPE");
        }
        if (!unfetched.isEmpty() || fetched.size() != 1) {
            throw invalid("QUOTE_DETAIL_COUNT");
        }
        JsonNode row = fetched.get(0);
        if (!row.isObject()) throw invalid("QUOTE_DETAIL_ROW");
        if (!expected.exchange().equals(text(row, "exchange"))
                || !expected.tradingSymbol().equals(text(row, "tradingSymbol"))
                || !expected.symbolToken().equals(text(row, "symbolToken"))) {
            throw invalid("QUOTE_DETAIL_IDENTITY");
        }
        return parseRow(row, availableAt, false);
    }

    /** One identity to match against a batch row; a null trading symbol matches the token only (indices). */
    public record Expected(String symbolToken, String tradingSymbol) {}

    /**
     * Batch FULL quote: keeps only rows whose exchange, token and (when given) trading symbol match an
     * expected identity and that pass the same OHLC checks as {@link #parse}. Missing or invalid rows are
     * omitted so the caller can show them as unavailable rather than failing the whole batch.
     */
    public static Map<String, Detail> parseBatch(JsonNode data, List<Expected> expected, Instant availableAt) {
        return parseBatch("NSE", data, expected, availableAt);
    }

    /** Parse one exchange at a time; token strings are not unique across NSE and BSE. */
    public static Map<String, Detail> parseBatch(String exchange, JsonNode data,
            List<Expected> expected, Instant availableAt) {
        if (!"NSE".equals(exchange) && !"BSE".equals(exchange)) {
            throw new IllegalArgumentException("FULL batch cash exchange must be NSE or BSE");
        }
        JsonNode fetched = data == null ? null : data.get("fetched");
        if (fetched == null || !fetched.isArray()) throw invalid("QUOTE_BATCH_SHAPE");
        Map<String, Expected> byToken = new HashMap<>();
        for (Expected e : expected) byToken.put(e.symbolToken(), e);
        Map<String, Detail> result = new LinkedHashMap<>();
        for (JsonNode row : fetched) {
            if (!row.isObject()) continue;
            JsonNode token = row.get("symbolToken");
            if (token == null || !token.isTextual()) continue;
            Expected match = byToken.get(token.asText());
            if (match == null || !exchange.equals(optionalText(row, "exchange"))) continue;
            if (match.tradingSymbol() != null && !match.tradingSymbol().equals(optionalText(row, "tradingSymbol"))) {
                continue;
            }
            try {
                result.put(match.symbolToken(),
                    parseRow(row, availableAt, match.tradingSymbol() == null));
            } catch (AngelOneException invalidRow) {
                // Dropped by design; the caller reports the symbol as unavailable.
            }
        }
        return result;
    }

    private static Detail parseRow(JsonNode row, Instant availableAt, boolean isIndex) {
        Instant exchangeAt = parseFeedTime(text(row, "exchFeedTime"));
        if (exchangeAt.isAfter(availableAt)) throw invalid("QUOTE_DETAIL_FUTURE_EVENT");

        BigDecimal ltp = positive(row, "ltp");
        BigDecimal open = positive(row, "open");
        BigDecimal high = positive(row, "high");
        BigDecimal low = positive(row, "low");
        BigDecimal previousClose = positive(row, "close");
        if (high.compareTo(low) < 0) throw invalid("QUOTE_DETAIL_RANGE");
        if (ltp.compareTo(low) < 0 || ltp.compareTo(high) > 0
                || open.compareTo(low) < 0 || open.compareTo(high) > 0) {
            throw invalid("QUOTE_DETAIL_RANGE");
        }
        // Published Angel One index FULL rows may omit tradeVolume entirely:
        // an index has no traded share volume. Equity rows still require it.
        // Missing index volume becomes 0 (unknown); a present invalid/negative
        // index volume is rejected rather than silently coerced.
        long volume = isIndex && (row.get("tradeVolume") == null
                || row.get("tradeVolume").isNull())
            ? 0L : nonNegativeLong(row, "tradeVolume");
        BigDecimal averagePrice = optionalPositive(row, "avgPrice");
        BigDecimal week52High = optionalPositive(row, "52WeekHigh");
        BigDecimal week52Low = optionalPositive(row, "52WeekLow");
        long totalBuy = optionalNonNegativeLong(row, "totBuyQuan");
        long totalSell = optionalNonNegativeLong(row, "totSellQuan");
        JsonNode depth = row.get("depth");
        List<DepthLevel> bids = depth == null ? List.of() : depthLevels(depth.get("buy"));
        List<DepthLevel> asks = depth == null ? List.of() : depthLevels(depth.get("sell"));
        return new Detail(ltp, open, high, low, previousClose, volume, exchangeAt,
                averagePrice, week52High, week52Low, totalBuy, totalSell, bids, asks);
    }

    /** Keeps priced levels only, up to five per side. Empty levels are normal outside trading hours. */
    private static List<DepthLevel> depthLevels(JsonNode side) {
        if (side == null || !side.isArray()) return List.of();
        List<DepthLevel> levels = new java.util.ArrayList<>();
        for (JsonNode level : side) {
            if (levels.size() == 5) break;
            if (!level.isObject()) continue;
            JsonNode price = level.get("price");
            if (price == null || !price.isNumber() || price.decimalValue().signum() <= 0) continue;
            levels.add(new DepthLevel(price.decimalValue(),
                    optionalNonNegativeLong(level, "quantity"), optionalNonNegativeLong(level, "orders")));
        }
        return levels;
    }

    private static BigDecimal optionalPositive(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isNumber() || value.decimalValue().signum() <= 0) return null;
        return value.decimalValue();
    }

    private static long optionalNonNegativeLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.canConvertToLong() || value.longValue() < 0) return 0L;
        return value.longValue();
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || !value.isTextual() ? null : value.asText();
    }

    private static Instant parseFeedTime(String value) {
        try {
            return LocalDateTime.parse(value, FEED_TIME).atZone(MARKET_ZONE).toInstant();
        } catch (DateTimeParseException ex) {
            throw invalid("QUOTE_DETAIL_FEED_TIME");
        }
    }

    private static BigDecimal positive(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isNumber()) throw invalid("QUOTE_DETAIL_NUMERIC");
        BigDecimal result = value.decimalValue();
        if (result.signum() <= 0) throw invalid("QUOTE_DETAIL_NON_POSITIVE");
        return result;
    }

    private static long nonNegativeLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.canConvertToLong() || value.longValue() < 0) {
            throw invalid("QUOTE_DETAIL_VOLUME");
        }
        return value.longValue();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw invalid("QUOTE_DETAIL_TEXT");
        }
        return value.asText();
    }

    private static AngelOneException invalid(String code) {
        return new AngelOneException(AngelOneException.Kind.INVALID_RESPONSE, code,
                "FULL market quote detail response was invalid");
    }
}
