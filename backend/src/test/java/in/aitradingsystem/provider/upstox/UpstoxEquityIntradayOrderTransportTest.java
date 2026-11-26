package in.aitradingsystem.provider.upstox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class UpstoxEquityIntradayOrderTransportTest {
    private final RestClient.Builder http = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final UpstoxEquityIntradayLimitOrderPayload builder =
            new UpstoxEquityIntradayLimitOrderPayload(mapper);
    private final UpstoxEquityIntradayOrderTransport transport =
            new UpstoxEquityIntradayOrderTransport(http);

    private UpstoxEquityIntradayLimitOrderPayload.VerifiedOrder verified() {
        return new UpstoxEquityIntradayLimitOrderPayload.VerifiedOrder(
                "NSE_EQ|INE009A01021", "BUY", 2,
                new BigDecimal("1500.00"), new BigDecimal("0.05"), "MintTick_901");
    }

    @Test
    void buildsOnlyIntradayNseLimitDayPayload() {
        var json = builder.build(verified());
        assertThat(json.path("product").asText()).isEqualTo("I");
        assertThat(json.path("order_type").asText()).isEqualTo("LIMIT");
        assertThat(json.path("validity").asText()).isEqualTo("DAY");
        assertThat(json.path("instrument_token").asText()).startsWith("NSE_EQ|");
        assertThat(json.path("is_amo").asBoolean()).isFalse();
        assertThat(json.path("slice").asBoolean()).isFalse();
    }

    @Test
    void rejectsNonEquityKeysBadSideOffTickPriceAndInvalidTag() {
        assertThatThrownBy(() -> builder.build(new UpstoxEquityIntradayLimitOrderPayload.VerifiedOrder(
                "NSE_FO|1", "BUY", 1, new BigDecimal("10"), new BigDecimal("0.05"), "MintTick_901")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.build(new UpstoxEquityIntradayLimitOrderPayload.VerifiedOrder(
                "NSE_EQ|ABC", "SHORT", 1, new BigDecimal("10"), new BigDecimal("0.05"), "MintTick_901")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.build(new UpstoxEquityIntradayLimitOrderPayload.VerifiedOrder(
                "NSE_EQ|ABC", "BUY", 1, new BigDecimal("10.03"), new BigDecimal("0.05"), "MintTick_901")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.build(new UpstoxEquityIntradayLimitOrderPayload.VerifiedOrder(
                "NSE_EQ|ABC", "BUY", 1, new BigDecimal("10"), new BigDecimal("0.05"), "bad")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void submitsOneMockedRequestAndValidatesAck() {
        server.expect(requestTo("https://api-hft.upstox.com/v3/order/place"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer synthetic-token"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"product\":\"I\"")))
                .andRespond(withSuccess("{\"status\":\"success\",\"data\":{\"order_ids\":[\"12345\"]}}",
                        MediaType.APPLICATION_JSON));
        var result = transport.submitOnce("synthetic-token", builder.build(verified()));
        assertThat(result.outcome()).isEqualTo(UpstoxEquityIntradayOrderTransport.Outcome.ACKNOWLEDGED);
        assertThat(result.orderIds()).containsExactly("12345");
        server.verify();
    }

    @Test
    void unknownTimeoutAndRateLimitDoNotRetry() {
        server.expect(requestTo("https://api-hft.upstox.com/v3/order/place"))
                .andRespond(request -> { throw new IOException("ambiguous timeout"); });
        assertThat(transport.submitOnce("synthetic-token", builder.build(verified())).outcome())
                .isEqualTo(UpstoxEquityIntradayOrderTransport.Outcome.UNKNOWN);
        server.verify();
    }

    @Test
    void rateLimitIsUnknownAndMarketPayloadIsRejectedBeforeNetwork() {
        server.expect(requestTo("https://api-hft.upstox.com/v3/order/place"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        assertThat(transport.submitOnce("synthetic-token", builder.build(verified())).outcome())
                .isEqualTo(UpstoxEquityIntradayOrderTransport.Outcome.UNKNOWN);
        server.verify();

        var invalid = builder.build(verified()).put("order_type", "MARKET");
        assertThat(transport.submitOnce("synthetic-token", invalid).outcome())
                .isEqualTo(UpstoxEquityIntradayOrderTransport.Outcome.REJECTED);
    }
}
