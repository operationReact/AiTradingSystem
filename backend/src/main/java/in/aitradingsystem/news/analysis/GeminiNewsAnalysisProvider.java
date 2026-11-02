package in.aitradingsystem.news.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.aitradingsystem.config.NewsProperties;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class GeminiNewsAnalysisProvider extends AbstractHttpAnalysisProvider {
    private static final String BASE_URL = "https://generativelanguage.googleapis.com";

    public GeminiNewsAnalysisProvider(NewsProperties props, ObjectMapper mapper) {
        super(BASE_URL, props, mapper);
    }

    @Override
    public String name() {
        return "GEMINI";
    }

    @Override
    public String model() {
        return props.geminiModel();
    }

    @Override
    public boolean configured() {
        return props.geminiApiKey() != null && !props.geminiApiKey().isBlank();
    }

    @Override
    public Completion complete(String systemPrompt, String userPrompt) {
        if (!configured()) {
            throw new AiProviderException(AiErrorCode.UNCONFIGURED, "GEMINI_API_KEY is not set");
        }
        JsonNode root = post(
            "/v1/interactions",
            Map.of("x-goog-api-key", props.geminiApiKey()),
            requestBody(systemPrompt, userPrompt));
        if (!"completed".equals(root.path("status").asText())) {
            throw new AiProviderException(
                AiErrorCode.INVALID_RESPONSE,
                "GEMINI interaction did not complete");
        }
        JsonNode usage = root.path("usage");
        return new Completion(
            extractOutputText(root),
            nullableText(root.path("id")),
            nullableNonNegativeInt(usage.path("total_input_tokens")),
            nullableNonNegativeInt(usage.path("total_output_tokens")),
            nullableNonNegativeInt(usage.path("total_tokens")));
    }

    Map<String, Object> requestBody(String systemPrompt, String userPrompt) {
        return Map.of(
            "model", model(),
            "input", userPrompt,
            "system_instruction", systemPrompt,
            "store", false,
            "generation_config", Map.of(
                "temperature", 0,
                "max_output_tokens", 1024),
            "response_format", Map.of(
                "type", "text",
                "mime_type", "application/json",
                "schema", responseSchema()));
    }

    static String extractOutputText(JsonNode root) {
        for (JsonNode step : root.path("steps")) {
            if (!"model_output".equals(step.path("type").asText())) continue;
            for (JsonNode content : step.path("content")) {
                if ("text".equals(content.path("type").asText())
                    && content.path("text").isTextual()
                    && !content.path("text").asText().isBlank()) {
                    return content.path("text").asText();
                }
            }
        }
        throw new AiProviderException(
            AiErrorCode.INVALID_RESPONSE,
            "GEMINI returned no structured text content");
    }

    private static String nullableText(JsonNode node) {
        return node != null && node.isTextual() && !node.asText().isBlank() ? node.asText() : null;
    }

    private static Integer nullableNonNegativeInt(JsonNode node) {
        if (node == null || !node.canConvertToInt() || node.asInt() < 0) return null;
        return node.asInt();
    }

    private static Map<String, Object> responseSchema() {
        Map<String, Object> properties = Map.ofEntries(
            Map.entry("sentiment", Map.of(
                "type", "string",
                "enum", List.of("POSITIVE", "NEGATIVE", "NEUTRAL", "MIXED"))),
            Map.entry("directionScore", Map.of(
                "type", "number",
                "minimum", -1,
                "maximum", 1)),
            Map.entry("category", Map.of(
                "type", "string",
                "enum", List.of(
                    "EARNINGS",
                    "REGULATORY",
                    "MACRO",
                    "CORPORATE_ACTION",
                    "MANAGEMENT",
                    "SECTOR",
                    "OTHER"))),
            Map.entry("confidence", Map.of("type", "number", "minimum", 0, "maximum", 1)),
            Map.entry("materiality", Map.of("type", "number", "minimum", 0, "maximum", 1)),
            Map.entry("horizon", Map.of(
                "type", "string",
                "enum", List.of("INTRADAY", "MULTI_DAY", "LONGER_TERM", "UNKNOWN"))),
            Map.entry("novelty", Map.of("type", "number", "minimum", 0, "maximum", 1)),
            Map.entry("contradiction", Map.of("type", "boolean")),
            Map.entry("ambiguity", Map.of("type", "boolean")),
            Map.entry("impactedSymbols", Map.of(
                "type", "array",
                "maxItems", 10,
                "items", Map.of("type", "string"))),
            Map.entry("evidence", Map.of("type", "string")),
            Map.entry("risks", Map.of(
                "type", "array",
                "maxItems", 5,
                "items", Map.of("type", "string"))));
        return Map.of(
            "type", "object",
            "additionalProperties", false,
            "properties", properties,
            "required", List.of(
                "sentiment",
                "directionScore",
                "category",
                "confidence",
                "materiality",
                "horizon",
                "novelty",
                "contradiction",
                "ambiguity",
                "impactedSymbols",
                "evidence",
                "risks"));
    }
}
