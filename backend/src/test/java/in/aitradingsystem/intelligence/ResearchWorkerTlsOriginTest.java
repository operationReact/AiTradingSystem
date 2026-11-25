package in.aitradingsystem.intelligence;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * V83: external HTTPS URLs never receive worker credentials without an
 * independently pinned hostname; local synthetic HTTP remains testable.
 */
class ResearchWorkerTlsOriginTest {
    private static final String HOST = "research-worker.example.com";
    private static final String TOKEN = "x".repeat(40);

    @Test
    void exactTrustedHostnameAndVerifiedHttpsPortAreRequired() {
        assertDoesNotThrow(() -> ResearchWorkerTlsOrigin.requirePinned(
            URI.create("https://research-worker.example.com/v1/research/artifact-readiness"),
            HOST));
        assertDoesNotThrow(() -> ResearchWorkerTlsOrigin.requirePinned(
            URI.create("https://research-worker.example.com:443/v1/research/artifact-inference"),
            "RESEARCH-WORKER.EXAMPLE.COM"));
        assertThrows(IntelligenceUnavailableException.class, () ->
            ResearchWorkerTlsOrigin.requirePinned(
                URI.create("https://research-worker.example.com:8443/x"), HOST));
    }

    @Test
    void missingPinOtherHttpsHostsAndPlainHttpFailClosed() {
        for (String pin : new String[]{"", "other.example.com", "example.com",
                "*.example.com", "research-worker.example.com.evil.test"}) {
            assertThrows(IntelligenceUnavailableException.class, () ->
                ResearchWorkerTlsOrigin.requirePinned(
                    URI.create("https://research-worker.example.com/x"), pin));
        }
        for (String url : new String[]{"https://evil.example/x",
                "https://research-worker.example.com.evil/x",
                "http://research-worker.example.com/x",
                "ftp://research-worker.example.com/x"}) {
            assertThrows(IntelligenceUnavailableException.class, () ->
                ResearchWorkerTlsOrigin.requirePinned(URI.create(url), HOST));
        }
    }

    @Test
    void localSyntheticHttpRetainsNoNetworkReleaseAuthority() {
        for (String host : new String[]{"localhost", "127.0.0.1", "[::1]"}) {
            assertDoesNotThrow(() -> ResearchWorkerTlsOrigin.requirePinned(
                URI.create("http://" + host + ":8090/x"), ""));
        }
        assertEquals("research-worker.example.com",
            ResearchWorkerTlsOrigin.syntheticTestPin("https://research-worker.example.com"));
        assertEquals("", ResearchWorkerTlsOrigin.syntheticTestPin("%%%"));
    }

    @Test
    void inferenceRejectsMismatchedHostBeforeTokenBearingTransport() {
        AtomicInteger calls = new AtomicInteger();
        var client = new ResearchArtifactInferenceClient(
            new ObjectMapper(), "https://untrusted.example.com", TOKEN,
            true, HOST, (endpoint, body, token) -> {
                calls.incrementAndGet();
                throw new AssertionError("Untrusted worker request must never be dispatched");
            });
        var failure = assertThrows(IntelligenceUnavailableException.class,
            () -> client.score(null, null));
        assertTrue(failure.getMessage().toLowerCase().contains("origin"));
        assertEquals(0, calls.get());
    }
}
