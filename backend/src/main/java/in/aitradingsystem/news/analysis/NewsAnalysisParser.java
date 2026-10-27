package in.aitradingsystem.news.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Strictly validates model output against the analysis schema. Anything outside the schema is
 * rejected, so an unvalidated model response can never reach storage or downstream decisions.
 */
@Component
public class NewsAnalysisParser {
    public static final Set<String> SENTIMENTS = Set.of("POSITIVE", "NEGATIVE", "NEUTRAL", "MIXED");
    public static final Set<String> CATEGORIES =
        Set.of("EARNINGS", "REGULATORY", "MACRO", "CORPORATE_ACTION", "MANAGEMENT", "SECTOR", "OTHER");
    private static final Pattern SYMBOL = Pattern.compile("^[A-Z0-9&-]{1,20}$");
    private static final int MAX_SYMBOLS = 10;
    private static final int MAX_RISKS = 5;
    private static final int MAX_EVIDENCE = 300;

    private final ObjectMapper mapper;

    public NewsAnalysisParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public record Result(String sentiment, String category, BigDecimal confidence, List<String> impactedSymbols,
        String evidence, List<String> risks) {}

    public Result parse(String raw) {
        if (raw == null || raw.isBlank()) throw new AiProviderException(AiErrorCode.INVALID_RESPONSE, "Empty model response");
        JsonNode root;
        try {
            root = mapper.readTree(stripFences(raw));
        } catch (Exception e) {
            throw new AiProviderException(AiErrorCode.INVALID_RESPONSE, "Model response is not valid JSON");
        }
        if (root == null || !root.isObject()) {
            throw new AiProviderException(AiErrorCode.INVALID_RESPONSE, "Model response is not a JSON object");
        }

        String sentiment = enumField(root, "sentiment", SENTIMENTS);
        String category = enumField(root, "category", CATEGORIES);

        JsonNode conf = root.get("confidence");
        if (conf == null || !conf.isNumber()) throw invalid("confidence must be a number");
        double c = conf.asDouble();
        if (Double.isNaN(c) || c < 0 || c > 1) throw invalid("confidence must be between 0 and 1");
        BigDecimal confidence = BigDecimal.valueOf(c).setScale(3, RoundingMode.HALF_UP);

        List<String> symbols = new ArrayList<>();
        for (JsonNode s : arrayField(root, "impactedSymbols", MAX_SYMBOLS)) {
            if (!s.isTextual()) throw invalid("impactedSymbols must contain strings");
            String sym = s.asText().trim().toUpperCase(Locale.ROOT);
            if (!SYMBOL.matcher(sym).matches()) throw invalid("impactedSymbols contains an invalid symbol");
            if (!symbols.contains(sym)) symbols.add(sym);
        }

        JsonNode ev = root.get("evidence");
        if (ev == null || !ev.isTextual() || ev.asText().isBlank()) throw invalid("evidence is required");
        String evidence = truncate(ev.asText().trim(), MAX_EVIDENCE);

        List<String> risks = new ArrayList<>();
        for (JsonNode r : arrayField(root, "risks", MAX_RISKS)) {
            if (!r.isTextual()) throw invalid("risks must contain strings");
            risks.add(truncate(r.asText().trim(), 200));
        }

        return new Result(sentiment, category, confidence, List.copyOf(symbols), evidence, List.copyOf(risks));
    }

    private String enumField(JsonNode root, String name, Set<String> allowed) {
        JsonNode n = root.get(name);
        if (n == null || !n.isTextual()) throw invalid(name + " is required");
        String v = n.asText().trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(v)) throw invalid(name + " has an unsupported value");
        return v;
    }

    private List<JsonNode> arrayField(JsonNode root, String name, int max) {
        JsonNode n = root.get(name);
        if (n == null || n.isNull()) return List.of();
        if (!n.isArray()) throw invalid(name + " must be an array");
        if (n.size() > max) throw invalid(name + " has too many entries");
        List<JsonNode> out = new ArrayList<>();
        n.forEach(out::add);
        return out;
    }

    /** Models sometimes wrap JSON in markdown fences; remove them before parsing. */
    static String stripFences(String raw) {
        String t = raw.trim();
        if (t.startsWith("```")) {
            int firstNewline = t.indexOf('\n');
            int end = t.lastIndexOf("```");
            if (firstNewline > 0 && end > firstNewline) t = t.substring(firstNewline + 1, end).trim();
        }
        return t;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static AiProviderException invalid(String detail) {
        return new AiProviderException(AiErrorCode.INVALID_RESPONSE, "Invalid model response: " + detail);
    }
}
