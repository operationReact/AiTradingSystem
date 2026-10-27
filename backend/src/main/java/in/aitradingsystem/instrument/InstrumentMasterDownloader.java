package in.aitradingsystem.instrument;

import in.aitradingsystem.config.AngelOneProperties;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.stereotype.Component;

/** Downloads only from the configured, allow-listed Angel One instrument-master HTTPS URL. */
@Component
public class InstrumentMasterDownloader {
    private final AngelOneProperties properties;
    private final HttpClient client;

    public InstrumentMasterDownloader(AngelOneProperties properties) {
        this.properties = properties;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.connectTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public byte[] download() {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(java.net.URI.create(properties.instrumentMasterUrl()))
                .timeout(Duration.ofMillis(properties.readTimeoutMs()))
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() != 200) {
                    throw new InstrumentMasterException("SOURCE_HTTP_" + response.statusCode(),
                            "Angel One instrument-master source returned HTTP " + response.statusCode());
                }
                return readBounded(body, properties.instrumentMasterMaxBytes());
            }
        } catch (InstrumentMasterException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InstrumentMasterException("SOURCE_INTERRUPTED", "Instrument-master download was interrupted");
        } catch (IOException | IllegalArgumentException e) {
            throw new InstrumentMasterException("SOURCE_UNAVAILABLE", "Angel One instrument-master source could not be read");
        }
    }

    private static byte[] readBounded(InputStream input, int maxBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maxBytes, 1_048_576));
        byte[] buffer = new byte[16 * 1024];
        int count;
        int total = 0;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > maxBytes) {
                throw new InstrumentMasterException("SOURCE_TOO_LARGE", "Instrument-master download exceeds configured size limit");
            }
            output.write(buffer, 0, count);
        }
        if (total == 0) {
            throw new InstrumentMasterException("SOURCE_EMPTY", "Instrument-master source returned an empty body");
        }
        return output.toByteArray();
    }
}
