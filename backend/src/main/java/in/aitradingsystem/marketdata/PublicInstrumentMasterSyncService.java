package in.aitradingsystem.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Imports the public Angel One instrument master only; it never authenticates or places orders. */
@Service
public class PublicInstrumentMasterSyncService {
    static final int MAX_BYTES = 50 * 1024 * 1024;
    static final int MAX_ROWS = 500_000;

    private final in.aitradingsystem.instrument.InstrumentMasterService instrumentService;
    private final ObjectMapper mapper;
    private final HttpClient client;
    private final URI masterUri;

    public PublicInstrumentMasterSyncService(
        in.aitradingsystem.instrument.InstrumentMasterService instrumentService,
        ObjectMapper mapper,
        @Value("${app.market-data.instrument-master-url:https://margincalculator.angelone.in/OpenAPI_File/files/OpenAPIScripMaster.json}") String masterUrl
    ) {
        this.instrumentService = instrumentService;
        this.mapper = mapper;
        this.masterUri = URI.create(masterUrl);
        if (!"https".equalsIgnoreCase(masterUri.getScheme())
            || !"margincalculator.angelone.in".equalsIgnoreCase(masterUri.getHost())) {
            throw new IllegalArgumentException("Instrument master URL must use the approved Angel One HTTPS host");
        }
        this.client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    }

    public record SyncResult(Object catalogueSummary, String source, String status) {}

    public SyncResult sync() {
        try {
            HttpRequest request = HttpRequest.newBuilder(masterUri)
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .GET().build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                response.body().close();
                throw new IllegalStateException("Instrument master provider returned HTTP " + response.statusCode());
            }
            byte[] body;
            try (InputStream stream = response.body()) {
                body = stream.readNBytes(MAX_BYTES + 1);
            }
            if (body.length == 0 || body.length > MAX_BYTES) {
                throw new IllegalStateException("Instrument master payload is empty or exceeds the 50 MiB limit");
            }
            JsonNode root = mapper.readTree(body);
            if (!root.isArray() || root.isEmpty() || root.size() > MAX_ROWS) {
                throw new IllegalStateException("Instrument master must be a non-empty JSON array of at most 500,000 rows");
            }
            // Reuse the existing versioned catalogue importer. It validates rows, tracks import audit,
            // atomically publishes the active catalogue, and protects the previous catalogue on failure.
            in.aitradingsystem.instrument.InstrumentImportSummary summary =
                instrumentService.importSource(body);
            if (!"SUCCEEDED".equalsIgnoreCase(summary.status())) {
                throw new IllegalStateException("Instrument catalogue import failed: " + summary.errorCode());
            }
            return new SyncResult(instrumentService.catalogueSummary(), "ANGEL_ONE_MASTER", "SUCCESS");
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read instrument master JSON", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Instrument master sync was interrupted", e);
        }
    }

}
