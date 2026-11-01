package in.aitradingsystem.intelligence;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Internal adapter to the Python intelligence plane. It is deliberately disconnected from order execution:
 * evidence can inform research/signal fusion later, but Java Risk Engine remains the only capital gate.
 */
@Component
public class HttpIntelligenceClient implements IntelligenceClient {

    private final RestClient restClient;
    private final boolean enabled;

    public HttpIntelligenceClient(RestClient.Builder builder,
                                  @Value("${app.intelligence.base-url:http://localhost:8090}") String baseUrl,
                                  @Value("${app.intelligence.enabled:false}") boolean enabled) {
        this.restClient = builder.baseUrl(baseUrl).build();
        this.enabled = enabled;
    }

    @Override
    public IntelligenceEvidenceResponse validate(IntelligenceEvidenceRequest request) {
        if (!enabled) {
            throw new IntelligenceUnavailableException("Python intelligence service is disabled");
        }
        IntelligenceEvidenceResponse response = restClient.post()
                .uri("/v1/contracts/validate")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(IntelligenceEvidenceResponse.class);
        if (response == null || !response.accepted()) {
            throw new IntelligenceUnavailableException("Python intelligence service did not accept the request");
        }
        if (response.canAuthorizeCapital()) {
            throw new IntelligenceUnavailableException("Invalid intelligence response attempted capital authorization");
        }
        return response;
    }
}
