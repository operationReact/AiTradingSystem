package in.aitradingsystem.provider.angelone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.aitradingsystem.config.AngelOneProperties;
import java.io.IOException;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class AngelOneApiClientTest {

    static final String ROOT = "https://apiconnect.angelone.in";
    static final String API_KEY = "test-api-key-value";
    static final String JWT = "jwt-secret-value-1";
    static final String REFRESH = "refresh-secret-value-1";

    private MockRestServiceServer server;
    private AngelOneApiClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(ROOT);
        server = MockRestServiceServer.bindTo(builder).build();
        AngelOneProperties props = new AngelOneProperties(API_KEY, "CLIENT1", "pin-secret", "JBSWY3DPEHPK3PXP",
                ROOT, 5000, 10000, 3600, 0, 0);
        client = new AngelOneApiClient(builder.build(), props, new ObjectMapper());
    }

    @Test
    void loginReturnsTokensAndSendsRequiredHeaders() {
        server.expect(requestTo(ROOT + AngelOneApiClient.LOGIN_PATH))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-PrivateKey", API_KEY))
                .andExpect(header("X-UserType", "USER"))
                .andExpect(header("X-SourceID", "WEB"))
                .andRespond(withSuccess("{\"status\":true,\"data\":{\"jwtToken\":\"" + JWT
                        + "\",\"refreshToken\":\"" + REFRESH + "\",\"feedToken\":\"f\"}}", MediaType.APPLICATION_JSON));

        AngelOneApiClient.LoginTokens tokens = client.login("CLIENT1", "pin-secret", "123456");

        assertThat(tokens.jwtToken()).isEqualTo(JWT);
        assertThat(tokens.refreshToken()).isEqualTo(REFRESH);
        server.verify();
    }

    @Test
    void loginTokensDoNotPrintInToString() {
        assertThat(new AngelOneApiClient.LoginTokens(JWT, REFRESH).toString())
                .doesNotContain(JWT)
                .doesNotContain(REFRESH);
    }

    @Test
    void loginEnvelopeFailureIsRejectedWithoutLeakingMessage() {
        server.expect(requestTo(ROOT + AngelOneApiClient.LOGIN_PATH))
                .andRespond(withSuccess("{\"status\":false,\"errorcode\":\"AB1007\",\"message\":\"Invalid pin " + JWT + "\"}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.login("CLIENT1", "pin-secret", "000000"))
                .isInstanceOfSatisfying(AngelOneException.class, e -> {
                    assertThat(e.kind()).isEqualTo(AngelOneException.Kind.REJECTED);
                    assertThat(e.code()).isEqualTo("AB1007");
                    assertThat(e.getMessage()).doesNotContain(JWT);
                });
    }

    @Test
    void loginWithoutTokensIsInvalidResponse() {
        server.expect(requestTo(ROOT + AngelOneApiClient.LOGIN_PATH))
                .andRespond(withSuccess("{\"status\":true,\"data\":{}}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.login("CLIENT1", "pin-secret", "000000"))
                .isInstanceOfSatisfying(AngelOneException.class,
                        e -> assertThat(e.kind()).isEqualTo(AngelOneException.Kind.INVALID_RESPONSE));
    }

    @Test
    void ltpParsesNumericPriceAndSendsBearerToken() {
        server.expect(requestTo(ROOT + AngelOneApiClient.LTP_PATH))
                .andExpect(header("Authorization", "Bearer " + JWT))
                .andRespond(withSuccess("{\"status\":true,\"data\":{\"ltp\":1234.55}}", MediaType.APPLICATION_JSON));

        BigDecimal ltp = client.ltp(JWT, "NSE", "RELIANCE-EQ", "2885");

        assertThat(ltp).isEqualByComparingTo("1234.55");
        server.verify();
    }

    @Test
    void candlesPostSdkFieldNamesAndParseArray() {
        server.expect(requestTo(ROOT + AngelOneApiClient.CANDLE_PATH))
                .andExpect(header("Authorization", "Bearer " + JWT))
                .andExpect(content().string(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("\"symboltoken\":\"2885\""),
                        org.hamcrest.Matchers.containsString("\"interval\":\"ONE_DAY\""),
                        org.hamcrest.Matchers.containsString("\"fromdate\":\"2025-01-01 09:15\""),
                        org.hamcrest.Matchers.containsString("\"todate\":\"2025-01-31 15:30\""))))
                .andRespond(withSuccess("{\"status\":true,\"data\":[[\"2025-01-02T09:15:00+05:30\",1,2,0.5,1.5,10]]}",
                        MediaType.APPLICATION_JSON));

        var data = client.candles(JWT, "NSE", "2885", "ONE_DAY", "2025-01-01 09:15", "2025-01-31 15:30");

        assertThat(data.isArray()).isTrue();
        assertThat(data.size()).isEqualTo(1);
        server.verify();
    }

    @Test
    void sessionExpiryErrorCodesMapToSessionExpired() {
        server.expect(requestTo(ROOT + AngelOneApiClient.LTP_PATH))
                .andRespond(withSuccess("{\"status\":false,\"errorcode\":\"AB8050\",\"message\":\"expired\"}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.ltp(JWT, "NSE", "RELIANCE-EQ", "2885"))
                .isInstanceOfSatisfying(AngelOneException.class,
                        e -> assertThat(e.kind()).isEqualTo(AngelOneException.Kind.SESSION_EXPIRED));
    }

    @Test
    void http401WithTokenMapsToSessionExpired() {
        server.expect(requestTo(ROOT + AngelOneApiClient.LTP_PATH))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> client.ltp(JWT, "NSE", "RELIANCE-EQ", "2885"))
                .isInstanceOfSatisfying(AngelOneException.class,
                        e -> assertThat(e.kind()).isEqualTo(AngelOneException.Kind.SESSION_EXPIRED));
    }

    @Test
    void http429MapsToRateLimited() {
        server.expect(requestTo(ROOT + AngelOneApiClient.LTP_PATH))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> client.ltp(JWT, "NSE", "RELIANCE-EQ", "2885"))
                .isInstanceOfSatisfying(AngelOneException.class,
                        e -> assertThat(e.kind()).isEqualTo(AngelOneException.Kind.RATE_LIMITED));
    }

    @Test
    void http5xxMapsToUnavailable() {
        server.expect(requestTo(ROOT + AngelOneApiClient.LTP_PATH)).andRespond(withServerError());

        assertThatThrownBy(() -> client.ltp(JWT, "NSE", "RELIANCE-EQ", "2885"))
                .isInstanceOfSatisfying(AngelOneException.class,
                        e -> assertThat(e.kind()).isEqualTo(AngelOneException.Kind.UNAVAILABLE));
    }

    @Test
    void networkFailureMapsToUnavailable() {
        server.expect(requestTo(ROOT + AngelOneApiClient.LTP_PATH))
                .andRespond(request -> {
                    throw new IOException("connection reset");
                });

        assertThatThrownBy(() -> client.ltp(JWT, "NSE", "RELIANCE-EQ", "2885"))
                .isInstanceOfSatisfying(AngelOneException.class, e -> {
                    assertThat(e.kind()).isEqualTo(AngelOneException.Kind.UNAVAILABLE);
                    assertThat(e.getMessage()).doesNotContain("connection reset");
                });
    }

    @Test
    void nonJsonAndMissingLtpAreInvalidResponses() {
        server.expect(requestTo(ROOT + AngelOneApiClient.LTP_PATH))
                .andRespond(withSuccess("<html>oops</html>", MediaType.TEXT_HTML));
        server.expect(requestTo(ROOT + AngelOneApiClient.LTP_PATH))
                .andRespond(withSuccess("{\"status\":true,\"data\":{\"ltp\":\"abc\"}}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.ltp(JWT, "NSE", "RELIANCE-EQ", "2885"))
                .isInstanceOfSatisfying(AngelOneException.class,
                        e -> assertThat(e.kind()).isEqualTo(AngelOneException.Kind.INVALID_RESPONSE));
        assertThatThrownBy(() -> client.ltp(JWT, "NSE", "RELIANCE-EQ", "2885"))
                .isInstanceOfSatisfying(AngelOneException.class,
                        e -> assertThat(e.kind()).isEqualTo(AngelOneException.Kind.INVALID_RESPONSE));
    }
}
