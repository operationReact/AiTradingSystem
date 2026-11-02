package in.aitradingsystem.provider.angelone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.aitradingsystem.config.AngelOneProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class AngelOneReadOnlyApiClientTest {
    private static final String ROOT = "https://apiconnect.angelone.in";
    private static final String SERVER_API_KEY = "server-api-key";
    private static final String CUSTOMER_API_KEY = "customer-scoped-api-key";
    private static final String AUTH_TOKEN = "publisher-auth-token";

    private MockRestServiceServer server;
    private AngelOneApiClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(ROOT);
        server = MockRestServiceServer.bindTo(builder).build();
        AngelOneProperties props = new AngelOneProperties(
                SERVER_API_KEY, "SERVER1", "pin", "JBSWY3DPEHPK3PXP",
                ROOT, 5000, 10000, 3600, 0, 0);
        client = new AngelOneApiClient(builder.build(), props, new ObjectMapper());
    }

    @Test
    void profileUsesCallScopedApiKeyAndBearerToken() {
        server.expect(requestTo(ROOT + AngelOneApiClient.PROFILE_PATH))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-PrivateKey", CUSTOMER_API_KEY))
                .andExpect(header("Authorization", "Bearer " + AUTH_TOKEN))
                .andRespond(withSuccess(
                        "{\"status\":true,\"data\":{\"clientcode\":\"CLIENT1\",\"name\":\"Test User\"}}",
                        MediaType.APPLICATION_JSON));

        var profile = client.profile(CUSTOMER_API_KEY, AUTH_TOKEN);

        assertThat(profile.get("clientcode").asText()).isEqualTo("CLIENT1");
        server.verify();
    }

    @Test
    void readOnlyAccountRoutesUseGetAndReturnProviderData() {
        expectRead(AngelOneApiClient.RMS_PATH);
        expectRead(AngelOneApiClient.ALL_HOLDING_PATH);
        expectRead(AngelOneApiClient.POSITION_PATH);
        expectRead(AngelOneApiClient.ORDER_BOOK_PATH);

        assertThat(client.funds(CUSTOMER_API_KEY, AUTH_TOKEN).get("ok").asBoolean()).isTrue();
        assertThat(client.holdings(CUSTOMER_API_KEY, AUTH_TOKEN).get("ok").asBoolean()).isTrue();
        assertThat(client.positions(CUSTOMER_API_KEY, AUTH_TOKEN).get("ok").asBoolean()).isTrue();
        assertThat(client.orderBook(CUSTOMER_API_KEY, AUTH_TOKEN).get("ok").asBoolean()).isTrue();
        server.verify();
    }

    private void expectRead(String path) {
        server.expect(requestTo(ROOT + path))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-PrivateKey", CUSTOMER_API_KEY))
                .andExpect(header("Authorization", "Bearer " + AUTH_TOKEN))
                .andRespond(withSuccess("{\"status\":true,\"data\":{\"ok\":true}}", MediaType.APPLICATION_JSON));
    }
}
