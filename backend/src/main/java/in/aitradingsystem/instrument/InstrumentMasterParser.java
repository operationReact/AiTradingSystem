package in.aitradingsystem.instrument;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Strict parser for the Angel One OpenAPIScripMaster JSON array. */
@Component
public class InstrumentMasterParser {
    private static final DateTimeFormatter EXPIRY = new DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendPattern("ddMMMyyyy")
            .toFormatter(Locale.ENGLISH);
    // Official daily master also includes commodity venues MintTick deliberately does not ingest.
    // These recognized, out-of-scope records are skipped, not treated as malformed target instruments.
    private static final Set<String> OUT_OF_SCOPE_SEGMENTS = Set.of("NCO", "NCDEX");
    private final ObjectMapper mapper;

    public InstrumentMasterParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public ParseResult parse(byte[] bytes, int maxRows) {
        try {
            JsonNode root = mapper.readTree(bytes);
            if (root == null || !root.isArray() || root.isEmpty() || root.size() > maxRows) {
                throw new InstrumentMasterException("INVALID_ROOT",
                        "Instrument master must be a non-empty bounded JSON array");
            }
            List<InstrumentDraft> accepted = new ArrayList<>(root.size());
            int rejected = 0;
            int skipped = 0;
            for (JsonNode node : root) {
                if (isOutOfScopeSegment(node)) {
                    skipped++;
                    continue;
                }
                try {
                    accepted.add(parseOne(node));
                } catch (RuntimeException e) {
                    rejected++;
                }
            }
            return new ParseResult(root.size(), List.copyOf(accepted), rejected, skipped);
        } catch (InstrumentMasterException e) {
            throw e;
        } catch (Exception e) {
            throw new InstrumentMasterException("INVALID_JSON", "Instrument master is not valid JSON");
        }
    }

    private static boolean isOutOfScopeSegment(JsonNode node) {
        if (node == null || !node.isObject()) return false;
        JsonNode raw = node.get("exch_seg");
        return raw != null && raw.isTextual()
                && OUT_OF_SCOPE_SEGMENTS.contains(raw.asText().trim().toUpperCase(Locale.ROOT));
    }

    private InstrumentDraft parseOne(JsonNode node) {
        if (!node.isObject()) throw new IllegalArgumentException("not object");
        String token = requiredText(node, "token", 32);
        String symbol = requiredText(node, "symbol", 100);
        String name = optionalText(node, "name", 200);
        String segment = normalizeSegment(requiredText(node, "exch_seg", 20));
        if (!token.matches("[0-9]{1,32}") || !symbol.matches("[A-Za-z0-9&._/-]{1,100}")) {
            throw new IllegalArgumentException("invalid token/symbol");
        }
        int lotSize = positiveInt(requiredText(node, "lotsize", 20));
        BigDecimal tickSize = positiveDecimal(requiredText(node, "tick_size", 50)).movePointLeft(2).stripTrailingZeros();
        String type = optionalText(node, "instrumenttype", 30);
        // Official SmartAPI cash EQ instruments may have an empty instrumenttype.
        // Canonicalize only the unambiguous cash -EQ series, never derivatives
        // or other NSE/BSE series (e.g. BE/BL/AF). V48 historical custody
        // requires EQ as a non-null, immutable classification.
        if (type == null && (segment.equals("NSE_CM") || segment.equals("BSE_CM"))
                && symbol.endsWith("-EQ")) {
            type = "EQ";
        }
        String expiryText = optionalText(node, "expiry", 20);
        LocalDate expiry = parseExpiry(expiryText);
        BigDecimal strike = parseStrike(optionalText(node, "strike", 50));
        String exchange = switch (segment) {
            case "NSE_CM" -> "NSE";
            case "BSE_CM" -> "BSE";
            case "NSE_FO" -> "NFO";
            case "BSE_FO" -> "BFO";
            case "MCX_FO" -> "MCX";
            case "CDS" -> "CDS";
            case "BCD" -> "BCD";
            default -> throw new IllegalArgumentException("unsupported segment");
        };
        return new InstrumentDraft(segment, exchange, token, symbol, name, type, expiry, strike, lotSize, tickSize);
    }

    /** Angel One uses both SmartAPI segment codes and exchange short names in official masters. */
    private static String normalizeSegment(String raw) {
        return switch (raw.toUpperCase(Locale.ROOT)) {
            case "NSE", "NSE_CM" -> "NSE_CM";
            case "BSE", "BSE_CM" -> "BSE_CM";
            case "NFO", "NSE_FO" -> "NSE_FO";
            case "BFO", "BSE_FO" -> "BSE_FO";
            case "MCX", "MCX_FO" -> "MCX_FO";
            case "CDS" -> "CDS";
            case "BCD" -> "BCD";
            default -> throw new IllegalArgumentException("unsupported segment");
        };
    }

    private static String requiredText(JsonNode node, String field, int max) {
        String value = optionalText(node, field, max);
        if (value == null) throw new IllegalArgumentException("missing field " + field);
        return value;
    }

    private static String optionalText(JsonNode node, String field, int max) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        String text = value.asText().trim();
        if (text.isEmpty()) return null;
        if (text.length() > max) throw new IllegalArgumentException("field too long");
        return text;
    }

    private static int positiveInt(String text) {
        try {
            int value = Integer.parseInt(text);
            if (value <= 0) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid positive integer");
        }
    }

    private static BigDecimal positiveDecimal(String text) {
        try {
            BigDecimal value = new BigDecimal(text);
            if (value.signum() <= 0 || value.scale() > 8 || value.precision() > 20) throw new NumberFormatException();
            return value.stripTrailingZeros();
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid positive decimal");
        }
    }

    private static BigDecimal parseStrike(String text) {
        if (text == null) return null;
        try {
            BigDecimal value = new BigDecimal(text);
            if (value.compareTo(new BigDecimal("-1")) == 0) return null;
            if (value.signum() < 0 || value.scale() > 6 || value.precision() > 20) {
                throw new NumberFormatException();
            }
            // SmartAPI stores option strike and tick size in paise in the master file.
            return value.signum() == 0 ? BigDecimal.ZERO : value.movePointLeft(2).stripTrailingZeros();
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid strike");
        }
    }

    private static LocalDate parseExpiry(String text) {
        if (text == null) return null;
        try {
            return LocalDate.parse(text.toUpperCase(Locale.ENGLISH), EXPIRY);
        } catch (DateTimeParseException legacyFormat) {
            try {
                return LocalDate.parse(text, DateTimeFormatter.ISO_LOCAL_DATE);
            } catch (DateTimeParseException isoFormat) {
                throw new IllegalArgumentException("invalid expiry");
            }
        }
    }

    public record InstrumentDraft(String exchangeSegment, String exchange, String symbolToken, String tradingSymbol,
                                  String name, String instrumentType, LocalDate expiry, BigDecimal strike,
                                  int lotSize, BigDecimal tickSize) {}
    public record ParseResult(int parsedCount, List<InstrumentDraft> accepted,
                              int rejectedCount, int skippedCount) {}
}
