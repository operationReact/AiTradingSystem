package in.aitradingsystem.provider.upstox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Read-only Upstox NSE EQ beginning-of-day ISIN reference. Broker tokens are not
 * interchangeable: resolve by the exact active NSE equity trading symbol only.
 * A failed or truncated refresh never replaces the last verified reference.
 */
@Component
public final class UpstoxNseEquityIsinSource {
    private static final Logger log = LoggerFactory.getLogger(UpstoxNseEquityIsinSource.class);
    static final URI SOURCE = URI.create(
        "https://assets.upstox.com/market-quote/instruments/exchange/NSE.json.gz");
    private static final Pattern ISIN = Pattern.compile("[A-Z]{2}[A-Z0-9]{9}[0-9]");
    private static final Pattern SYMBOL = Pattern.compile("[A-Z0-9&._/+-]{1,100}");
    private static final int MAX_COMPRESSED = 32 * 1024 * 1024;
    private static final int MAX_UNCOMPRESSED = 80 * 1024 * 1024;
    private static final Duration MAX_AGE = Duration.ofHours(36);
    private final ObjectMapper mapper;
    private final Clock clock;
    private final HttpClient client;
    private volatile Snapshot snapshot = new Snapshot(Map.of(), Instant.EPOCH);

    public record Snapshot(Map<String, String> symbolToIsin, Instant fetchedAt) {}
    public record Parse(Map<String, String> symbolToIsin, int rawRows, int validRows) {}

    public UpstoxNseEquityIsinSource(ObjectMapper mapper) {
        this(mapper, Clock.systemUTC(), HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build());
    }

    UpstoxNseEquityIsinSource(ObjectMapper mapper, Clock clock, HttpClient client) {
        this.mapper = mapper;
        this.clock = clock;
        this.client = client;
    }

    public Optional<String> resolve(String exchange, String tradingSymbol) {
        Snapshot current = snapshot;
        if (!"NSE".equals(exchange) || tradingSymbol == null
                || !tradingSymbol.matches("[A-Z0-9&._/+-]{1,100}-EQ")
                || clock.instant().isAfter(current.fetchedAt().plus(MAX_AGE))) {
            return Optional.empty();
        }
        return Optional.ofNullable(current.symbolToIsin()
            .get(tradingSymbol.substring(0, tradingSymbol.length() - 3)));
    }

    @Scheduled(initialDelay = 5000, fixedDelay = 3600000)
    public synchronized void refresh() {
        try {
            HttpRequest request = HttpRequest.newBuilder(SOURCE)
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/gzip,application/octet-stream")
                .GET().build();
            HttpResponse<byte[]> reply = client.send(request,
                HttpResponse.BodyHandlers.ofByteArray());
            if (reply.statusCode() != 200 || reply.body() == null
                    || reply.body().length > MAX_COMPRESSED) {
                log.warn("Upstox NSE ISIN source refresh unavailable");
                return;
            }
            Parse parsed = parseGzip(reply.body(), mapper);
            // Treat a single-page/truncated result as a failed publication.
            if (parsed.symbolToIsin().size() < 1000) {
                log.warn("Upstox NSE ISIN source did not meet minimum coverage");
                return;
            }
            snapshot = new Snapshot(parsed.symbolToIsin(), clock.instant());
            log.info("Upstox NSE equity ISIN reference updated: {} verified rows",
                parsed.symbolToIsin().size());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("Upstox NSE ISIN refresh interrupted");
        } catch (Exception ex) {
            // Never log raw provider response or replace a healthy reference.
            log.warn("Upstox NSE ISIN source refresh unavailable");
        }
    }

    static Parse parseGzip(byte[] payload, ObjectMapper mapper) {
        if (payload == null || payload.length < 12 || payload.length > MAX_COMPRESSED
                || (payload[0] & 0xff) != 0x1f || (payload[1] & 0xff) != 0x8b) {
            throw new IllegalArgumentException("Invalid Upstox NSE BOD gzip");
        }
        byte[] uncompressed;
        try (var input = new GZIPInputStream(new ByteArrayInputStream(payload));
             var output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int size;
            while ((size = input.read(buffer)) != -1) {
                if (size > MAX_UNCOMPRESSED - output.size()) {
                    throw new IllegalArgumentException("Upstox NSE BOD exceeds safety limit");
                }
                output.write(buffer, 0, size);
            }
            uncompressed = output.toByteArray();
        } catch (IOException ex) {
            throw new IllegalArgumentException("Invalid Upstox NSE BOD compression");
        }
        try {
            JsonNode json = mapper.readTree(uncompressed);
            if (json == null || !json.isArray() || json.size() > 100_000) {
                throw new IllegalArgumentException("Invalid Upstox NSE instrument list");
            }
            Map<String, String> matches = new HashMap<>();
            Set<String> ambiguous = new HashSet<>();
            int valid = 0;
            for (JsonNode item : json) {
                if (!item.isObject()) throw new IllegalArgumentException("Malformed instrument row");
                if (!"NSE".equals(item.path("exchange").asText())
                        || !"NSE_EQ".equals(item.path("segment").asText())
                        || !"EQ".equals(item.path("instrument_type").asText())) continue;
                String security = item.path("security_type").asText("");
                if (!security.isBlank() && !"NORMAL".equals(security)) continue;
                String isin = item.path("isin").asText("");
                String symbol = item.path("trading_symbol").asText("");
                String key = item.path("instrument_key").asText("");
                if (!ISIN.matcher(isin).matches() || !SYMBOL.matcher(symbol).matches()
                        || !key.equals("NSE_EQ|" + isin)) continue;
                valid++;
                if (ambiguous.contains(symbol)) continue;
                String prior = matches.putIfAbsent(symbol, isin);
                if (prior != null && !prior.equals(isin)) {
                    ambiguous.add(symbol);
                    matches.remove(symbol);
                }
            }
            return new Parse(Map.copyOf(matches), json.size(), valid);
        } catch (IOException ex) {
            throw new IllegalArgumentException("Invalid Upstox NSE BOD JSON");
        }
    }
}
