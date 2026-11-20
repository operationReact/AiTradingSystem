package in.aitradingsystem.provider.upstox;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/**
 * Fetches the published Upstox instrument master (public file, no credentials).
 * Bounded by size and time. Returns raw bytes and a SHA-256 for audit. The caller
 * parses with UpstoxFuturesInstrumentMaster, which fails closed.
 */
@Component
public class FuturesInstrumentMasterFetcher {
    static final long MAX_COMPRESSED_BYTES = 20L * 1024 * 1024;

    public record Fetched(byte[] bytes, String sha256) {}

    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER).build();

    public Fetched fetch() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(UpstoxFuturesInstrumentMaster.OFFICIAL_URL))
            .timeout(Duration.ofMinutes(2)).GET().build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException("INSTRUMENT_MASTER_HTTP_" + response.statusCode());
        }
        byte[] body = response.body();
        if (body.length == 0 || body.length > MAX_COMPRESSED_BYTES) {
            throw new IOException("INSTRUMENT_MASTER_SIZE_OUT_OF_BOUNDS");
        }
        try {
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
            return new Fetched(body, digest);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IOException("SHA256_UNAVAILABLE", impossible);
        }
    }
}
