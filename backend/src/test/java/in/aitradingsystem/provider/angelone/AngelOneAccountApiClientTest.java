package in.aitradingsystem.provider.angelone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.aitradingsystem.config.AngelOneProperties;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class AngelOneAccountApiClientTest {

    private static final String ROOT = "https://apiconnect.angelone.in";
    private static final String API_KEY = "test-api-key-value";
    private static final String JWT = "jwt-secret-value-1";

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
    void accountReadsUseVerifiedGetRoutesAndAuthenticationHeaders() {
        expectGet(AngelOneApiClient.RMS_PATH,
                "{\"status\":true,\"data\":{\"availablecash\":\"1000.00\"}}");
        expectGet(AngelOneApiClient.HOLDINGS_PATH,
                "{\"status\":true,\"data\":[{\"tradingsymbol\":\"RELIANCE-EQ\"}]}");
        expectGet(AngelOneApiClient.ALL_HOLDINGS_PATH,
                "{\"status\":true,\"data\":{\"holdings\":[],\"totalholding\":{}}}");
        expectGet(AngelOneApiClient.POSITIONS_PATH,
                "{\"status\":true,\"data\":[{\"tradingsymbol\":\"RELIANCE-EQ\"}]}");
        expectGet(AngelOneApiClient.ORDER_BOOK_PATH,
                "{\"status\":true,\"data\":[{\"orderid\":\"123\"}]}");

        assertThat(client.rms(JWT).get("availablecash").asText()).isEqualTo("1000.00");
        assertThat(client.holdings(JWT)).hasSize(1);
        assertThat(client.allHoldings(JWT).has("holdings")).isTrue();
        assertThat(client.positions(JWT)).hasSize(1);
        assertThat(client.orderBook(JWT)).hasSize(1);
        server.verify();
    }

    @Test
    void accountGetSessionExpiryEnvelopeMapsToSessionExpired() {
        server.expect(requestTo(ROOT + AngelOneApiClient.POSITIONS_PATH))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "{\"status\":false,\"errorcode\":\"AB8050\",\"message\":\"expired\"}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.positions(JWT))
                .isInstanceOfSatisfying(AngelOneException.class,
                        error -> assertThat(error.kind()).isEqualTo(AngelOneException.Kind.SESSION_EXPIRED));
    }

    @Test
    void apiClientExposesNoLiveOrderMutationMethod() {
        assertThat(Arrays.stream(AngelOneApiClient.class.getDeclaredMethods()).map(Method::getName))
                .doesNotContain("placeOrder", "modifyOrder", "cancelOrder", "convertPosition");
    }

    private void expectGet(String path, String responseBody) {
        server.expect(requestTo(ROOT + path))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer " + JWT))
                .andExpect(header("X-PrivateKey", API_KEY))
                .andExpect(header("X-UserType", "USER"))
                .andExpect(header("X-SourceID", "WEB"))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));
    }
}
