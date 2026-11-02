package in.aitradingsystem.marketdata;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class OfficialMarketSourceClient {
    static final URI NIFTY50_CONSTITUENTS_URI = URI.create(
            "https://www.niftyindices.com/IndexConstituent/ind_nifty50list.csv");
    private static final Set<String> ACCEPTED_MEDIA_TYPES = Set.of(
            "text/csv",
            "text/plain",
            "application/octet-stream");

    private final HttpClient client;

    public OfficialMarketSourceClient() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    OfficialMarketSourceClient(HttpClient client) {
        this.client = client;
    }

    public FetchedPayload fetchNifty50Constituents() throws IOException, InterruptedException {
        return fetchGet(
                OfficialMarketSourceFamily.NIFTY50_CONSTITUENTS,
                NIFTY50_CONSTITUENTS_URI);
    }

    private FetchedPayload fetchGet(OfficialMarketSourceFamily family, URI uri)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(12))
                .header("Accept", "text/csv, text/plain, application/octet-stream")
                .header("User-Agent", "AiTradingSystem-ResearchSource/1.0")
                .GET()
                .build();
        HttpResponse<InputStream> response = client.send(
                request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = response.body()) {
            if (response.statusCode() != 200) {
                throw new IOException("Official source returned HTTP " + response.statusCode());
            }
            String contentType = response.headers().firstValue("Content-Type").orElse("")
                    .trim().toLowerCase(Locale.ROOT);
            String mediaType = contentType.split(";", 2)[0].trim();
            if (!ACCEPTED_MEDIA_TYPES.contains(mediaType)) {
                throw new IOException("Official source returned an unsupported content type");
            }
            byte[] payload = body.readNBytes(family.maxBytes() + 1);
            if (payload.length == 0) {
                throw new IOException("Official source returned an empty payload");
            }
            if (payload.length > family.maxBytes()) {
                throw new IOException("Official source exceeded the configured payload limit");
            }
            return new FetchedPayload(uri.toString(), contentType, payload, Instant.now());
        }
    }

    public record FetchedPayload(
            String sourceUrl,
            String contentType,
            byte[] payload,
            Instant observedAt) {
        public FetchedPayload {
            payload = payload == null ? null : payload.clone();
        }

        @Override
        public byte[] payload() {
            return payload == null ? null : payload.clone();
        }
    }
}
