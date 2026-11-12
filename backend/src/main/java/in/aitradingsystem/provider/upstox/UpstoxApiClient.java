package in.aitradingsystem.provider.upstox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** Fixed official Upstox HTTPS origin; no caller-supplied paths or redirect URLs. */
@Component
public class UpstoxApiClient {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_TOKEN_RESPONSE_CHARS = 16384;
    private final RestClient http;
    private final RestClient tokenHttp;

    public UpstoxApiClient() {
        SimpleClientHttpRequestFactory standardFactory = new SimpleClientHttpRequestFactory();
        standardFactory.setConnectTimeout(5_000);
        standardFactory.setReadTimeout(10_000);
        SimpleClientHttpRequestFactory exchangeFactory = new SimpleClientHttpRequestFactory();
        // The API Gateway Lambda calling us has a seven-second upstream limit.
        // Give the backend time to return a safe failure before that limit.
        exchangeFactory.setConnectTimeout(2_000);
        exchangeFactory.setReadTimeout(4_000);
        this.http = RestClient.builder()
                .baseUrl("https://api.upstox.com")
                .requestFactory(standardFactory)
                .build();
        this.tokenHttp = RestClient.builder()
                .baseUrl("https://api.upstox.com")
                .requestFactory(exchangeFactory)
                .build();
    }

    /** Allows a response-shaped HTTP contract test without actual broker access. */
    UpstoxApiClient(RestClient.Builder builder) {
        this.http = builder.baseUrl("https://api.upstox.com").build();
        this.tokenHttp = this.http;
    }

    public JsonNode exchangeCode(UpstoxProperties config, String oneTimeCode) {
        if (!config.configured() || oneTimeCode == null || oneTimeCode.isBlank()) {
            throw new UpstoxProviderException("UPSTOX_OAUTH_UNAVAILABLE");
        }
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("code", oneTimeCode);
        body.add("client_id", config.clientId());
        body.add("client_secret", config.clientSecret());
        body.add("redirect_uri", config.redirectUri());
        body.add("grant_type", "authorization_code");
        try {
            // Spring Boot 4's default RestClient converters may use Jackson 3,
            // whereas the broker model deliberately uses Jackson 2 JsonNode.
            // Read bounded text and parse explicitly with the Jackson 2 mapper.
            String payload = tokenHttp.post().uri("/v2/login/authorization/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .accept(MediaType.APPLICATION_JSON)
                .body(body).retrieve().body(String.class);
            if (payload == null || payload.isBlank()
                    || payload.length() > MAX_TOKEN_RESPONSE_CHARS) {
                throw new UpstoxProviderException("UPSTOX_AUTH_INVALID_RESPONSE");
            }
            JsonNode result = JSON.readTree(payload);
            if (result == null || !result.isObject()) {
                throw new UpstoxProviderException("UPSTOX_AUTH_INVALID_RESPONSE");
            }
            return result;
        } catch (RestClientResponseException ex) {
            // Report only predefined provider error classes. Raw body, OAuth code,
            // client secret and token are never logged, returned or persisted.
            throw new UpstoxProviderException(
                classifyOAuthExchangeFailure(ex.getResponseBodyAsString()));
        } catch (org.springframework.web.client.ResourceAccessException ex) {
            throw new UpstoxProviderException("UPSTOX_AUTH_TRANSPORT_UNAVAILABLE");
        } catch (JsonProcessingException ex) {
            throw new UpstoxProviderException("UPSTOX_AUTH_INVALID_RESPONSE");
        } catch (UpstoxProviderException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            // Unknown client failure is not evidence that Upstox is offline.
            throw new UpstoxProviderException("UPSTOX_AUTH_CLIENT_FAILURE");
        }
    }

    /** Allowlisted OAuth token-exchange diagnostics; no broker response reflection. */
    static String classifyOAuthExchangeFailure(String responseBody) {
        final String fallback = "UPSTOX_AUTH_FAILED";
        if (responseBody == null || responseBody.isBlank() || responseBody.length() > 4096) {
            return fallback;
        }
        try {
            JsonNode errors = JSON.readTree(responseBody).path("errors");
            if (!errors.isArray() || errors.isEmpty()) return fallback;
            JsonNode first = errors.get(0);
            String code = first.path("errorCode").asText("");
            if (code.isBlank()) code = first.path("error_code").asText("");
            return switch (code) {
                case "UDAPI100069" -> "UPSTOX_OAUTH_APP_CREDENTIALS_INVALID";
                case "UDAPI100070" -> "UPSTOX_OAUTH_REDIRECT_REJECTED";
                case "UDAPI100057" -> "UPSTOX_AUTH_CODE_INVALID";
                case "UDAPI100058" -> "UPSTOX_ACCOUNT_SEGMENTS_INACTIVE";
                default -> fallback;
            };
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            return fallback;
        }
    }

    /**
     * Official individual-app PIN + TOTP login. Upstox revokes its previous
     * access token on success. Never call while the customer's session is
     * valid; never expose the request or provider response to the device.
     */
    public JsonNode ownerTokenFromTotp(String appKey, String pin, String totp) {
        if (appKey == null || appKey.isBlank() || !pin.matches("[0-9]{6}")
                || !totp.matches("[0-9]{6}")) {
            throw new UpstoxProviderException("UPSTOX_TOTP_CONFIGURATION_INVALID");
        }
        try {
            JsonNode result = http.post()
                .uri("/v2/totp-login/authorization/token")
                .header("x-api-key", appKey)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(java.util.Map.of("pin", pin, "totp", totp))
                .retrieve().body(JsonNode.class);
            if (result == null || result.path("access_token").asText("").isBlank()) {
                throw new UpstoxProviderException("UPSTOX_TOTP_AUTH_REJECTED");
            }
            return result;
        } catch (RestClientResponseException ex) {
            // Parse only an allowlisted provider error identifier. Never log or
            // return the raw broker response, PIN, TOTP, app key, or token.
            throw new UpstoxProviderException(
                classifyTotpErrorResponse(ex.getResponseBodyAsString()));
        } catch (UpstoxProviderException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new UpstoxProviderException("UPSTOX_TOTP_AUTH_UNAVAILABLE");
        }
    }

    /**
     * Convert broker error identifiers into non-secret, actionable diagnostics.
     * Unknown/malformed or oversized upstream bodies always fail closed.
     */
    static String classifyTotpErrorResponse(String responseBody) {
        final String fallback = "UPSTOX_TOTP_AUTH_REJECTED";
        if (responseBody == null || responseBody.isBlank() || responseBody.length() > 4096) return fallback;
        try {
            JsonNode errors = JSON.readTree(responseBody).path("errors");
            if (!errors.isArray() || errors.isEmpty()) return fallback;
            JsonNode first = errors.get(0);
            String code = first.path("errorCode").asText("");
            if (code.isBlank()) code = first.path("error_code").asText("");
            return switch (code) {
                case "UDAPI100101" -> "UPSTOX_TOTP_APP_NOT_ELIGIBLE";
                case "UDAPI100096" -> "UPSTOX_TOTP_APP_KEY_INVALID";
                case "UDAPI100097" -> "UPSTOX_TOTP_CREDENTIALS_INVALID";
                case "UDAPI100098" -> "UPSTOX_TOTP_NOT_ENABLED";
                case "UDAPI100099" -> "UPSTOX_TOTP_RATE_LIMITED";
                case "UDAPI1241", "UDAPI1242" -> "UPSTOX_TOTP_CONFIGURATION_INVALID";
                default -> fallback;
            };
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            return fallback;
        }
    }

    public JsonNode profile(String token) {
        return get(token, "/v2/user/profile");
    }

    public JsonNode funds(String token) {
        return get(token, "/v2/user/get-funds-and-margin");
    }

    public JsonNode holdings(String token) {
        return get(token, "/v2/portfolio/long-term-holdings");
    }

    public JsonNode positions(String token) {
        return get(token, "/v2/portfolio/short-term-positions");
    }

    public JsonNode orders(String token) {
        return get(token, "/v2/order/retrieve-all");
    }

    public JsonNode staticIps(String token) {
        return get(token, "/v2/user/ip");
    }

    private JsonNode get(String token, String fixedPath) {
        if (token == null || token.isBlank()) {
            throw new UpstoxProviderException("UPSTOX_NOT_CONNECTED");
        }
        try {
            JsonNode result = http.get()
                .uri(fixedPath)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve().body(JsonNode.class);
            if (result == null || !"success".equals(result.path("status").asText())
                    || result.get("data") == null) {
                throw new UpstoxProviderException("UPSTOX_INVALID_RESPONSE");
            }
            return result.get("data");
        } catch (RestClientResponseException ex) {
            // Do not log provider responses. Token errors invalidate the session upstream.
            if (ex.getStatusCode().value() == 401) {
                throw new UpstoxProviderException("UPSTOX_SESSION_EXPIRED");
            }
            // 403 may also mean an endpoint is unavailable to this app
            // (e.g. static-IP management), not an invalid OAuth session.
            throw new UpstoxProviderException("UPSTOX_READ_UNAVAILABLE");
        } catch (UpstoxProviderException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new UpstoxProviderException("UPSTOX_READ_UNAVAILABLE");
        }
    }

    public static final class UpstoxProviderException extends RuntimeException {
        private final String code;
        public UpstoxProviderException(String code) {
            super(code);
            this.code = code;
        }
        public String code() { return code; }
    }
}
