package in.aitradingsystem.news.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.aitradingsystem.config.NewsProperties;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ClaudeNewsAnalysisProvider extends AbstractHttpAnalysisProvider {
    private static final String BASE_URL = "https://api.anthropic.com";
    private static final String API_VERSION = "2023-06-01";

    public ClaudeNewsAnalysisProvider(NewsProperties props, ObjectMapper mapper) {
        super(BASE_URL, props, mapper);
    }

    @Override
    public String name() {
        return "CLAUDE";
    }

    @Override
    public String model() {
        return props.claudeModel();
    }

    @Override
    public boolean configured() {
        return props.claudeApiKey() != null && !props.claudeApiKey().isBlank();
    }

    @Override
    public Completion complete(String systemPrompt, String userPrompt) {
        if (!configured()) {
            throw new AiProviderException(AiErrorCode.UNCONFIGURED, "ANTHROPIC_API_KEY is not set");
        }
        Map<String, Object> body = Map.of(
            "model", model(),
            "max_tokens", 700,
            "temperature", 0,
            "system", systemPrompt,
            "messages", List.of(Map.of("role", "user", "content", userPrompt)));
        JsonNode root = post(
            "/v1/messages",
            Map.of("x-api-key", props.claudeApiKey(), "anthropic-version", API_VERSION),
            body);
        for (JsonNode block : root.path("content")) {
            if ("text".equals(block.path("type").asText()) && block.path("text").isTextual()) {
                String requestId = root.path("id").isTextual() ? root.path("id").asText() : null;
                return new Completion(block.path("text").asText(), requestId);
            }
        }
        throw new AiProviderException(AiErrorCode.INVALID_RESPONSE, "CLAUDE returned no text content");
    }
}
