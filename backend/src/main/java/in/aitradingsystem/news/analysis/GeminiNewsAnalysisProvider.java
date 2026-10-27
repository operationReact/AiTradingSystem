package in.aitradingsystem.news.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.aitradingsystem.config.NewsProperties;
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
        if (!configured()) throw new AiProviderException(AiErrorCode.UNCONFIGURED, "GEMINI_API_KEY is not set");
        Map<String, Object> body = Map.of(
            "systemInstruction", Map.of("parts", java.util.List.of(Map.of("text", systemPrompt))),
            "contents", java.util.List.of(Map.of("role", "user", "parts", java.util.List.of(Map.of("text", userPrompt)))),
            "generationConfig", Map.of("responseMimeType", "application/json", "temperature", 0));
        JsonNode root = post(
            "/v1beta/models/" + model() + ":generateContent",
            Map.of("x-goog-api-key", props.geminiApiKey()),
            body);
        JsonNode text = root.path("candidates").path(0).path("content").path("parts").path(0).path("text");
        if (!text.isTextual()) throw new AiProviderException(AiErrorCode.INVALID_RESPONSE, "GEMINI returned no text content");
        return text.asText();
    }
}
