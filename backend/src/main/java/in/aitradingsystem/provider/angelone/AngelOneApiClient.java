package in.aitradingsystem.provider.angelone;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import in.aitradingsystem.config.AngelOneProperties;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Thin HTTP adapter for the Angel One SmartAPI endpoints in use. Paths were taken from the official SDK source
 * (angel-one/smartapi-java, Routes.java) and are marked VERIFIED_SDK in docs/angel-one-smartapi-integration.md.
 * Request and response bodies are never logged; error messages carry only HTTP status and provider error codes.
 */
@Component
public class AngelOneApiClient {

    static final String LOGIN_PATH = "/rest/auth/angelbroking/user/v1/loginByPassword";
    static final String PROFILE_PATH = "/rest/secure/angelbroking/user/v1/getProfile";
    static final String RMS_PATH = "/rest/secure/angelbroking/user/v1/getRMS";
    static final String ALL_HOLDING_PATH = "/rest/secure/angelbroking/portfolio/v1/getAllHolding";
    static final String POSITION_PATH = "/rest/secure/angelbroking/order/v1/getPosition";
    static final String ORDER_BOOK_PATH = "/rest/secure/angelbroking/order/v1/getOrderBook";
    static final String LTP_PATH = "/rest/secure/angelbroking/order/v1/getLtpData";
    static final String CANDLE_PATH = "/rest/secure/angelbroking/historical/v1/getCandleData";

    /** Provider error codes that the SDK treats as token or session expiry. */
    static final Set<String> SESSION_EXPIRED_CODES = Set.of("AG8001", "AG8003", "AB8050", "AB8051", "AB1010");

    public record LoginTokens(String jwtToken, String refreshToken) {
        @Override
        public String toString() {
            return "LoginTokens[redacted]";
        }
    }

    private final RestClient rest;
    private final AngelOneProperties properties;
    private final ObjectMapper mapper;
    private final AngelOneCallMetrics metrics;

    @Autowired
    public AngelOneApiClient(RestClient angelOneRestClient, AngelOneProperties properties, ObjectMapper mapper,
                             AngelOneCallMetrics metrics) {
        this.rest = angelOneRestClient;
        this.properties = properties;
        this.mapper = mapper;
        this.metrics = metrics;
    }

    /** Backwards-compatible constructor for focused HTTP adapter tests. */
    AngelOneApiClient(RestClient angelOneRestClient, AngelOneProperties properties, ObjectMapper mapper) {
        this.rest = angelOneRestClient;
        this.properties = properties;
        this.mapper = mapper;
        this.metrics = null;
    }

    /** Existing server-owned provider login path retained for market-data services. */
    public LoginTokens login(String clientCode, String password, String totp) {
        return login(properties.apiKey(), clientCode, password, totp);
    }

    /** Customer-scoped login. The API key, PIN and TOTP are used only for this provider exchange. */
    public LoginTokens login(String apiKey, String clientCode, String pin, String totp) {
        ObjectNode body = mapper.createObjectNode()
                .put("clientcode", clientCode)
                .put("password", pin)
                .put("totp", totp);
        JsonNode data = post(LOGIN_PATH, apiKey, null, body);
        String jwt = text(data, "jwtToken");
        String refresh = text(data, "refreshToken");
        if (jwt == null || refresh == null) {
            throw new AngelOneException(AngelOneException.Kind.INVALID_RESPONSE, "MISSING_TOKENS",
                    "Login response did not contain session tokens");
        }
        return new LoginTokens(jwt, refresh);
    }

    /** Authenticated account profile used to verify the broker identity before issuing an app session. */
    public JsonNode profile(String apiKey, String jwtToken) {
        JsonNode data = get(PROFILE_PATH, apiKey, jwtToken);
        if (!data.isObject()) {
            throw new AngelOneException(AngelOneException.Kind.INVALID_RESPONSE, "PROFILE_NOT_OBJECT",
                    "Profile response data was not an object");
        }
        return data;
    }

    /** Read-only RMS/funds snapshot for a customer-scoped broker session. */
    public JsonNode funds(String apiKey, String jwtToken) {
        return get(RMS_PATH, apiKey, jwtToken);
    }

    /** Read-only holdings snapshot for a customer-scoped broker session. */
    public JsonNode holdings(String apiKey, String jwtToken) {
        return get(ALL_HOLDING_PATH, apiKey, jwtToken);
    }

    /** Read-only positions snapshot for a customer-scoped broker session. */
    public JsonNode positions(String apiKey, String jwtToken) {
        return get(POSITION_PATH, apiKey, jwtToken);
    }

    /** Read-only order book. This adapter intentionally exposes no place/modify/cancel order method. */
    public JsonNode orderBook(String apiKey, String jwtToken) {
        return get(ORDER_BOOK_PATH, apiKey, jwtToken);
    }

    /** Returns the last traded price for one instrument. The caller supplies the verified symbol token. */
    public BigDecimal ltp(String jwtToken, String exchange, String tradingSymbol, String symbolToken) {
        ObjectNode body = mapper.createObjectNode()
                .put("exchange", exchange)
                .put("tradingsymbol", tradingSymbol)
                .put("symboltoken", symbolToken);
        JsonNode data = post(LTP_PATH, properties.apiKey(), jwtToken, body);
        JsonNode ltp = data.get("ltp");
        if (ltp == null || !ltp.isNumber()) {
            throw new AngelOneException(AngelOneException.Kind.INVALID_RESPONSE, "MISSING_LTP",
                    "LTP response did not contain a numeric ltp");
        }
        BigDecimal value = ltp.decimalValue();
        if (value.signum() <= 0) {
            throw new AngelOneException(AngelOneException.Kind.INVALID_RESPONSE, "NON_POSITIVE_LTP",
                    "LTP response contained a non-positive price");
        }
        return value;
    }

    /**
     * Historical candles. Request fields (exchange, symboltoken, interval, fromdate, todate) are verified from the
     * SDK README; the layout of each returned element is NOT verified and is left to the caller to parse strictly.
     */
    public JsonNode candles(String jwtToken, String exchange, String symbolToken, String interval,
                            String fromDate, String toDate) {
        ObjectNode body = mapper.createObjectNode()
                .put("exchange", exchange)
                .put("symboltoken", symbolToken)
                .put("interval", interval)
                .put("fromdate", fromDate)
                .put("todate", toDate);
        JsonNode data = post(CANDLE_PATH, properties.apiKey(), jwtToken, body);
        if (!data.isArray()) {
            throw new AngelOneException(AngelOneException.Kind.INVALID_RESPONSE, "CANDLES_NOT_ARRAY",
                    "Candle response data was not an array");
        }
        return data;
    }

    private JsonNode post(String path, String apiKey, String jwtToken, JsonNode body) {
        return execute(path, () -> rest.post()
                .uri(path)
                .headers(headers -> applyHeaders(headers, apiKey, jwtToken, true))
                // Serialize the JSON tree explicitly. Passing JsonNode as Object lets the active HTTP converter
                // introspect JsonNode's bean-style accessors on some Spring/Jackson combinations, producing a
                // metadata object instead of the SmartAPI payload.
                .body(body.toString())
                .exchange((request, response) -> parseResponse(response, jwtToken)));
    }

    private JsonNode get(String path, String apiKey, String jwtToken) {
        return execute(path, () -> rest.get()
                .uri(path)
                .headers(headers -> applyHeaders(headers, apiKey, jwtToken, false))
                .exchange((request, response) -> parseResponse(response, jwtToken)));
    }

    private JsonNode execute(String path, Supplier<JsonNode> operation) {
        long startedAt = metrics == null ? 0L : metrics.start();
        try {
            JsonNode result = operation.get();
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

    private void applyHeaders(HttpHeaders headers, String apiKey, String jwtToken, boolean contentType) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new AngelOneException(AngelOneException.Kind.NOT_CONFIGURED, "API_KEY_REQUIRED",
                    "Angel One API key is required");
        }
        if (contentType) headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.set("X-PrivateKey", apiKey);
        headers.set("X-UserType", "USER");
        headers.set("X-SourceID", "WEB");
        if (jwtToken != null) headers.setBearerAuth(jwtToken);
    }

    private JsonNode parseResponse(ClientHttpResponse response, String jwtToken) throws IOException {
        int status = response.getStatusCode().value();
        // SmartAPI's current official RateLimit page documents 403 when a provider rate limit is hit.
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
        String raw = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
        return parseEnvelope(raw);
    }

    private JsonNode parseEnvelope(String raw) {
        JsonNode root;
        try {
            root = mapper.readTree(raw);
        } catch (Exception e) {
            throw new AngelOneException(AngelOneException.Kind.INVALID_RESPONSE, "NOT_JSON",
                    "Provider returned a non-JSON response");
        }
        if (root == null || !root.has("status") || !root.get("status").isBoolean()) {
            throw new AngelOneException(AngelOneException.Kind.INVALID_RESPONSE, "NO_STATUS",
                    "Provider response envelope is missing status");
        }
        if (!root.get("status").asBoolean()) {
            String code = text(root, "errorcode");
            if (code == null) code = "UNKNOWN";
            if (SESSION_EXPIRED_CODES.contains(code)) {
                throw new AngelOneException(AngelOneException.Kind.SESSION_EXPIRED, code,
                        "Provider session is no longer valid");
            }
            throw new AngelOneException(AngelOneException.Kind.REJECTED, code, "Provider rejected the request");
        }
        JsonNode data = root.get("data");
        if (data == null || !(data.isObject() || data.isArray())) {
            throw new AngelOneException(AngelOneException.Kind.INVALID_RESPONSE, "NO_DATA",
                    "Provider response did not contain data");
        }
        return data;
    }

    static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || value.asText().isBlank() ? null : value.asText();
    }
}
