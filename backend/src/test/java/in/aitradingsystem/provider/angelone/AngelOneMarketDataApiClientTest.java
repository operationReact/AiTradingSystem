package in.aitradingsystem.provider.angelone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.aitradingsystem.config.AngelOneProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class AngelOneMarketDataApiClientTest {
    static final String ROOT = "https://apiconnect.angelone.in";
    static final String JWT = "jwt-secret-value-1";
    static final Instant CAPTURED_AT = Instant.parse("2026-11-02T10:00:05Z");

    private MockRestServiceServer server;
    private AngelOneMarketDataApiClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(ROOT);
        server = MockRestServiceServer.bindTo(builder).build();
        AngelOneProperties props = new AngelOneProperties("api-key", "CLIENT1", "pin-secret",
                "JBSWY3DPEHPK3PXP", ROOT, 5000, 10000, 3600, 0, 0);
        client = new AngelOneMarketDataApiClient(builder.build(), props, new ObjectMapper(),
                Clock.fixed(CAPTURED_AT, ZoneOffset.UTC));
    }

    @Test
    void fullQuoteUsesCurrentFullModeContractAndDerivesObservedSpread() {
        server.expect(requestTo(ROOT + AngelOneMarketDataApiClient.FULL_QUOTE_PATH))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + JWT))
                .andExpect(content().string(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("\"mode\":\"FULL\""),
                        org.hamcrest.Matchers.containsString("\"NSE\":[\"2885\"]"))))
                .andRespond(withSuccess(successQuote("NSE", "2885", "RELIANCE-EQ", "100.00", "100.10"),
                        MediaType.APPLICATION_JSON));

        AngelOneFullQuote quote = client.fullQuote(JWT, "NSE", "2885");

        assertThat(quote.exchange()).isEqualTo("NSE");
        assertThat(quote.tradingSymbol()).isEqualTo("RELIANCE-EQ");
        assertThat(quote.symbolToken()).isEqualTo("2885");
        assertThat(quote.ltp()).isEqualByComparingTo("100.05");
        assertThat(quote.bestBidPrice()).isEqualByComparingTo("100.00");
        assertThat(quote.bestBidQuantity()).isEqualTo(125L);
        assertThat(quote.bestAskPrice()).isEqualByComparingTo("100.10");
        assertThat(quote.bestAskQuantity()).isEqualTo(150L);
        assertThat(quote.spreadBps()).isEqualByComparingTo("9.99500250");
        assertThat(quote.tradeVolume()).isEqualTo(123456L);
        assertThat(quote.exchangeFeedTime()).isEqualTo(Instant.parse("2026-11-02T09:59:58Z"));
        assertThat(quote.availableAt()).isEqualTo(CAPTURED_AT);
        server.verify();
    }

    @Test
    void fullQuoteFailsClosedOnProviderIdentityMismatch() {
        server.expect(requestTo(ROOT + AngelOneMarketDataApiClient.FULL_QUOTE_PATH))
                .andRespond(withSuccess(successQuote("NSE", "9999", "OTHER-EQ", "100.00", "100.10"),
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.fullQuote(JWT, "NSE", "2885"))
                .isInstanceOfSatisfying(AngelOneException.class, ex -> {
                    assertThat(ex.kind()).isEqualTo(AngelOneException.Kind.INVALID_RESPONSE);
                    assertThat(ex.code()).isEqualTo("QUOTE_IDENTITY_MISMATCH");
                });
    }

    @Test
    void fullQuoteFailsClosedOnCrossedBook() {
        server.expect(requestTo(ROOT + AngelOneMarketDataApiClient.FULL_QUOTE_PATH))
                .andRespond(withSuccess(successQuote("NSE", "2885", "RELIANCE-EQ", "100.20", "100.10"),
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.fullQuote(JWT, "NSE", "2885"))
                .isInstanceOfSatisfying(AngelOneException.class, ex -> {
                    assertThat(ex.kind()).isEqualTo(AngelOneException.Kind.INVALID_RESPONSE);
                    assertThat(ex.code()).isEqualTo("CROSSED_BOOK");
                });
    }

    @Test
    void fullQuoteFailsClosedWhenProviderReportsUnfetchedInstrument() {
        server.expect(requestTo(ROOT + AngelOneMarketDataApiClient.FULL_QUOTE_PATH))
                .andRespond(withSuccess("{\"status\":true,\"message\":\"SUCCESS\",\"errorcode\":\"\","
                                + "\"data\":{\"fetched\":[],\"unfetched\":[{\"exchange\":\"NSE\","
                                + "\"symbolToken\":\"2885\"}]}}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.fullQuote(JWT, "NSE", "2885"))
                .isInstanceOfSatisfying(AngelOneException.class,
                        ex -> assertThat(ex.code()).isEqualTo("QUOTE_UNFETCHED"));
    }

    private static String successQuote(String exchange, String token, String symbol, String bid, String ask) {
        return "{\"status\":true,\"message\":\"SUCCESS\",\"errorcode\":\"\",\"data\":{"
                + "\"fetched\":[{\"exchange\":\"" + exchange + "\",\"tradingSymbol\":\"" + symbol
                + "\",\"symbolToken\":\"" + token + "\",\"ltp\":100.05,\"tradeVolume\":123456,"
                + "\"exchFeedTime\":\"02-Nov-2026 15:29:58\",\"depth\":{"
                + "\"buy\":[{\"price\":" + bid + ",\"quantity\":125,\"orders\":2}],"
                + "\"sell\":[{\"price\":" + ask + ",\"quantity\":150,\"orders\":3}]}}],"
                + "\"unfetched\":[]}}";
    }
}
