package in.aitradingsystem.provider.upstox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import java.io.IOException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** HTTP contract test for the dormant transport; never contacts a broker. */
class UpstoxIntradayEquityOrderTransportTest {
    private final RestClient.Builder http = RestClient.builder();
    private final MockRestServiceServer server =
        MockRestServiceServer.bindTo(http).build();
    private final UpstoxIntradayEquityOrderPayload payloadBuilder =
        new UpstoxIntradayEquityOrderPayload();
    private final UpstoxIntradayEquityOrderTransport transport =
        new UpstoxIntradayEquityOrderTransport(http);

    private com.fasterxml.jackson.databind.node.ObjectNode verified() {
        return payloadBuilder.build(new UpstoxIntradayEquityOrderPayload.VerifiedOrder(
            "NSE_EQ|INE528G01035", "BUY", 1, new BigDecimal("20.05"), "mt_equity_01"));
    }

    @Test
    void builderPinsCashIntradayLimitDayAndDisablesSlicingAndAmo() {
        var request = verified();
        assertThat(request.path("instrument_token").asText()).isEqualTo("NSE_EQ|INE528G01035");
        assertThat(request.path("transaction_type").asText()).isEqualTo("BUY");
        assertThat(request.path("product").asText()).isEqualTo("I");
        assertThat(request.path("order_type").asText()).isEqualTo("LIMIT");
        assertThat(request.path("validity").asText()).isEqualTo("DAY");
        assertThat(request.path("price").decimalValue()).isEqualByComparingTo("20.05");
        assertThat(request.path("is_amo").asBoolean()).isFalse();
        assertThat(request.path("slice").asBoolean()).isFalse();
    }

    @Test
    void postsOneCashLimitRequestAndValidatesSingleOrderId() {
        server.expect(requestTo("https://api-hft.upstox.com/v3/order/place"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization", "Bearer synthetic-token"))
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(content().string(org.hamcrest.Matchers.containsString(
                "\"instrument_token\":\"NSE_EQ|INE528G01035\"")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString(
                "\"product\":\"I\"")))
            .andRespond(withSuccess("""
                {"status":"success","data":{"order_ids":["1644490272000"]},
                 "metadata":{"latency":30}}
                """, MediaType.APPLICATION_JSON));
        var result = transport.submitOnce("synthetic-token", verified());
        assertThat(result.outcome()).isEqualTo(UpstoxIntradayEquityOrderTransport.Outcome.ACKNOWLEDGED);
        assertThat(result.orderId()).isEqualTo("1644490272000");
        assertThat(result.code()).isEqualTo("UPSTOX_EQUITY_ORDER_ACK");
        server.verify();
    }

    @Test
    void timeoutAndRateLimitRemainUnknownWithoutRetry() {
        server.expect(requestTo("https://api-hft.upstox.com/v3/order/place"))
            .andRespond(request -> { throw new IOException("accepted then disconnected"); });
        var timeout = transport.submitOnce("synthetic-token", verified());
        assertThat(timeout.outcome()).isEqualTo(UpstoxIntradayEquityOrderTransport.Outcome.UNKNOWN);
        server.verify();

        var rateLimitedHttp = RestClient.builder();
        var rateLimited = MockRestServiceServer.bindTo(rateLimitedHttp).build();
        var rateLimitedTransport = new UpstoxIntradayEquityOrderTransport(rateLimitedHttp);
        rateLimited.expect(requestTo("https://api-hft.upstox.com/v3/order/place"))
            .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        assertThat(rateLimitedTransport.submitOnce("synthetic-token", verified()).outcome())
            .isEqualTo(UpstoxIntradayEquityOrderTransport.Outcome.UNKNOWN);
        rateLimited.verify();
    }

    @Test
    void refusesDerivativeOrMarketPayloadBeforeCallingProvider() {
        var derivative = verified().put("instrument_token", "NSE_FO|43919");
        var market = verified().put("order_type", "MARKET");
        assertThat(transport.submitOnce("synthetic-token", derivative).outcome())
            .isEqualTo(UpstoxIntradayEquityOrderTransport.Outcome.REJECTED);
        assertThat(transport.submitOnce("synthetic-token", market).outcome())
            .isEqualTo(UpstoxIntradayEquityOrderTransport.Outcome.REJECTED);
        assertThat(transport.submitOnce(null, verified()).code())
            .isEqualTo("UPSTOX_SESSION_MISSING");
        server.verify();
    }

    @Test
    void payloadBuilderRejectsInvalidIdentityQuantityPriceAndTag() {
        assertThatThrownBy(() -> payloadBuilder.build(
            new UpstoxIntradayEquityOrderPayload.VerifiedOrder(
                "NSE_FO|43919", "BUY", 1, new BigDecimal("20.05"), "mt_equity_01")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> payloadBuilder.build(
            new UpstoxIntradayEquityOrderPayload.VerifiedOrder(
                "NSE_EQ|INE528G01035", "BUY", 10001,
                new BigDecimal("20.05"), "mt_equity_01")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> payloadBuilder.build(
            new UpstoxIntradayEquityOrderPayload.VerifiedOrder(
                "NSE_EQ|INE528G01035", "BUY", 1,
                new BigDecimal("20.055"), "mt_equity_01")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> payloadBuilder.build(
            new UpstoxIntradayEquityOrderPayload.VerifiedOrder(
                "NSE_EQ|INE528G01035", "BUY", 1,
                new BigDecimal("20.05"), "bad")))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
