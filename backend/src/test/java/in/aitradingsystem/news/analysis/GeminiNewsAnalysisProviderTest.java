package in.aitradingsystem.news.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.aitradingsystem.config.NewsProperties;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GeminiNewsAnalysisProviderTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void buildsNonStoredStructuredInteractionWithoutTools() {
        GeminiNewsAnalysisProvider provider = provider();

        Map<String, Object> body = provider.requestBody("system", "user");

        assertThat(body).containsEntry("model", "gemini-2.5-flash");
        assertThat(body).containsEntry("input", "user");
        assertThat(body).containsEntry("system_instruction", "system");
        assertThat(body).containsEntry("store", false);

        @SuppressWarnings("unchecked")
        Map<String, Object> generation = (Map<String, Object>) body.get("generation_config");
        assertThat(generation).containsEntry("tool_choice", "none");
        assertThat(generation).containsEntry("temperature", 0);

        @SuppressWarnings("unchecked")
        Map<String, Object> responseFormat = (Map<String, Object>) body.get("response_format");
        assertThat(responseFormat).containsEntry("mime_type", "application/json");

        @SuppressWarnings("unchecked")
        Map<String, Object> schema = (Map<String, Object>) responseFormat.get("schema");
        assertThat(schema).containsEntry("additionalProperties", false);
        assertThat(schema.get("required")).isEqualTo(List.of(
            "sentiment", "category", "confidence", "impactedSymbols", "evidence", "risks"));
    }

    @Test
    void extractsOnlyModelOutputTextFromCompletedInteractionShape() throws Exception {
        JsonNode root = mapper.readTree("""
            {
              "status": "completed",
              "steps": [
                {"type":"user_input","content":[{"type":"text","text":"ignored"}]},
                {"type":"model_output","content":[{"type":"text","text":"{\\"sentiment\\":\\"NEUTRAL\\"}"}]}
              ]
            }
            """);

        assertThat(GeminiNewsAnalysisProvider.extractOutputText(root))
            .isEqualTo("{\"sentiment\":\"NEUTRAL\"}");
    }

    @Test
    void rejectsInteractionWithoutModelOutputText() throws Exception {
        JsonNode root = mapper.readTree("""
            {"status":"completed","steps":[{"type":"model_output","content":[]}]}
            """);

        assertThatThrownBy(() -> GeminiNewsAnalysisProvider.extractOutputText(root))
            .isInstanceOf(AiProviderException.class)
            .hasMessageContaining("structured text content");
    }

    private GeminiNewsAnalysisProvider provider() {
        NewsProperties props = new NewsProperties(
            "GEMINI",
            "gemini-2.5-flash",
            "claude-test",
            "test-key",
            "",
            1000,
            5000,
            2);
        return new GeminiNewsAnalysisProvider(props, mapper);
    }
}
