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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Imports the public Angel One instrument master only; it never authenticates or places orders. */
@Service
public class InstrumentMasterService {
    static final int MAX_BYTES = 50 * 1024 * 1024;
    static final int MAX_ROWS = 500_000;

    private final MarketDataInstrumentRepository repository;
    private final ObjectMapper mapper;
    private final HttpClient client;
    private final URI masterUri;

    public InstrumentMasterService(
        MarketDataInstrumentRepository repository,
        ObjectMapper mapper,
        @Value("${app.market-data.instrument-master-url:https://margincalculator.angelone.in/OpenAPI_File/files/OpenAPIScripMaster.json}") String masterUrl
    ) {
        this.repository = repository;
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

    public record MasterRow(String exchange, String symbolToken, String tradingSymbol, String name,
                            String instrumentType, LocalDate expiry, BigDecimal strikePrice,
                            Integer lotSize, BigDecimal tickSize) {}
    public record SyncResult(int importedRows, String source, String status) {}

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
            List<MasterRow> rows = new ArrayList<>(root.size());
            for (JsonNode item : root) {
                MasterRow row = parse(item);
                if (row != null) rows.add(row);
            }
            if (rows.isEmpty()) throw new IllegalStateException("No valid cash-equity instruments were found");
            int written = repository.upsertBatch(rows);
            return new SyncResult(written, "ANGEL_ONE_MASTER", "SUCCESS");
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read instrument master JSON", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Instrument master sync was interrupted", e);
        }
    }

    private MasterRow parse(JsonNode item) {
        String exchange = text(item, "exch_seg");
        String token = text(item, "token");
        String symbol = text(item, "symbol");
        String name = text(item, "name");
        String type = text(item, "instrumenttype");
        // Phase 2 is cash-equity discovery only; derivatives are deliberately excluded.
        if (!"NSE".equals(exchange) && !"BSE".equals(exchange)) return null;
        if (token == null || symbol == null || !symbol.matches("[A-Za-z0-9&._-]{1,100}")) return null;
        if (type != null && !type.isBlank() && !"EQ".equalsIgnoreCase(type)) return null;
        Integer lot = integer(item, "lotsize");
        BigDecimal tick = decimal(item, "tick_size");
        return new MasterRow(exchange, token, symbol, name, "EQ", null, BigDecimal.ZERO,
            lot == null || lot < 1 ? 1 : lot, tick == null || tick.signum() <= 0 ? new BigDecimal("0.05") : tick);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText().trim();
    }

    private static Integer integer(JsonNode node, String field) {
        try { String value = text(node, field); return value == null || value.isBlank() ? null : Integer.valueOf(value); }
        catch (NumberFormatException ignored) { return null; }
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        try { String value = text(node, field); return value == null || value.isBlank() ? null : new BigDecimal(value); }
        catch (NumberFormatException ignored) { return null; }
    }
}