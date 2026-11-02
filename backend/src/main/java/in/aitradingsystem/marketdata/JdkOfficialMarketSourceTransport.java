package in.aitradingsystem.marketdata;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class JdkOfficialMarketSourceTransport implements OfficialMarketSourceTransport {
    private static final Set<String> ALLOWED_HOSTS = Set.of(
            "nseindia.com",
            "www.nseindia.com",
            "nsearchives.nseindia.com",
            "niftyindices.com",
            "www.niftyindices.com");
    private static final Set<Integer> RETRYABLE_STATUS = Set.of(429, 500, 502, 503, 504);

    private final Duration connectTimeout;
    private final Duration requestTimeout;
    private final int maxAttempts;
    private final long retryBackoffMs;
    private final String userAgent;

    public JdkOfficialMarketSourceTransport(
            @Value("${app.market-data.official-sources.connect-timeout-ms:5000}") long connectTimeoutMs,
            @Value("${app.market-data.official-sources.request-timeout-ms:15000}") long requestTimeoutMs,
            @Value("${app.market-data.official-sources.max-attempts:2}") int maxAttempts,
            @Value("${app.market-data.official-sources.retry-backoff-ms:250}") long retryBackoffMs,
            @Value("${app.market-data.official-sources.user-agent:AiTradingSystem/1.0 research-market-data}") String userAgent) {
        if (connectTimeoutMs < 250 || connectTimeoutMs > 30_000) {
            throw new IllegalArgumentException("Official source connect timeout must be between 250 and 30000 ms");
        }
        if (requestTimeoutMs < 500 || requestTimeoutMs > 60_000) {
            throw new IllegalArgumentException("Official source request timeout must be between 500 and 60000 ms");
        }
        if (maxAttempts < 1 || maxAttempts > 3) {
            throw new IllegalArgumentException("Official source max attempts must be between 1 and 3");
        }
        if (retryBackoffMs < 0 || retryBackoffMs > 5_000) {
            throw new IllegalArgumentException("Official source retry backoff must be between 0 and 5000 ms");
        }
        if (userAgent == null || userAgent.isBlank() || userAgent.length() > 200) {
            throw new IllegalArgumentException("Official source user agent is invalid");
        }
        this.connectTimeout = Duration.ofMillis(connectTimeoutMs);
        this.requestTimeout = Duration.ofMillis(requestTimeoutMs);
        this.maxAttempts = maxAttempts;
        this.retryBackoffMs = retryBackoffMs;
        this.userAgent = userAgent.trim();
    }

    @Override
    public Response get(URI sourceUri, URI bootstrapUri, int maxBytes) {
        validateUri(sourceUri);
        if (bootstrapUri != null) validateUri(bootstrapUri);
        if (maxBytes < 1 || maxBytes > 10_000_000) {
            throw new MarketDataException("INVALID_OFFICIAL_SOURCE_MAX_BYTES",
                    "Official source response limit is invalid");
        }

        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER);
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .cookieHandler(cookies)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        if (bootstrapUri != null) {
            fetch(client, bootstrapUri, Math.min(maxBytes, 1_000_000), false);
        }
        return fetch(client, sourceUri, maxBytes, true);
    }

    private Response fetch(HttpClient client, URI uri, int maxBytes, boolean requireSuccessPayload) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder(uri)
                        .timeout(requestTimeout)
                        .header("User-Agent", userAgent)
                        .header("Accept", "text/csv,application/json,text/plain,application/octet-stream;q=0.8")
                        .header("Accept-Language", "en-US,en;q=0.9")
                        .header("Accept-Encoding", "identity")
                        .GET()
                        .build();
                HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                int status = response.statusCode();
                if (status >= 300 && status < 400) {
                    closeQuietly(response.body());
                    throw new MarketDataException("OFFICIAL_SOURCE_REDIRECT_REJECTED",
                            "Official source redirects are rejected to preserve host allowlisting");
                }
                if (status < 200 || status >= 300) {
                    closeQuietly(response.body());
                    MarketDataException failure = new MarketDataException("OFFICIAL_SOURCE_HTTP_" + status,
                            "Official source returned HTTP " + status);
                    if (RETRYABLE_STATUS.contains(status) && attempt < maxAttempts) {
                        last = failure;
                        sleepBeforeRetry(attempt);
                        continue;
                    }
                    throw failure;
                }
                long declaredLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
                if (declaredLength > maxBytes) {
                    closeQuietly(response.body());
                    throw new MarketDataException("OFFICIAL_SOURCE_PAYLOAD_TOO_LARGE",
                            "Official source response exceeds the configured family limit");
                }
                byte[] payload = readBounded(response.body(), maxBytes);
                if (requireSuccessPayload && payload.length == 0) {
                    throw new MarketDataException("EMPTY_OFFICIAL_SOURCE_PAYLOAD",
                            "Official source response payload is empty");
                }
                String contentType = response.headers().firstValue("Content-Type")
                        .orElse("application/octet-stream");
                return new Response(contentType, payload);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new MarketDataException("OFFICIAL_SOURCE_FETCH_INTERRUPTED",
                        "Official source retrieval was interrupted");
            } catch (IOException ex) {
                last = new MarketDataException("OFFICIAL_SOURCE_NETWORK_FAILURE",
                        "Official source retrieval failed due to a network error");
                if (attempt < maxAttempts) {
                    sleepBeforeRetry(attempt);
                    continue;
                }
                throw last;
            }
        }
        throw last == null
                ? new MarketDataException("OFFICIAL_SOURCE_FETCH_FAILED", "Official source retrieval failed")
                : last;
    }

    private byte[] readBounded(InputStream input, int maxBytes) throws IOException {
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(maxBytes, 64 * 1024))) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw new MarketDataException("OFFICIAL_SOURCE_PAYLOAD_TOO_LARGE",
                            "Official source response exceeds the configured family limit");
                }
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    private void validateUri(URI uri) {
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null
                || !ALLOWED_HOSTS.contains(uri.getHost().toLowerCase(Locale.ROOT))) {
            throw new MarketDataException("OFFICIAL_SOURCE_URL_NOT_ALLOWED",
                    "Official source URL must use an allowlisted HTTPS NSE host");
        }
    }

    private void sleepBeforeRetry(int attempt) {
        if (retryBackoffMs == 0) return;
        try {
            Thread.sleep(Math.min(retryBackoffMs * attempt, 5_000L));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new MarketDataException("OFFICIAL_SOURCE_FETCH_INTERRUPTED",
                    "Official source retrieval was interrupted");
        }
    }

    private void closeQuietly(InputStream input) {
        if (input == null) return;
        try {
            input.close();
        } catch (IOException ignored) {
            // Best-effort cleanup only.
        }
    }
}
