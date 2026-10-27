package in.aitradingsystem.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Strict parser for the official SmartAPI candle tuple: [timestamp, open, high, low, close, volume]. */
@Component
public class HistoricalCandleParser {
    public record CandleValues(
            Instant candleTime, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close, long volume) {}

    public List<CandleValues> parse(JsonNode data) {
        if (data == null || !data.isArray()) {
            throw new MarketDataException("CANDLES_NOT_ARRAY", "Historical candle data must be an array");
        }
        Map<Instant, CandleValues> unique = new LinkedHashMap<>();
        for (int index = 0; index < data.size(); index++) {
            JsonNode row = data.get(index);
            if (row == null || !row.isArray() || row.size() != 6) {
                throw new MarketDataException("INVALID_CANDLE_SHAPE",
                        "Historical candle row " + index + " must contain exactly six values");
            }
            CandleValues candle = parseRow(row, index);
            CandleValues previous = unique.putIfAbsent(candle.candleTime(), candle);
            if (previous != null && !sameValues(previous, candle)) {
                throw new MarketDataException("CONFLICTING_CANDLE_DUPLICATE",
                        "Historical response contains conflicting candles for the same timestamp");
            }
        }
        return List.copyOf(new ArrayList<>(unique.values()));
    }

    private CandleValues parseRow(JsonNode row, int index) {
        Instant time;
        try {
            if (!row.get(0).isTextual()) {
                throw invalid(index, "timestamp must be text with an explicit UTC offset");
            }
            time = OffsetDateTime.parse(row.get(0).asText()).toInstant();
        } catch (DateTimeParseException ex) {
            throw invalid(index, "timestamp must be ISO-8601 text with an explicit UTC offset");
        }
        BigDecimal open = decimal(row.get(1), index, "open");
        BigDecimal high = decimal(row.get(2), index, "high");
        BigDecimal low = decimal(row.get(3), index, "low");
        BigDecimal close = decimal(row.get(4), index, "close");
        long volume = integer(row.get(5), index, "volume");

        if (open.signum() <= 0 || high.signum() <= 0 || low.signum() <= 0 || close.signum() <= 0) {
            throw invalid(index, "prices must be positive");
        }
        if (volume < 0) throw invalid(index, "volume cannot be negative");
        if (high.compareTo(low) < 0 || high.compareTo(open) < 0 || high.compareTo(close) < 0
                || low.compareTo(open) > 0 || low.compareTo(close) > 0) {
            throw invalid(index, "OHLC values are inconsistent");
        }
        return new CandleValues(time, open, high, low, close, volume);
    }

    private BigDecimal decimal(JsonNode value, int row, String field) {
        if (value == null || value.isNull() || !(value.isNumber() || value.isTextual())) {
            throw invalid(row, field + " must be numeric");
        }
        try {
            return new BigDecimal(value.asText());
        } catch (NumberFormatException ex) {
            throw invalid(row, field + " must be numeric");
        }
    }

    private long integer(JsonNode value, int row, String field) {
        BigDecimal number = decimal(value, row, field);
        try {
            return number.longValueExact();
        } catch (ArithmeticException ex) {
            throw invalid(row, field + " must be a whole number");
        }
    }

    private boolean sameValues(CandleValues a, CandleValues b) {
        return a.open().compareTo(b.open()) == 0 && a.high().compareTo(b.high()) == 0
                && a.low().compareTo(b.low()) == 0 && a.close().compareTo(b.close()) == 0
                && a.volume() == b.volume();
    }

    private MarketDataException invalid(int index, String detail) {
        return new MarketDataException("INVALID_CANDLE_ROW", "Historical candle row " + index + ": " + detail);
    }
}
