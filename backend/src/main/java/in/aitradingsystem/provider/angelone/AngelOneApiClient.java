package in.aitradingsystem.provider.angelone;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import in.aitradingsystem.config.AngelOneProperties;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Thin HTTP adapter for the Angel One SmartAPI endpoints in use. Paths are verified against Angel One's current
 * SmartAPI documentation and official SDK route inventory. Request and response bodies are never logged; error
 * messages carry only HTTP status and provider error codes.
 */
@Component
public class AngelOneApiClient {

    static final String LOGIN_PATH = "/rest/auth/angelbroking/user/v1/loginByPassword";
    static final String PROFILE_PATH = "/rest/secure/angelbroking/user/v1/getProfile";
    static final String LTP_PATH = "/rest/secure/angelbroking/order/v1/getLtpData";
    static final String MARKET_QUOTE_PATH = "/rest/secure/angelbroking/market/v1/quote/";
    static final String CANDLE_PATH = "/rest/secure/angelbroking/historical/v1/getCandleData";
    static final String RMS_PATH = "/rest/secure/angelbroking/user/v1/getRMS";
    static final String HOLDINGS_PATH = "/rest/secure/angelbroking/portfolio/v1/getHolding";
    static final String ALL_HOLDINGS_PATH = "/rest/secure/angelbroking/portfolio/v1/getAllHolding";
    static final String POSITIONS_PATH = "/rest/secure/angelbroking/order/v1/getPosition";
    static final String ORDER_BOOK_PATH = "/rest/secure/angelbroking/order/v1/getOrderBook";
    static final String PLACE_ORDER_PATH = "/rest/secure/angelbroking/order/v1/placeOrder";

    /** Provider error codes that the SDK treats as token or session expiry. */
    static final Set<String> SESSION_EXPIRED_CODES = Set.of("AG8001", "AG8003", "AB8050", "AB8051", "AB1010");

    public record LoginTokens(String jwtToken, String refreshToken, String feedToken) {
        /** Legacy operator login fixture constructor; no streaming entitlement inferred. */
        public LoginTokens(String jwtToken, String refreshToken) {
            this(jwtToken, refreshToken, null);
        }
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

    public LoginTokens login(String clientCode, String password, String totp) {
        ObjectNode body = mapper.createObjectNode()
                .put("clientcode", clientCode)
                .put("password", password)
                .put("totp", totp);
        JsonNode data = post(LOGIN_PATH, null, body);
        String jwt = text(data, "jwtToken");
        String refresh = text(data, "refreshToken");
        if (jwt == null || refresh == null) {
            throw new AngelOneException(AngelOneException.Kind.INVALID_RESPONSE, "MISSING_TOKENS",
                    "Login response did not contain session tokens");
        }
        return new LoginTokens(jwt, refresh);
    }

    /**
     * Customer-scoped interactive login. Unlike the legacy operator session,
     * this uses the user's own API key, PIN and current TOTP. Credentials are
     * never retained by this adapter. Never log the request or provider JWT.
     */
    public LoginTokens customerLogin(String apiKey, String clientCode, String pin, String totp) {
        if (apiKey == null || apiKey.isBlank() || apiKey.length() > 256) {
            throw new IllegalArgumentException("Invalid broker API key");
        }
        ObjectNode body = mapper.createObjectNode()
                .put("clientcode", clientCode)
                .put("password", pin)
                .put("totp", totp);
        JsonNode data = post(LOGIN_PATH, null, body, apiKey);
        String jwt = text(data, "jwtToken");
        String refresh = text(data, "refreshToken");
        if (jwt == null || refresh == null) {
            throw new AngelOneException(AngelOneException.Kind.INVALID_RESPONSE, "MISSING_TOKENS",
                    "Provider login returned no session tokens");
        }
        String feedToken = text(data, "feedToken");
        // Existing manual broker reads continue without feedToken, but a WS
        // connection is impossible unless the broker actually supplies one.
        return new LoginTokens(jwt, refresh, feedToken);
    }

    public JsonNode customerProfile(String apiKey, String jwtToken) {
        return get(PROFILE_PATH, jwtToken, apiKey);
    }

    public JsonNode customerFunds(String apiKey, String jwtToken) {
        return get(RMS_PATH, jwtToken, apiKey);
    }

    public JsonNode customerHoldings(String apiKey, String jwtToken) {
        return get(ALL_HOLDINGS_PATH, jwtToken, apiKey);
    }

    public JsonNode customerPositions(String apiKey, String jwtToken) {
        return get(POSITIONS_PATH, jwtToken, apiKey);
    }

    public JsonNode customerOrders(String apiKey, String jwtToken) {
        return get(ORDER_BOOK_PATH, jwtToken, apiKey);
    }

    /**
     * Internal order transport only. NOT a public customer API.
     * Must be called only after compliance authorization, server-owned
     * capital reservation, user approval and idempotent intent persistence.
     * Never blindly retry a place-order timeout: reconcile order book first.
     */
    JsonNode postInternalApprovedLimitOrder(String apiKey, String jwtToken,
                                           ObjectNode verifiedLimitDayOrder) {
        if (apiKey == null || apiKey.isBlank() || jwtToken == null || jwtToken.isBlank()
                || verifiedLimitDayOrder == null
                || !"LIMIT".equals(verifiedLimitDayOrder.path("ordertype").asText())
                || !"DAY".equals(verifiedLimitDayOrder.path("duration").asText())
                || !"NORMAL".equals(verifiedLimitDayOrder.path("variety").asText())) {
            throw new IllegalArgumentException("Authorized LIMIT/DAY order context required");
        }
        return post(PLACE_ORDER_PATH, jwtToken, verifiedLimitDayOrder, apiKey);
    }

    /** Returns the last traded price for one instrument. The caller supplies the verified symbol token. */
    public BigDecimal ltp(String jwtToken, String exchange, String tradingSymbol, String symbolToken) {
        return checkedLtp(properties.apiKey(), jwtToken, exchange, tradingSymbol, symbolToken);
    }

    /** Broker-customer-scoped quote; do not substitute the platform API key. */
    public BigDecimal customerLtp(String apiKey, String jwtToken,
            String exchange, String tradingSymbol, String symbolToken) {
        if (apiKey == null || apiKey.isBlank() || apiKey.length() > 256
                || jwtToken == null || jwtToken.isBlank()) {
            throw new IllegalArgumentException("Valid customer broker session required");
        }
        return checkedLtp(apiKey, jwtToken, exchange, tradingSymbol, symbolToken);
    }

    private BigDecimal checkedLtp(String apiKey, String jwtToken,
            String exchange, String tradingSymbol, String symbolToken) {
        ObjectNode body = mapper.createObjectNode()
                .put("exchange", exchange)
                .put("tradingsymbol", tradingSymbol)
                .put("symboltoken", symbolToken);
        JsonNode data = post(LTP_PATH, jwtToken, body, apiKey);
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
     * Read-only SmartAPI FULL market quote for a bounded NSE token batch. FULL mode is used because it is the
     * provider REST surface that includes best-five bid/ask depth. Response semantics are validated by the
     * higher-level depth service before becoming research evidence.
     */
    public JsonNode fullMarketQuote(String jwtToken, List<String> symbolTokens) {
        return fullMarketQuote(null, jwtToken, symbolTokens);
    }

    /** Broker-customer-scoped FULL quote for one NSE token; do not substitute the platform API key. */
    public JsonNode customerFullQuote(String apiKey, String jwtToken, String symbolToken) {
        if (apiKey == null || apiKey.isBlank() || apiKey.length() > 256
                || jwtToken == null || jwtToken.isBlank()) {
            throw new IllegalArgumentException("Valid customer broker session required");
        }
        return fullMarketQuote(apiKey, jwtToken, List.of(symbolToken));
    }

    /** Broker-customer-scoped FULL quote for a bounded NSE token batch (at most 50 per request). */
    public JsonNode customerFullQuotes(String apiKey, String jwtToken, List<String> symbolTokens) {
        if (apiKey == null || apiKey.isBlank() || apiKey.length() > 256
                || jwtToken == null || jwtToken.isBlank()) {
            throw new IllegalArgumentException("Valid customer broker session required");
        }
        return fullMarketQuote(apiKey, jwtToken, symbolTokens);
    }

    private JsonNode fullMarketQuote(String apiKey, String jwtToken, List<String> symbolTokens) {
        if (symbolTokens == null || symbolTokens.isEmpty() || symbolTokens.size() > 50) {
            throw new IllegalArgumentException("FULL market quote requires between 1 and 50 symbol tokens");
        }
        ObjectNode exchangeTokens = mapper.createObjectNode();
        var nseTokens = mapper.createArrayNode();
        for (String token : symbolTokens) {
            if (token == null || token.isBlank() || token.length() > 64) {
                throw new IllegalArgumentException("FULL market quote symbol tokens must be non-blank and bounded");
            }
            nseTokens.add(token.trim());
        }
        exchangeTokens.set("NSE", nseTokens);
        ObjectNode body = mapper.createObjectNode()
                .put("mode", "FULL")
                .set("exchangeTokens", exchangeTokens);
        JsonNode data = apiKey == null ? post(MARKET_QUOTE_PATH, jwtToken, body)
                : post(MARKET_QUOTE_PATH, jwtToken, body, apiKey);
        if (!data.isObject()) {
            throw new AngelOneException(AngelOneException.Kind.INVALID_RESPONSE, "MARKET_QUOTE_NOT_OBJECT",
                    "FULL market quote response data was not an object");
        }
        return data;
    }

    /**
     * Historical candles. Request fields (exchange, symboltoken, interval, fromdate, todate) are verified from the
     * provider documentation; each returned row is still parsed strictly by the historical service.
     */
    public JsonNode candles(String jwtToken, String exchange, String symbolToken, String interval,
                            String fromDate, String toDate) {
        ObjectNode body = mapper.createObjectNode()
                .put("exchange", exchange)
                .put("symboltoken", symbolToken)
                .put("interval", interval)
                .put("fromdate", fromDate)
                .put("todate", toDate);
        JsonNode data = post(CANDLE_PATH, jwtToken, body);
        if (!data.isArray()) {
            throw new AngelOneException(AngelOneException.Kind.INVALID_RESPONSE, "CANDLES_NOT_ARRAY",
                    "Candle response data was not an array");
        }
        return data;
    }

    public JsonNode rms(String jwtToken) {
        return get(RMS_PATH, jwtToken);
    }

    public JsonNode holdings(String jwtToken) {
        return get(HOLDINGS_PATH, jwtToken);
    }

    public JsonNode allHoldings(String jwtToken) {
        return get(ALL_HOLDINGS_PATH, jwtToken);
    }

    public JsonNode positions(String jwtToken) {
        return get(POSITIONS_PATH, jwtToken);
    }

    public JsonNode orderBook(String jwtToken) {
        return get(ORDER_BOOK_PATH, jwtToken);
    }

    private JsonNode post(String path, String jwtToken, JsonNode body) {
        return post(path, jwtToken, body, properties.apiKey());
    }

    private JsonNode post(String path, String jwtToken, JsonNode body, String apiKey) {
        long startedAt = metrics == null ? 0L : metrics.start();
        try {
            JsonNode result = rest.post()
                    .uri(path)
                    .headers(h -> applyHeaders(h, jwtToken, apiKey))
                    // Serialize the JSON tree explicitly. Passing JsonNode as Object lets the active HTTP converter
                    // introspect JsonNode's bean-style accessors on some Spring/Jackson combinations, producing a
                    // metadata object instead of the SmartAPI payload.
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

    private JsonNode get(String path, String jwtToken) {
        return get(path, jwtToken, properties.apiKey());
    }

    private JsonNode get(String path, String jwtToken, String apiKey) {
        long startedAt = metrics == null ? 0L : metrics.start();
        try {
            JsonNode result = rest.get()
                    .uri(path)
                    .headers(h -> applyHeaders(h, jwtToken, apiKey))
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

    private void applyHeaders(org.springframework.http.HttpHeaders headers, String jwtToken, String apiKey) {
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.set("X-PrivateKey", apiKey);
        headers.set("X-UserType", "USER");
        headers.set("X-SourceID", "WEB");
        if (jwtToken != null) headers.setBearerAuth(jwtToken);
    }

    private JsonNode parseHttpResponse(int status, String jwtToken, String raw) {
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

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || value.asText().isBlank() ? null : value.asText();
    }
}
