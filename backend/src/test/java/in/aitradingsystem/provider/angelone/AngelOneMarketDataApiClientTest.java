package in.aitradingsystem.provider.angelone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.aitradingsystem.config.AngelOneProperties;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class AngelOneMarketDataApiClientTest {
    private static final String ROOT = AngelOneApiClientTest.ROOT;
    private static final String JWT = AngelOneApiClientTest.JWT;

    private MockRestServiceServer server;
    private AngelOneApiClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(ROOT);
        server = MockRestServiceServer.bindTo(builder).build();
        AngelOneProperties props = new AngelOneProperties(
                AngelOneApiClientTest.API_KEY,
                "CLIENT1",
                "pin-secret",
                "JBSWY3DPEHPK3PXP",
                ROOT,
                5000,
                10000,
                3600,
                0,
                0);
        client = new AngelOneApiClient(builder.build(), props, new ObjectMapper());
    }

    @Test
    void fullQuoteUsesVerifiedFullModeShapeAndBearerToken() {
        server.expect(requestTo(ROOT + AngelOneApiClient.MARKET_QUOTE_PATH))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + JWT))
                .andExpect(content().string(org.hamcrest.Matchers.allOf(
                        containsString("\"mode\":\"FULL\""),
                        containsString("\"NSE\":[\"1594\",\"1333\"]"))))
                .andRespond(withSuccess(
                        "{\"status\":true,\"message\":\"SUCCESS\",\"errorcode\":\"\","
                                + "\"data\":{\"fetched\":[],\"unfetched\":[]}}",
                        MediaType.APPLICATION_JSON));

        var data = client.fullMarketQuote(JWT, List.of("1594", "1333"));

        assertThat(data.isObject()).isTrue();
        assertThat(data.get("fetched").isArray()).isTrue();
        server.verify();
    }

    @Test
    void fullQuoteRejectsEmptyOversizedAndBlankTokenBatchesBeforeHttp() {
        assertThatThrownBy(() -> client.fullMarketQuote(JWT, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.fullMarketQuote(
                JWT,
                java.util.stream.IntStream.range(0, 51).mapToObj(Integer::toString).toList()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.fullMarketQuote(JWT, List.of("1594", " ")))
                .isInstanceOf(IllegalArgumentException.class);
        server.verify();
    }

    @Test
    void fullQuoteRejectsNonObjectProviderData() {
        server.expect(requestTo(ROOT + AngelOneApiClient.MARKET_QUOTE_PATH))
                .andRespond(withSuccess(
                        "{\"status\":true,\"data\":[]}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.fullMarketQuote(JWT, List.of("1594")))
                .isInstanceOfSatisfying(AngelOneException.class, ex -> {
                    assertThat(ex.kind()).isEqualTo(AngelOneException.Kind.INVALID_RESPONSE);
                    assertThat(ex.code()).isEqualTo("MARKET_QUOTE_NOT_OBJECT");
                });
        server.verify();
    }
}
