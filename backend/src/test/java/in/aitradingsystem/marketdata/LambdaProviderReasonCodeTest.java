package in.aitradingsystem.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.aitradingsystem.provider.angelone.AngelOneException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;

/** Known, secret-free Lambda reason codes reach the operator; unknown ones stay generic. */
class LambdaProviderReasonCodeTest {

    private static String codeFromLambda(String lambdaCode) {
        LambdaClient lambda = mock(LambdaClient.class);
        String body = "{\"ok\":false,\"code\":\"" + lambdaCode + "\"}";
        InvokeResponse response = InvokeResponse.builder().statusCode(200)
                .payload(SdkBytes.fromString(body, StandardCharsets.UTF_8)).build();
        when(lambda.invoke(any(InvokeRequest.class))).thenReturn(response);
        LambdaAngelOneMarketDataProvider provider = new LambdaAngelOneMarketDataProvider(
                lambda, new ObjectMapper(), new HistoricalCandleParser(), "market-feed-fn");
        try {
            provider.historicalCandles("NSE", "2885", "FIVE_MINUTE", "2026-11-03 09:15", "2026-11-03 15:30");
            throw new AssertionError("expected a market-data failure");
        } catch (MarketDataException ex) {
            return ex.code();
        }
    }

    @Test
    void knownLambdaCodesArePassedThrough() {
        assertThat(codeFromLambda("PROVIDER_REJECTED")).isEqualTo("PROVIDER_REJECTED");
        assertThat(codeFromLambda("PROVIDER_INVALID_RESPONSE")).isEqualTo("PROVIDER_INVALID_RESPONSE");
        assertThat(codeFromLambda("INTERNAL_FAILURE")).isEqualTo("INTERNAL_FAILURE");
    }

    @Test
    void unknownLambdaCodesStayGeneric() {
        assertThat(codeFromLambda("SOMETHING_NEW")).isEqualTo("PROVIDER_UNAVAILABLE");
    }

    @Test
    void invokeFailuresGetClassLevelCodes() {
        assertThat(LambdaAngelOneMarketDataProvider.invokeCode(
                software.amazon.awssdk.services.lambda.model.ResourceNotFoundException.builder().message("x").build()))
            .isEqualTo("PROVIDER_INVOKE_NOT_FOUND");
        assertThat(LambdaAngelOneMarketDataProvider.invokeCode(
                (software.amazon.awssdk.services.lambda.model.LambdaException)
                    software.amazon.awssdk.services.lambda.model.LambdaException.builder().message("x").build()))
            .isEqualTo("PROVIDER_INVOKE_FAILED");
    }

    @Test
    void configurationCodesStillMapToNotConfigured() {
        assertThat(codeFromLambda("SSM_UNAVAILABLE")).isEqualTo("PROVIDER_NOT_CONFIGURED");
    }
}
