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
    public String complete(String systemPrompt, String userPrompt) {
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
        return extractOutputText(root);
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

    private static Map<String, Object> responseSchema() {
        return Map.of(
            "type", "object",
            "additionalProperties", false,
            "properties", Map.of(
                "sentiment", Map.of(
                    "type", "string",
                    "enum", List.of("POSITIVE", "NEGATIVE", "NEUTRAL", "MIXED")),
                "category", Map.of(
                    "type", "string",
                    "enum", List.of(
                        "EARNINGS",
                        "REGULATORY",
                        "MACRO",
                        "CORPORATE_ACTION",
                        "MANAGEMENT",
                        "SECTOR",
                        "OTHER")),
                "confidence", Map.of(
                    "type", "number",
                    "minimum", 0,
                    "maximum", 1),
                "impactedSymbols", Map.of(
                    "type", "array",
                    "maxItems", 10,
                    "items", Map.of("type", "string")),
                "evidence", Map.of("type", "string"),
                "risks", Map.of(
                    "type", "array",
                    "maxItems", 5,
                    "items", Map.of("type", "string"))),
            "required", List.of(
                "sentiment",
                "category",
                "confidence",
                "impactedSymbols",
                "evidence",
                "risks"));
    }
}
