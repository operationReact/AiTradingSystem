package in.aitradingsystem.provider.angelone;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import in.aitradingsystem.config.AngelOneProperties;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/** Dedicated adapter for the current SmartAPI Live Market Data API. No mutation endpoints are exposed. */
@Component
public class AngelOneMarketDataApiClient {
    static final String FULL_QUOTE_PATH = "/rest/secure/angelbroking/market/v1/quote/";
    private static final DateTimeFormatter EXCHANGE_TIME =
            DateTimeFormatter.ofPattern("dd-MMM-uuuu HH:mm:ss", Locale.ENGLISH);
    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    private final RestClient rest;
    private final AngelOneProperties properties;
    private final ObjectMapper mapper;
    private final AngelOneCallMetrics metrics;
    private final Clock clock;

    public AngelOneMarketDataApiClient(RestClient angelOneRestClient, AngelOneProperties properties,
                                       ObjectMapper mapper, AngelOneCallMetrics metrics, Clock clock) {
        this.rest = angelOneRestClient;
        this.properties = properties;
        this.mapper = mapper;
        this.metrics = metrics;
        this.clock = clock;
    }

    /** Test-only constructor without metrics. */
    AngelOneMarketDataApiClient(RestClient rest, AngelOneProperties properties, ObjectMapper mapper, Clock clock) {
        this.rest = rest;
        this.properties = properties;
        this.mapper = mapper;
        this.metrics = null;
        this.clock = clock;
    }

    public AngelOneFullQuote fullQuote(String jwtToken, String exchange, String symbolToken) {
        if (exchange == null || exchange.isBlank() || symbolToken == null || symbolToken.isBlank()) {
            throw invalid("INVALID_QUOTE_IDENTITY", "Quote identity must contain exchange and symbol token");
        }
        ObjectNode exchangeTokens = mapper.createObjectNode();
        exchangeTokens.putArray(exchange).add(symbolToken);
        ObjectNode body = mapper.createObjectNode().put("mode", "FULL");
        body.set("exchangeTokens", exchangeTokens);
        JsonNode data = post(FULL_QUOTE_PATH, jwtToken, body);
        return parseFullQuote(data, exchange, symbolToken, clock.instant());
    }

    private AngelOneFullQuote parseFullQuote(JsonNode data, String expectedExchange, String expectedToken,
                                             Instant availableAt) {
        JsonNode unfetched = data.get("unfetched");
        if (unfetched != null && unfetched.isArray() && !unfetched.isEmpty()) {
            throw invalid("QUOTE_UNFETCHED", "Provider did not fetch the requested quote");
        }
        JsonNode fetched = data.get("fetched");
        if (fetched == null || !fetched.isArray() || fetched.size() != 1) {
            throw invalid("QUOTE_FETCH_COUNT", "FULL quote response must contain exactly one fetched instrument");
        }
        JsonNode quote = fetched.get(0);
        String exchange = requiredText(quote, "exchange");
        String symbolToken = requiredText(quote, "symbolToken");
        if (!expectedExchange.equals(exchange) || !expectedToken.equals(symbolToken)) {
            throw invalid("QUOTE_IDENTITY_MISMATCH", "Provider quote identity did not match the request");
        }

        BigDecimal ltp = positiveDecimal(quote, "ltp");
        long volume = nonNegativeLong(quote, "tradeVolume");
        JsonNode depth = quote.get("depth");
        if (depth == null || !depth.isObject()) {
            throw invalid("MISSING_DEPTH", "FULL quote response did not contain depth");
        }
        JsonNode bestBid = firstDepth(depth, "buy");
        JsonNode bestAsk = firstDepth(depth, "sell");
        BigDecimal bid = positiveDecimal(bestBid, "price");
        BigDecimal ask = positiveDecimal(bestAsk, "price");
        long bidQuantity = positiveLong(bestBid, "quantity");
        long askQuantity = positiveLong(bestAsk, "quantity");
        if (ask.compareTo(bid) < 0) {
            throw invalid("CROSSED_BOOK", "FULL quote contained a crossed best bid/ask book");
        }
        BigDecimal mid = bid.add(ask).divide(BigDecimal.valueOf(2));
        BigDecimal spreadBps = ask.subtract(bid)
                .multiply(BigDecimal.valueOf(10_000))
                .divide(mid, 8, RoundingMode.HALF_UP);

        return new AngelOneFullQuote(
                exchange,
                requiredText(quote, "tradingSymbol"),
                symbolToken,
                ltp,
                bid,
                bidQuantity,
                ask,
                askQuantity,
                spreadBps,
                volume,
                exchangeTime(requiredText(quote, "exchFeedTime")),
                availableAt);
    }

    private JsonNode post(String path, String jwtToken, JsonNode body) {
        long startedAt = metrics == null ? 0L : metrics.start();
        try {
            JsonNode result = rest.post()
                    .uri(path)
                    .headers(h -> applyHeaders(h, jwtToken))
                    .body(body.toString())
                    .exchange((request, response) -> parseHttpResponse(response.getStatusCode().value(), jwtToken,
                            new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8)));
            if (metrics != null) metrics.recordSuccess(path, startedAt);
            return result;
        } catch (AngelOneException e) {
            if (metrics != null) metrics.recordFailure(path, e, startedAt);
            throw e;
        } catch (ResourceAccessException e) {
            AngelOneException mapped = new AngelOneException(AngelOneException.Kind.UNAVAILABLE, "NETWORK",
                    "Provider could not be reached");
            if (metrics != null) metrics.recordFailure(path, mapped, startedAt);
            throw mapped;
        }
    }

    private void applyHeaders(org.springframework.http.HttpHeaders headers, String jwtToken) {
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.set("X-PrivateKey", properties.apiKey());
        headers.set("X-UserType", "USER");
        headers.set("X-SourceID", "WEB");
        if (jwtToken != null) headers.setBearerAuth(jwtToken);
    }

    private JsonNode parseHttpResponse(int status, String jwtToken, String raw) {
        if (status == 403 || status == 429) {
            throw new AngelOneException(AngelOneException.Kind.RATE_LIMITED, "HTTP_" + status,
                    "Provider rate limit reached");
        }
        if (status == 401) {
            throw jwtToken != null
                    ? new AngelOneException(AngelOneException.Kind.SESSION_EXPIRED, "HTTP_401",
                            "Provider session is no longer valid")
                    : new AngelOneException(AngelOneException.Kind.REJECTED, "HTTP_401",
                            "Provider rejected the request as unauthorised");
        }
        if (status == 408 || status == 425 || status >= 500) {
            throw new AngelOneException(AngelOneException.Kind.UNAVAILABLE, "HTTP_" + status,
                    "Provider returned a transient HTTP error");
        }
        if (status >= 400) {
            throw new AngelOneException(AngelOneException.Kind.REJECTED, "HTTP_" + status,
                    "Provider rejected the request");
        }
        JsonNode root;
        try {
            root = mapper.readTree(raw);
        } catch (Exception e) {
            throw invalid("NOT_JSON", "Provider returned a non-JSON response");
        }
        if (root == null || !root.has("status") || !root.get("status").isBoolean()) {
            throw invalid("NO_STATUS", "Provider response envelope is missing status");
        }
        if (!root.get("status").asBoolean()) {
            String code = optionalText(root, "errorcode");
            if (code == null) code = "UNKNOWN";
            if (AngelOneApiClient.SESSION_EXPIRED_CODES.contains(code)) {
                throw new AngelOneException(AngelOneException.Kind.SESSION_EXPIRED, code,
                        "Provider session is no longer valid");
            }
            throw new AngelOneException(AngelOneException.Kind.REJECTED, code, "Provider rejected the request");
        }
        JsonNode data = root.get("data");
        if (data == null || !data.isObject()) {
            throw invalid("NO_DATA", "Provider response did not contain market data");
        }
        return data;
    }

    private static JsonNode firstDepth(JsonNode depth, String side) {
        JsonNode values = depth.get(side);
        if (values == null || !values.isArray() || values.isEmpty() || !values.get(0).isObject()) {
            throw invalid("MISSING_BEST_" + side.toUpperCase(Locale.ROOT),
                    "FULL quote did not contain a best " + side + " level");
        }
        return values.get(0);
    }

    private static String requiredText(JsonNode node, String field) {
        String value = optionalText(node, field);
        if (value == null) throw invalid("MISSING_" + field.toUpperCase(Locale.ROOT),
                "FULL quote response is missing " + field);
        return value;
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || value.asText().isBlank() ? null : value.asText();
    }

    private static BigDecimal positiveDecimal(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isNumber() || value.decimalValue().signum() <= 0) {
            throw invalid("INVALID_" + field.toUpperCase(Locale.ROOT), field + " must be a positive number");
        }
        return value.decimalValue();
    }

    private static long nonNegativeLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.canConvertToLong() || value.longValue() < 0) {
            throw invalid("INVALID_" + field.toUpperCase(Locale.ROOT), field + " must be a non-negative integer");
        }
        return value.longValue();
    }

    private static long positiveLong(JsonNode node, String field) {
        long value = nonNegativeLong(node, field);
        if (value == 0) throw invalid("INVALID_" + field.toUpperCase(Locale.ROOT), field + " must be positive");
        return value;
    }

    private static Instant exchangeTime(String raw) {
        try {
            return LocalDateTime.parse(raw, EXCHANGE_TIME).atZone(INDIA).toInstant();
        } catch (DateTimeParseException e) {
            throw invalid("INVALID_EXCHANGE_TIME", "FULL quote exchange feed time was invalid");
        }
    }

    private static AngelOneException invalid(String code, String message) {
        return new AngelOneException(AngelOneException.Kind.INVALID_RESPONSE, code, message);
    }
}
