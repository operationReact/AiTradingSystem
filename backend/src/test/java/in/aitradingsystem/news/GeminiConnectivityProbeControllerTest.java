package in.aitradingsystem.news;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.aitradingsystem.config.NewsProperties;
import in.aitradingsystem.news.analysis.AiErrorCode;
import in.aitradingsystem.news.analysis.AiProviderException;
import in.aitradingsystem.news.analysis.GeminiNewsAnalysisProvider;
import in.aitradingsystem.news.analysis.NewsAnalysisParser;
import in.aitradingsystem.news.analysis.NewsAnalysisProvider.Completion;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

class GeminiConnectivityProbeControllerTest {
    private final GeminiNewsAnalysisProvider provider = mock(GeminiNewsAnalysisProvider.class);
    private final ObjectMapper mapper = new ObjectMapper();

    private GeminiConnectivityProbeController controller(String selected) {
        when(provider.model()).thenReturn("gemini-test");
        return new GeminiConnectivityProbeController(
            new NewsProperties(selected, "gemini-test", "claude-test", "fixture-key", "", 1000, 5000, 2),
            provider, new NewsAnalysisParser(mapper));
    }

    private Completion response(String challenge, String id) {
        return new Completion("""
            {"sentiment":"NEUTRAL","directionScore":0,"category":"OTHER",
             "confidence":1,"materiality":0,"horizon":"UNKNOWN","novelty":0,
             "contradiction":false,"ambiguity":false,"impactedSymbols":[],
             "evidence":"%s","risks":[]}
            """.formatted(challenge), id);
    }

    @Test
    void disabledProviderDoesNotCallRemote() {
        var result = controller("NONE").verify().getBody();
        assertThat(result.providerAttempted()).isFalse();
        assertThat(result.structuredConnectivityVerified()).isFalse();
        verify(provider, never()).complete(anyString(), anyString());
    }

    @Test
    void validatesChallengeAndRequestIdAndRateLimitsWithoutRetry() {
        when(provider.configured()).thenReturn(true);
        when(provider.complete(anyString(), anyString())).thenAnswer(invocation -> {
            String prompt = invocation.getArgument(1);
            return response(prompt.substring(prompt.indexOf("MINTTICK-DIAGNOSTIC-")), "fixture-id");
        });
        var controller = controller("GEMINI");
        var result = controller.verify();
        assertThat(result.getBody().structuredConnectivityVerified()).isTrue();
        assertThat(result.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(result.getBody().toString()).doesNotContain("fixture-id", "fixture-key", "MINTTICK-DIAGNOSTIC-");
        assertThatThrownBy(controller::verify).isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("429");
        verify(provider, times(1)).complete(anyString(), anyString());
        var system = ArgumentCaptor.forClass(String.class);
        var input = ArgumentCaptor.forClass(String.class);
        verify(provider).complete(system.capture(), input.capture());
        assertThat(system.getValue()).isNotEqualTo(NewsAnalysisService.SYSTEM_PROMPT)
            .contains("synthetic structured-connectivity diagnostic",
                "evidence field must equal the user message exactly");
        assertThat(input.getValue()).matches("MINTTICK-DIAGNOSTIC-[0-9a-f-]{36}");
    }

    @Test
    void staleChallengeOrMissingIdCannotVerify() {
        when(provider.configured()).thenReturn(true);
        when(provider.complete(anyString(), anyString())).thenReturn(response("stale", "fixture-id"));
        assertThat(controller("GEMINI").verify().getBody().structuredConnectivityVerified()).isFalse();
        when(provider.complete(anyString(), anyString())).thenAnswer(invocation -> {
            String prompt = invocation.getArgument(1);
            return response(prompt.substring(prompt.indexOf("MINTTICK-DIAGNOSTIC-")), null);
        });
        assertThat(controller("GEMINI").verify().getBody().structuredConnectivityVerified()).isFalse();
    }

    @Test
    void failedTransportReturnsSafeCodeWithoutRetry() {
        when(provider.configured()).thenReturn(true);
        when(provider.complete(anyString(), anyString())).thenThrow(
            new AiProviderException(AiErrorCode.RATE_LIMITED, "fixture-sensitive-detail"));
        var result = controller("GEMINI").verify().getBody();
        assertThat(result.outcome()).isEqualTo("RATE_LIMITED");
        assertThat(result.structuredConnectivityVerified()).isFalse();
        assertThat(result.toString()).doesNotContain("fixture-sensitive-detail");
        verify(provider, times(1)).complete(anyString(), anyString());
    }

    @Test
    void malformedStructuredResponseCannotVerifyOrTriggerRetry() {
        when(provider.configured()).thenReturn(true);
        when(provider.complete(anyString(), anyString())).thenReturn(
            new Completion("{\"private\":\"fixture-provider-content\"}", "fixture-request-id"));
        var result = controller("GEMINI").verify().getBody();
        assertThat(result.outcome()).isEqualTo("INVALID_RESPONSE");
        assertThat(result.providerAttempted()).isTrue();
        assertThat(result.structuredConnectivityVerified()).isFalse();
        assertThat(result.toString()).doesNotContain("fixture-provider-content", "fixture-request-id");
        verify(provider, times(1)).complete(anyString(), anyString());
    }
}
