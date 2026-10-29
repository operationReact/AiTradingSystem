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
 * Thin HTTP adapter for the Angel One SmartAPI endpoints in use. Paths were taken from the official SDK source
 * (angel-one/smartapi-java, Routes.java) and are marked VERIFIED_SDK in docs/angel-one-smartapi-integration.md.
 * Request and response bodies are never logged; error messages carry only HTTP status and provider error codes.
 */
@Component
public class AngelOneApiClient {

    static final String LOGIN_PATH = "/rest/auth/angelbroking/user/v1/loginByPassword";
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

    public LoginTokens login(String clientCode, String password, String totp) {
        ObjectNode body = mapper.createObjectNode()
                .put("clientcode", clientCode)
                .put("password", password)
                .put("totp", totp);
        JsonNode data = call(LOGIN_PATH, null, body);
        String jwt = text(data, "jwtToken");
        String refresh = text(data, "refreshToken");
        if (jwt == null || refresh == null) {
            throw new AngelOneException(AngelOneException.Kind.INVALID_RESPONSE, "MISSING_TOKENS",
                    "Login response did not contain session tokens");
        }
        return new LoginTokens(jwt, refresh);
    }

    /** Returns the last traded price for one instrument. The caller supplies the verified symbol token. */
    public BigDecimal ltp(String jwtToken, String exchange, String tradingSymbol, String symbolToken) {
        ObjectNode body = mapper.createObjectNode()
                .put("exchange", exchange)
                .put("tradingsymbol", tradingSymbol)
                .put("symboltoken", symbolToken);
        JsonNode data = call(LTP_PATH, jwtToken, body);
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
        JsonNode data = call(CANDLE_PATH, jwtToken, body);
        if (!data.isArray()) {
            throw new AngelOneException(AngelOneException.Kind.INVALID_RESPONSE, "CANDLES_NOT_ARRAY",
                    "Candle response data was not an array");
        }
        return data;
    }

    private JsonNode call(String path, String jwtToken, Object body) {
        long startedAt = metrics == null ? 0L : metrics.start();
        try {
            JsonNode result = rest.post()
                    .uri(path)
                    .headers(h -> {
                        h.setContentType(MediaType.APPLICATION_JSON);
                        h.setAccept(List.of(MediaType.APPLICATION_JSON));
                        h.set("X-PrivateKey", properties.apiKey());
                        h.set("X-UserType", "USER");
                        h.set("X-SourceID", "WEB");
                        if (jwtToken != null) h.setBearerAuth(jwtToken);
                    })
                    .body(body)
                    .exchange((request, response) -> {
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
                    });
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
