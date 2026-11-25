package in.aitradingsystem.news;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
public class RssFeedClient {
    private final HttpClient client;

    public RssFeedClient() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER).build());
    }

    RssFeedClient(HttpClient client) {
        this.client = client;
    }

    public byte[] fetch(NewsSource source, int maxBytes) throws IOException, InterruptedException {
        if (source == null || source.feedUri() == null || !NewsSource.SUPPORTED.contains(source)) {
            throw new IOException("Unsupported RSS source");
        }
        if (!"https".equalsIgnoreCase(source.feedUri().getScheme())) {
            throw new IOException("RSS source must use HTTPS");
        }
        if (maxBytes < 1 || maxBytes > 2_097_152) {
            throw new IllegalArgumentException("RSS payload limit must be between 1 and 2097152 bytes");
        }
        HttpRequest request = HttpRequest.newBuilder(source.feedUri())
            .timeout(Duration.ofSeconds(5))
            .header("Accept", "application/rss+xml, application/xml, text/xml")
            .header("User-Agent", "AiTradingSystem-NewsReader/1.0")
            .GET().build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = response.body()) {
            if (response.statusCode() != 200) {
                throw new IOException("News provider returned HTTP " + response.statusCode());
            }
            String contentType = response.headers().firstValue("Content-Type").orElse("")
                .toLowerCase(java.util.Locale.ROOT);
            if (!contentType.startsWith("application/rss+xml")
                && !contentType.startsWith("application/xml")
                && !contentType.startsWith("text/xml")) {
                throw new IOException("News provider returned an unsupported content type");
            }
            byte[] data = body.readNBytes(maxBytes + 1);
            if (data.length > maxBytes) throw new IOException("News feed exceeds the configured payload limit");
            return data;
        }
    }
}
