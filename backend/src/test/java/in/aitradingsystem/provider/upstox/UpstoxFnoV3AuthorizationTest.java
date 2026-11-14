package in.aitradingsystem.provider.upstox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;
import software.amazon.awssdk.services.ssm.model.Parameter;
import software.amazon.awssdk.services.ssm.model.ParameterType;

class UpstoxFnoV3AuthorizationTest {
    private final SsmClient ssm = mock(SsmClient.class);
    private final RestClient.Builder builder =
            RestClient.builder().baseUrl("https://api.upstox.com");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final UpstoxAnalyticsSource source =
            new UpstoxAnalyticsSource(true, ssm, builder.build());

    private void token() {
        when(ssm.getParameter(any(GetParameterRequest.class))).thenAnswer(inv -> {
            GetParameterRequest request = inv.getArgument(0);
            assertThat(request.name()).isEqualTo("/minttick/upstox/analytics-token");
            assertThat(request.withDecryption()).isTrue();
            return GetParameterResponse.builder().parameter(Parameter.builder()
                    .name(request.name()).type(ParameterType.SECURE_STRING)
                    .value("sample-token-never-log").build()).build();
        });
    }

    @Test
    void permitsOnlyOneUseOfficialWssUriObtainedThroughExistingSsmToken() {
        token();
        server.expect(requestTo("https://api.upstox.com/v3/feed/market-data-feed/authorize"))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer sample-token-never-log"))
                .andRespond(withSuccess("""
                    {"status":"success","data":{"authorized_redirect_uri":
                    "wss://prod-feed.upstox.com/market-data-feeder/v3/upstox-developer-api/feeds?requestId=abc&code=private-one-use-code"}}
                    """, MediaType.APPLICATION_JSON));
        var uri = source.authorizeFnoMarketFeedV3();
        assertThat(uri.getScheme()).isEqualTo("wss");
        assertThat(uri.getHost()).isEqualTo("prod-feed.upstox.com");
        assertThat(uri.getRawQuery()).contains("code=private-one-use-code");
        server.verify();
    }

    @Test
    void rejectsAttackerControlledWssHostWithoutLeakingSecretUrl() {
        token();
        server.expect(requestTo("https://api.upstox.com/v3/feed/market-data-feed/authorize"))
                .andRespond(withSuccess("""
                    {"status":"success","data":{"authorized_redirect_uri":
                    "wss://upstox.com.evil.example/market-data-feeder/v3/feeds?code=private-secret"}}
                    """, MediaType.APPLICATION_JSON));
        assertThatThrownBy(source::authorizeFnoMarketFeedV3)
                .hasMessage("UPSTOX_FNO_WS_AUTH_UNAVAILABLE")
                .hasMessageNotContaining("private-secret")
                .hasMessageNotContaining("evil.example");
        server.verify();
    }

    @Test
    void disabledSourceDoesNotReadSsmOrProvider() {
        var disabled = new UpstoxAnalyticsSource(false, ssm, builder.build());
        assertThatThrownBy(disabled::authorizeFnoMarketFeedV3)
                .hasMessage("UPSTOX_ANALYTICS_DISABLED");
        verifyNoInteractions(ssm);
    }

    @Test
    void rejectsUnsafeSchemeAndMissingOneUseParameters() {
        token();
        server.expect(requestTo("https://api.upstox.com/v3/feed/market-data-feed/authorize"))
                .andRespond(withSuccess("""
                    {"status":"success","data":{"authorized_redirect_uri":
                    "https://prod-feed.upstox.com/market-data-feeder/v3/feeds?code=secret"}}
                    """, MediaType.APPLICATION_JSON));
        assertThatThrownBy(source::authorizeFnoMarketFeedV3)
                .hasMessage("UPSTOX_FNO_WS_AUTH_UNAVAILABLE");
        server.verify();
    }
}
