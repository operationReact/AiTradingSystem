package in.aitradingsystem.provider.upstox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;
import software.amazon.awssdk.services.ssm.model.Parameter;
import software.amazon.awssdk.services.ssm.model.ParameterType;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class UpstoxFnoAnalyticsSourceTest {
    private static final String UNDERLYING = "NSE_INDEX|Nifty 50";
    private static final LocalDate EXPIRY = LocalDate.of(2026, 11, 19);
    private final SsmClient ssm = mock(SsmClient.class);
    private final RestClient.Builder builder = RestClient.builder().baseUrl("https://api.upstox.com");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final UpstoxAnalyticsSource source = new UpstoxAnalyticsSource(true, ssm, builder.build());

    private void validSsm() {
        when(ssm.getParameter(any(GetParameterRequest.class))).thenAnswer(inv -> {
            GetParameterRequest request = inv.getArgument(0);
            assertThat(request.name()).isEqualTo("/minttick/upstox/analytics-token");
            assertThat(request.withDecryption()).isTrue();
            return GetParameterResponse.builder().parameter(Parameter.builder()
                    .name(request.name()).type(ParameterType.SECURE_STRING)
                    .value("test-token-never-log").build()).build();
        });
    }

    @Test
    void readsCurrentOptionContractsAndPreservesProviderLotSize() {
        validSsm();
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/v2/option/contract?")))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer test-token-never-log"))
                .andRespond(withSuccess("""
                    {"status":"success","data":[
                      {"underlying_key":"NSE_INDEX|Nifty 50","expiry":"2026-11-19",
                       "instrument_key":"NSE_FO|61001","instrument_type":"CE",
                       "trading_symbol":"NIFTY 25000 CE 19 NOV 26",
                       "strike_price":25000,"lot_size":65,"tick_size":0.05}
                    ]}
                    """, MediaType.APPLICATION_JSON));
        var contracts = source.currentFnoOptionContracts(UNDERLYING, EXPIRY);
        assertThat(contracts).hasSize(1);
        assertThat(contracts.getFirst().side()).isEqualTo("CE");
        assertThat(contracts.getFirst().lotSize()).isEqualTo(65);
        assertThat(contracts.getFirst().strikePrice()).isEqualByComparingTo("25000");
        server.verify();
    }

    @Test
    void readsChainWithoutInventingAnExchangeQuoteTimestamp() {
        validSsm();
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/v2/option/chain?")))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer test-token-never-log"))
                .andRespond(withSuccess("""
                    {"status":"success","data":[
                      {"expiry":"2026-11-19","underlying_key":"NSE_INDEX|Nifty 50",
                       "strike_price":25000,"underlying_spot_price":25050,
                       "call_options":{"instrument_key":"NSE_FO|61001",
                         "market_data":{"ltp":120,"bid_price":119,"ask_price":121,
                           "volume":300,"oi":4500,"prev_oi":3500},
                         "option_greeks":{"iv":17.8,"delta":0.52,"gamma":0.001,"theta":-2,"vega":3.2}},
                       "put_options":{"instrument_key":"NSE_FO|61002",
                         "market_data":{"ltp":70,"bid_price":69,"ask_price":71,
                           "volume":400,"oi":6000,"prev_oi":5000}}}
                    ]}
                    """, MediaType.APPLICATION_JSON));
        var chain = source.currentFnoOptionChain(UNDERLYING, EXPIRY);
        assertThat(chain.source()).isEqualTo("UPSTOX_ANALYTICS");
        assertThat(chain.exchangeQuoteTimeVerified()).isFalse();
        assertThat(chain.retrievedAt()).isNotNull();
        assertThat(chain.strikes()).hasSize(1);
        assertThat(chain.strikes().getFirst().call().delta()).isEqualByComparingTo("0.52");
        assertThat(chain.strikes().getFirst().put().iv()).isNull();
        assertThat(chain.strikes().getFirst().put().openInterest()).isEqualTo(6000);
        server.verify();
    }

    @Test
    void emptyProviderChainIsObservedZeroNotInventedData() {
        validSsm();
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/v2/option/chain?")))
                .andRespond(withSuccess("{\"status\":\"success\",\"data\":[]}", MediaType.APPLICATION_JSON));
        var chain = source.currentFnoOptionChain(UNDERLYING, EXPIRY);
        assertThat(chain.strikes()).isEmpty();
        assertThat(chain.exchangeQuoteTimeVerified()).isFalse();
        server.verify();
    }

    @Test
    void wrongUnderlyingOrDuplicateStrikeFailsClosed() {
        validSsm();
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/v2/option/chain?")))
                .andRespond(withSuccess("""
                    {"status":"success","data":[{"expiry":"2026-11-19",
                        "underlying_key":"BSE_INDEX|SENSEX","strike_price":25000,
                        "underlying_spot_price":25000,"call_options":{},"put_options":{}}]}
                    """, MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> source.currentFnoOptionChain(UNDERLYING, EXPIRY))
                .hasMessage("UPSTOX_FNO_INVALID_CHAIN");
        server.verify();
    }

    @Test
    void invalidRequestsDoNotReadSecretsOrCallProvider() {
        assertThatThrownBy(() -> source.currentFnoOptionChain(
                "NSE_INDEX|Nifty 50/../../orders", EXPIRY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> source.currentFnoOptionContracts(
                UNDERLYING, LocalDate.of(1990, 1, 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> source.currentFnoOptionChain(UNDERLYING, null))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(ssm);
    }

    @Test
    void providerUnauthorizedNeverLeaksBearerOrBody() {
        validSsm();
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/v2/option/chain?")))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .body("test-token-never-log private upstream message"));
        assertThatThrownBy(() -> source.currentFnoOptionChain(UNDERLYING, EXPIRY))
                .hasMessage("UPSTOX_FNO_UNAUTHORIZED")
                .hasMessageNotContaining("test-token-never-log");
        server.verify();
    }

    @Test
    void onlySanitizedSafeHttpClassesEscapeFromFnoSource() {
        validSsm();
        var cases = java.util.Map.of(
            HttpStatus.FORBIDDEN, "FORBIDDEN",
            HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
            HttpStatus.BAD_REQUEST, "UPSTREAM_REJECTED",
            HttpStatus.NOT_FOUND, "NOT_FOUND",
            HttpStatus.INTERNAL_SERVER_ERROR, "UPSTREAM_UNAVAILABLE");
        for (var entry : cases.entrySet()) {
            server.expect(requestTo(org.hamcrest.Matchers.containsString("/v2/option/contract?")))
                    .andRespond(withStatus(entry.getKey())
                            .body("test-token-never-log secret diagnostics"));
        }
        // MockRestServiceServer enforces request ordering, so test each
        // expected category against a stable sorted input below.
        for (var entry : cases.entrySet()) {
            assertThatThrownBy(() -> source.currentFnoOptionContracts(UNDERLYING, EXPIRY))
                    .hasMessage("UPSTOX_FNO_" + entry.getValue())
                    .hasMessageNotContaining("test-token-never-log");
        }
        server.verify();
    }

    @Test
    void invalidProviderEnvelopeFailsWithoutPrintingRawResponse() {
        validSsm();
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/v2/option/contract?")))
            .andRespond(withSuccess("not-json private token", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> source.currentFnoOptionContracts(UNDERLYING, EXPIRY))
            .hasMessage("UPSTOX_FNO_INVALID_PAYLOAD")
            .hasMessageNotContaining("private token");
        server.verify();
    }

    @Test
    void disabledSourceDoesNotReachSsm() {
        var disabled = new UpstoxAnalyticsSource(false, ssm, builder.build());
        assertThatThrownBy(() -> disabled.currentFnoOptionChain(UNDERLYING, EXPIRY))
                .hasMessage("UPSTOX_ANALYTICS_DISABLED");
        verifyNoInteractions(ssm);
    }
}
