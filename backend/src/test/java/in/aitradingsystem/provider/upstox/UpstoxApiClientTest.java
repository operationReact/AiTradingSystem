package in.aitradingsystem.provider.upstox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** Test the actual HTTP body conversion, not a mocked UpstoxApiClient. */
class UpstoxApiClientTest {
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final UpstoxApiClient api = new UpstoxApiClient(builder);
    private final UpstoxProperties app = new UpstoxProperties(
        "sandbox-client-id", "test-server-secret",
        "https://api.minttick.com/api/customer/brokers/upstox/callback");

    @Test
    void decodesActualUpstoxTokenJsonUsingJackson2AndFormParameters() {
        server.expect(requestTo("https://api.upstox.com/v2/login/authorization/token"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("grant_type=authorization_code")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("client_id=sandbox-client-id")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("client_secret=test-server-secret")))
            .andRespond(withSuccess("""
                {"access_token":"private-session-token","broker":"UPSTOX",
                 "user_id":"UP12345","is_active":true}
                """, MediaType.APPLICATION_JSON));

        var result = api.exchangeCode(app, "single-use-code");
        assertThat(result.path("broker").asText()).isEqualTo("UPSTOX");
        assertThat(result.path("user_id").asText()).isEqualTo("UP12345");
        assertThat(result.path("is_active").asBoolean()).isTrue();
        server.verify();
    }

    @Test
    void providerOAuthErrorKeepsFixedBrokerCodeWithoutSensitiveBody() {
        server.expect(requestTo("https://api.upstox.com/v2/login/authorization/token"))
            .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"errors\":[{\"errorCode\":\"UDAPI100069\",\"message\":\"private secret\"}]}"));
        assertThatThrownBy(() -> api.exchangeCode(app, "single-use-code"))
            .isInstanceOf(UpstoxApiClient.UpstoxProviderException.class)
            .hasMessage("UPSTOX_OAUTH_APP_CREDENTIALS_INVALID")
            .hasMessageNotContaining("private secret");
        server.verify();
    }

    @Test
    void malformedSuccessPayloadNeverTurnsIntoAnUpstoxOutage() {
        server.expect(requestTo("https://api.upstox.com/v2/login/authorization/token"))
            .andRespond(withSuccess("{malformed-json", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> api.exchangeCode(app, "single-use-code"))
            .isInstanceOf(UpstoxApiClient.UpstoxProviderException.class)
            .hasMessage("UPSTOX_AUTH_INVALID_RESPONSE");
        server.verify();
    }

    @Test
    void transportIOExceptionIsSeparatedFromResponseDecoding() {
        server.expect(requestTo("https://api.upstox.com/v2/login/authorization/token"))
            .andRespond(request -> { throw new IOException("network failure"); });
        assertThatThrownBy(() -> api.exchangeCode(app, "single-use-code"))
            .isInstanceOf(UpstoxApiClient.UpstoxProviderException.class)
            .hasMessage("UPSTOX_AUTH_TRANSPORT_UNAVAILABLE")
            .hasMessageNotContaining("network failure");
        server.verify();
    }

    @Test
    void unavailableOvernightFundsDoNotRepresentZeroBalance() {
        server.expect(requestTo("https://api.upstox.com/v2/user/get-funds-and-margin"))
            .andRespond(withStatus(HttpStatus.LOCKED));
        assertThatThrownBy(() -> api.funds("synthetic-session"))
            .isInstanceOf(UpstoxApiClient.UpstoxProviderException.class)
            .hasMessage("UPSTOX_FUNDS_MAINTENANCE");
        server.verify();
    }

    @Test
    void savedLoginAndAccountReadsUseExplicitJsonParsingToo() {
        server.expect(requestTo("https://api.upstox.com/v2/totp-login/authorization/token"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess(
                "{\"access_token\":\"upstox-test-token\",\"broker\":\"UPSTOX\"}",
                MediaType.APPLICATION_JSON));
        // MockRestServiceServer requires every expectation to be registered
        // before the first request, even when subsequent requests are ordered.
        server.expect(requestTo("https://api.upstox.com/v2/user/profile"))
            .andExpect(header("Authorization", "Bearer upstox-test-token"))
            .andRespond(withSuccess(
                "{\"status\":\"success\",\"data\":{\"broker\":\"UPSTOX\",\"user_id\":\"UP12345\"}}",
                MediaType.APPLICATION_JSON));
        assertThat(api.ownerTokenFromTotp("test-app-key","123456","654321")
            .path("access_token").asText()).isEqualTo("upstox-test-token");
        assertThat(api.profile("upstox-test-token").path("broker").asText())
            .isEqualTo("UPSTOX");
        server.verify();
    }
    @Test
    void officialFundamentalsRequireExactIsinAndUseTheAccountBearer() {
        server.expect(requestTo("https://api.upstox.com/v2/fundamentals/INE002A01018/key-ratios"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("Authorization", "Bearer private-test-token"))
            .andRespond(withSuccess("""
                {"status":"success","data":[
                  {"name":"P/E","company_value":"20.15","sector_value":"12.46"},
                  {"name":"ROE","company_value":"8.94%","sector_value":"16.46%"}
                ]}
                """, MediaType.APPLICATION_JSON));
        var ratios = api.fundamentals("private-test-token", "INE002A01018", "ratios");
        assertThat(ratios.isArray()).isTrue();
        assertThat(ratios.get(1).path("company_value").asText()).isEqualTo("8.94%");
        server.verify();
    }

    @Test
    void cashEquityQuoteUsesEncodedIsinKeyAndCustomerBearer() {
        server.expect(requestTo("https://api.upstox.com/v3/market-quote/quotes?instrument_key=NSE_EQ%7CINE848E01016"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("Authorization", "Bearer customer-session"))
            .andRespond(withSuccess("""
                {"status":"success","data":{"NSE_EQ:NHPC":{
                  "instrument_token":"NSE_EQ|INE848E01016","timestamp":"2026-11-26T10:00:00+05:30",
                  "last_price":76.59,"depth":{"buy":[{"price":76.58,"quantity":100}],
                  "sell":[{"price":76.60,"quantity":80}]}}}}
                """, MediaType.APPLICATION_JSON));

        var quote = api.cashEquityQuote("customer-session", "NSE_EQ|INE848E01016");
        assertThat(quote.path("NSE_EQ:NHPC").path("instrument_token").asText())
            .isEqualTo("NSE_EQ|INE848E01016");
        assertThat(quote.path("NSE_EQ:NHPC").path("last_price").decimalValue())
            .isEqualByComparingTo("76.59");
        server.verify();
    }

    @Test
    void cashEquityQuoteRejectsArbitraryOrNonEquityInstrumentKeys() {
        assertThatThrownBy(() -> api.cashEquityQuote("session", "NSE_FO|43919"))
            .isInstanceOf(UpstoxApiClient.UpstoxProviderException.class)
            .hasMessage("UPSTOX_EQUITY_INSTRUMENT_KEY_INVALID");
        assertThatThrownBy(() -> api.cashEquityQuote("session", "https://example.com"))
            .isInstanceOf(UpstoxApiClient.UpstoxProviderException.class)
            .hasMessage("UPSTOX_EQUITY_INSTRUMENT_KEY_INVALID");
        server.verify();
    }

    @Test
    void fundamentalsRejectUnverifiedIsinOrArbitrarySectionWithoutProviderCall() {
        assertThatThrownBy(() -> api.fundamentals("session", "../other", "ratios"))
            .isInstanceOf(UpstoxApiClient.UpstoxProviderException.class)
            .hasMessage("UPSTOX_FUNDAMENTAL_ISIN_INVALID");
        assertThatThrownBy(() -> api.fundamentals("session", "INE002A01018", "orders"))
            .isInstanceOf(UpstoxApiClient.UpstoxProviderException.class)
            .hasMessage("UPSTOX_FUNDAMENTAL_SECTION_INVALID");
        server.verify();
    }

    @Test
    void fundamentalForbiddenDoesNotLeakProviderResponseOrInvalidateLogin() {
        server.expect(requestTo("https://api.upstox.com/v2/fundamentals/INE002A01018/profile"))
            .andRespond(withStatus(HttpStatus.FORBIDDEN)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"message\":\"private broker account details\"}"));
        assertThatThrownBy(() -> api.fundamentals("private-test-token", "INE002A01018", "profile"))
            .isInstanceOf(UpstoxApiClient.UpstoxProviderException.class)
            .hasMessage("UPSTOX_READ_UNAVAILABLE")
            .hasMessageNotContaining("private broker account details");
        server.verify();
    }

}
