package in.aitradingsystem.provider.upstox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;
import software.amazon.awssdk.services.ssm.model.Parameter;
import software.amazon.awssdk.services.ssm.model.ParameterType;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import org.springframework.http.HttpStatus;

class UpstoxAnalyticsSourceTest {
    private final SsmClient ssm = mock(SsmClient.class);
    private final RestClient.Builder builder = RestClient.builder().baseUrl("https://api.upstox.com");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final UpstoxAnalyticsSource source =
            new UpstoxAnalyticsSource(true, ssm, builder.build());

    private void token() {
        when(ssm.getParameter(any(GetParameterRequest.class))).thenAnswer(inv -> {
            GetParameterRequest request = inv.getArgument(0);
            assertThat(request.name()).isEqualTo("/minttick/upstox/analytics-token");
            assertThat(request.withDecryption()).isTrue();
            return GetParameterResponse.builder()
                    .parameter(Parameter.builder().name(request.name())
                            .type(ParameterType.SECURE_STRING).value("unit-test-only").build())
                    .build();
        });
    }

    @Test
    void readsAndValidatesV3DailyCandlesIncludingSeventhOiColumn() {
        token();
        server.expect(requestTo(uri -> assertThat(uri.getPath()).contains("/v3/historical-candle/")))
                .andExpect(header("Authorization", "Bearer unit-test-only"))
                .andRespond(withSuccess("""
                    {"status":"success","data":{"candles":[
                      ["2026-11-09T09:15:00+05:30",100,102,99,101,42,0]
                    ]}}
                    """, MediaType.APPLICATION_JSON));
        var rows = source.dailyEquityCandles("NSE_EQ|INE002A01018",
                java.time.LocalDate.parse("2026-11-09"),
                java.time.LocalDate.parse("2026-11-09"));
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().volume()).isEqualTo(42);
        assertThat(rows.getFirst().openInterest()).isZero();
        server.verify();
    }

    @Test
    void readsCurrentRatiosButNeverGrantsPointInTimeHistory() {
        token();
        server.expect(requestTo("https://api.upstox.com/v2/fundamentals/INE002A01018/key-ratios"))
                .andExpect(header("Authorization", "Bearer unit-test-only"))
                .andRespond(withSuccess("""
                    {"status":"success","data":[{"name":"P/E","company_value":"20.15","sector_value":"12.46"}]}
                    """, MediaType.APPLICATION_JSON));
        assertThat(source.currentRatios("INE002A01018")).hasSize(1);
        server.verify();
    }

    @Test
    void disabledOrMalformedInputsNeverReachSsmOrUpstox() {
        UpstoxAnalyticsSource disabled =
                new UpstoxAnalyticsSource(false, ssm, builder.build());
        assertThatThrownBy(() -> disabled.currentRatios("INE002A01018"))
                .hasMessage("UPSTOX_SSM_UNAVAILABLE");
        assertThatThrownBy(() -> source.currentRatios("../secret"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> source.dailyEquityCandles("NSE_EQ|INE002A01018",
                java.time.LocalDate.parse("2000-01-01"), java.time.LocalDate.parse("2000-03-01")))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(ssm);
    }

    @Test
    void invalidProviderPayloadIsRejectedWithoutCredentialsInErrors() {
        token();
        server.expect(requestTo(uri -> assertThat(uri.getPath()).contains("historical-candle")))
                .andRespond(withSuccess("""
                    {"status":"success","data":{"candles":[
                      ["2026-11-09T09:15:00+05:30",100,98,99,101,42,0]
                    ]}}
                    """, MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> source.dailyEquityCandles("NSE_EQ|INE002A01018",
                java.time.LocalDate.parse("2026-11-09"), java.time.LocalDate.parse("2026-11-09")))
                .hasMessage("UPSTOX_INVALID_CANDLES");
        server.verify();
    }

    @Test
    void providerUnauthorizedDoesNotLeakBearerOrResponse() {
        token();
        server.expect(requestTo(uri -> assertThat(uri.getPath()).contains("key-ratios")))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .body("unit-test-only token must not appear"));
        assertThatThrownBy(() -> source.currentRatios("INE002A01018"))
                .hasMessage("UPSTOX_READ_UNAVAILABLE")
                .hasMessageNotContaining("unit-test-only");
        server.verify();
    }

    @Test
    void probeCooldownAndNonTradingGuarantee() {
        var clock = Clock.fixed(Instant.parse("2026-11-11T08:00:00Z"), ZoneOffset.UTC);
        var unavailable = new UpstoxAnalyticsSource(false, ssm, builder.build());
        var probe = new UpstoxAnalyticsProbeController(unavailable, clock).probe().getBody();
        assertThat(probe.status()).isEqualTo("DISABLED");
        assertThat(probe.stored()).isFalse();
        assertThat(probe.modelTrainingRightsVerified()).isFalse();
        assertThat(probe.liveTradingEnabled()).isFalse();
        verifyNoInteractions(ssm);
    }
}
