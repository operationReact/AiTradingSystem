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
    public static final Set<String> HORIZONS = Set.of("INTRADAY", "MULTI_DAY", "LONGER_TERM", "UNKNOWN");
    public static final Set<String> EVENT_TYPES = Set.of("QUARTERLY_RESULTS", "ANNUAL_RESULTS",
        "RESULT_GUIDANCE", "DIVIDEND", "SPLIT_BONUS_RIGHTS", "BUYBACK", "ORDER_WIN",
        "MERGER_ACQUISITION", "OWNERSHIP_CHANGE", "MANAGEMENT_CHANGE", "REGULATORY",
        "MACRO", "SECTOR", "OTHER", "UNKNOWN");
    public static final Set<String> METRIC_NAMES = Set.of("REVENUE", "REVENUE_GROWTH_YOY_PCT",
        "REVENUE_GROWTH_QOQ_PCT", "EBITDA", "EBITDA_MARGIN_PCT", "OPERATING_PROFIT",
        "PAT", "PAT_GROWTH_YOY_PCT", "PAT_GROWTH_QOQ_PCT", "EPS", "DIVIDEND_PER_SHARE",
        "ORDER_VALUE", "DEBT", "GUIDANCE_VALUE");
    public static final Set<String> METRIC_UNITS = Set.of("INR_CRORE", "INR_LAKH",
        "INR_PER_SHARE", "PERCENT", "COUNT", "UNKNOWN");
    private static final Pattern SYMBOL = Pattern.compile("^[A-Z0-9&-]{1,20}$");
    private static final int MAX_SYMBOLS = 10;
    private static final int MAX_RISKS = 5;
    private static final int MAX_EVIDENCE = 300;

    private final ObjectMapper mapper;

    public NewsAnalysisParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public record Result(
        String headlineEnglish,
        String summaryEnglish,
        String sentiment,
        BigDecimal directionScore,
        String category,
        BigDecimal confidence,
        BigDecimal materiality,
        String horizon,
        BigDecimal novelty,
        boolean contradiction,
        boolean ambiguity,
        String eventType,
        List<FinancialMetric> financialMetrics,
        List<String> impactedSymbols,
        String evidence,
        List<String> risks) {}

    public Result parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new AiProviderException(AiErrorCode.INVALID_RESPONSE, "Empty model response");
        }
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
        BigDecimal directionScore = boundedNumber(root, "directionScore", -1, 1);
        String category = enumField(root, "category", CATEGORIES);
        BigDecimal confidence = boundedNumber(root, "confidence", 0, 1);
        BigDecimal materiality = boundedNumber(root, "materiality", 0, 1);
        String horizon = enumField(root, "horizon", HORIZONS);
        BigDecimal novelty = boundedNumber(root, "novelty", 0, 1);
        boolean contradiction = booleanField(root, "contradiction");
        boolean ambiguity = booleanField(root, "ambiguity");

        List<String> symbols = new ArrayList<>();
        for (JsonNode s : arrayField(root, "impactedSymbols", MAX_SYMBOLS)) {
            if (!s.isTextual()) throw invalid("impactedSymbols must contain strings");
            String sym = s.asText().trim().toUpperCase(Locale.ROOT);
            if (!SYMBOL.matcher(sym).matches()) throw invalid("impactedSymbols contains an invalid symbol");
            if (!symbols.contains(sym)) symbols.add(sym);
        }

        JsonNode ev = root.get("evidence");
        if (ev == null || !ev.isTextual() || ev.asText().isBlank()) throw invalid("evidence is required");
        String evidenceText = ev.asText().trim();
        if (!isEnglishText(evidenceText)) throw invalid("evidence must be English text");
        String evidence = truncate(evidenceText, MAX_EVIDENCE);

        List<String> risks = new ArrayList<>();
        for (JsonNode r : arrayField(root, "risks", MAX_RISKS)) {
            if (!r.isTextual()) throw invalid("risks must contain strings");
            String risk = r.asText().trim();
            if (!isEnglishText(risk)) throw invalid("risks must be English text");
            risks.add(truncate(risk, 200));
        }

        String eventType = root.has("eventType") ? enumField(root, "eventType", EVENT_TYPES) : "UNKNOWN";
        List<FinancialMetric> financialMetrics = parseFinancialMetrics(root.get("financialMetrics"));
        String headlineEnglish = optionalEnglishText(root, "headlineEnglish", 500);
        String summaryEnglish = optionalEnglishText(root, "summaryEnglish", 2000);

        return new Result(
            headlineEnglish,
            summaryEnglish,
            sentiment,
            directionScore,
            category,
            confidence,
            materiality,
            horizon,
            novelty,
            contradiction,
            ambiguity,
            eventType,
            financialMetrics,
            List.copyOf(symbols),
            evidence,
            List.copyOf(risks));
    }

    private String optionalEnglishText(JsonNode root, String name, int maxLength) {
        JsonNode node = root.get(name);
        if (node == null || node.isNull()) return "";
        if (!node.isTextual()) throw invalid(name + " must be text");
        String value = node.asText().trim();
        if (value.length() > maxLength) throw invalid(name + " is too long");
        if (!isEnglishText(value)) throw invalid(name + " must be English text");
        return value;
    }

    private static boolean isEnglishText(String value) {
        return value.codePoints().noneMatch(cp -> Character.isLetter(cp)
            && Character.UnicodeScript.of(cp) != Character.UnicodeScript.LATIN);
    }

    private List<FinancialMetric> parseFinancialMetrics(JsonNode node) {
        if (node == null || node.isNull()) return List.of();
        if (!node.isArray() || node.size() > 12) throw invalid("financialMetrics must be an array of at most 12 items");
        List<FinancialMetric> metrics = new ArrayList<>();
        for (JsonNode metric : node) {
            if (!metric.isObject()) throw invalid("financialMetrics entries must be objects");
            String name = enumField(metric, "name", METRIC_NAMES);
            JsonNode number = metric.get("value");
            if (number == null || !number.isNumber() || !Double.isFinite(number.asDouble())
                || Math.abs(number.asDouble()) > 1_000_000_000_000_000d) {
                throw invalid("financial metric value must be a finite, bounded number");
            }
            String unit = enumField(metric, "unit", METRIC_UNITS);
            String period = textField(metric, "period", 40);
            String evidence = textField(metric, "evidence", 240);
            if (evidence.isBlank() || !isEnglishText(period) || !isEnglishText(evidence)) {
                throw invalid("financial metric period and evidence must be English text");
            }
            metrics.add(new FinancialMetric(name, new BigDecimal(number.asText()), unit, period, evidence));
        }
        return List.copyOf(metrics);
    }

    private String textField(JsonNode root, String name, int maxLength) {
        JsonNode node = root.get(name);
        if (node == null || !node.isTextual()) throw invalid(name + " must be text");
        String value = node.asText().trim();
        if (value.length() > maxLength) throw invalid(name + " is too long");
        return value;
    }

    public record FinancialMetric(String name, BigDecimal value, String unit, String period, String evidence) {}

    private BigDecimal boundedNumber(JsonNode root, String name, double min, double max) {
        JsonNode node = root.get(name);
        if (node == null || !node.isNumber()) throw invalid(name + " must be a number");
        double value = node.asDouble();
        if (Double.isNaN(value) || !Double.isFinite(value) || value < min || value > max) {
            throw invalid(name + " must be between " + min + " and " + max);
        }
        return BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP);
    }

    private boolean booleanField(JsonNode root, String name) {
        JsonNode node = root.get(name);
        if (node == null || !node.isBoolean()) throw invalid(name + " must be a boolean");
        return node.asBoolean();
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
