package in.aitradingsystem.intelligence;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/**
 * V83: a distinct, operator-owned DNS identity required for non-loopback
 * research worker calls. Prevents an arbitrary but valid HTTPS URL in the
 * base-url configuration from receiving the private worker token.
 *
 * Java HttpClient still performs normal CA-chain and hostname verification.
 * No trust-all TLS, redirect, retry or capital/order authority is introduced.
 */
final class ResearchWorkerTlsOrigin {
    private ResearchWorkerTlsOrigin() {}

    static void requirePinned(URI endpoint, String expectedHost) {
        String actual = endpoint.getHost();
        if (actual == null) {
            throw new IntelligenceUnavailableException("Research worker origin unavailable");
        }
        boolean loopback = Set.of("127.0.0.1", "localhost", "::1", "[::1]")
            .contains(actual.toLowerCase(Locale.ROOT));
        // Only local synthetic tests may use HTTP without certificate validation.
        if (loopback && "http".equals(endpoint.getScheme())) {
            return;
        }
        String host = expectedHost == null ? "" : expectedHost.trim().toLowerCase(Locale.ROOT);
        if (!"https".equals(endpoint.getScheme())
                || (endpoint.getPort() != -1 && endpoint.getPort() != 443)
                || host.length() < 4 || host.length() > 253
                || !host.contains(".")
                || !host.matches("[a-z0-9](?:[a-z0-9.-]*[a-z0-9])")
                || host.contains("..")
                || !host.equals(actual.toLowerCase(Locale.ROOT))) {
            throw new IntelligenceUnavailableException("Trusted worker HTTPS origin mismatch");
        }
    }

    /** Synthetic test seam only; production receives an independent env pin. */
    static String syntheticTestPin(String baseUrl) {
        try {
            URI uri = URI.create(baseUrl);
            return uri.getHost() == null ? "" : uri.getHost();
        } catch (RuntimeException invalid) {
            return "";
        }
    }
}
