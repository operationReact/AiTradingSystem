package in.aitradingsystem.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.aitradingsystem.instrument.Instrument;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;

class LambdaAngelOneMarketDataProviderTest {
    private static Instrument instrument() {
        return new Instrument(3, "NSE_EQ", "NSE", "123", "TEST-EQ",
                "Test", "EQ", null, null, 1, new BigDecimal("0.05"), UUID.randomUUID());
    }

    private static LambdaAngelOneMarketDataProvider adapter(LambdaClient client) {
        return new LambdaAngelOneMarketDataProvider(client, new ObjectMapper(),
                new HistoricalCandleParser(), "minttick-dev-angelone-feed");
    }

    @Test
    void normalizesQuoteAndRejectsNonPositivePrice() {
        LambdaClient client = mock(LambdaClient.class);
        when(client.invoke(any(InvokeRequest.class))).thenReturn(InvokeResponse.builder()
                .statusCode(200).payload(SdkBytes.fromUtf8String(
                        "{\"ok\":true,\"source\":\"ANGEL_ONE_SMARTAPI\",\"ltp\":\"125.90\"}")).build());
        assertThat(adapter(client).lastTradedPrice(instrument())).isEqualByComparingTo("125.90");

        when(client.invoke(any(InvokeRequest.class))).thenReturn(InvokeResponse.builder()
                .statusCode(200).payload(SdkBytes.fromUtf8String(
                        "{\"ok\":true,\"source\":\"ANGEL_ONE_SMARTAPI\",\"ltp\":\"-1\"}")).build());
        assertThatThrownBy(() -> adapter(client).lastTradedPrice(instrument()))
                .isInstanceOf(MarketDataException.class).hasMessageContaining("invalid response");
    }

    @Test
    void neverPropagatesLambdaErrorPayloadToCaller() {
        LambdaClient client = mock(LambdaClient.class);
        when(client.invoke(any(InvokeRequest.class))).thenReturn(InvokeResponse.builder()
                .statusCode(200).functionError("Unhandled")
                .payload(SdkBytes.fromUtf8String("SECRET_PRIVATE_BODY")).build());
        assertThatThrownBy(() -> adapter(client).lastTradedPrice(instrument()))
                .isInstanceOf(MarketDataException.class)
                .hasMessageNotContaining("SECRET_PRIVATE_BODY");
    }

    @Test
    void normalizesCandlesWithoutBrokerEnvelope() {
        LambdaClient client = mock(LambdaClient.class);
        when(client.invoke(any(InvokeRequest.class))).thenReturn(InvokeResponse.builder()
                .statusCode(200).payload(SdkBytes.fromUtf8String(
                    "{\"ok\":true,\"source\":\"ANGEL_ONE_SMARTAPI\",\"candles\":[[\""
                    + "2026-11-05T09:15:00+05:30\",100,105,99,104,123]]}")).build());
        var candles = adapter(client).historicalCandles("NSE", "123", "ONE_DAY",
                "2026-11-05 09:15", "2026-11-05 15:30");
        assertThat(candles).hasSize(1);
        assertThat(candles.getFirst().close()).isEqualByComparingTo("104");
    }

    @Test
    void requiresExplicitFunctionName() {
        assertThatThrownBy(() -> new LambdaAngelOneMarketDataProvider(
                mock(LambdaClient.class), new ObjectMapper(), new HistoricalCandleParser(), ""))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
