package in.aitradingsystem.provider.upstox;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;
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
    private final RestClient http;

    public UpstoxApiClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(10_000);
        this.http = RestClient.builder()
                .baseUrl("https://api.upstox.com")
                .requestFactory(factory)
                .build();
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
            return Objects.requireNonNull(
                http.post().uri("/v2/login/authorization/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body).retrieve().body(JsonNode.class));
        } catch (RestClientResponseException ex) {
            // Do not expose the upstream request, code, secrets or raw body.
            throw new UpstoxProviderException("UPSTOX_AUTH_FAILED");
        } catch (RuntimeException ex) {
            throw new UpstoxProviderException("UPSTOX_AUTH_UNAVAILABLE");
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
            if (ex.getStatusCode().value() == 401 || ex.getStatusCode().value() == 403) {
                throw new UpstoxProviderException("UPSTOX_SESSION_EXPIRED");
            }
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
