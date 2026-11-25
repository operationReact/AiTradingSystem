package in.aitradingsystem.provider.upstox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** Real Spring HTTP mock server; no live brokerage or user credentials used. */
class UpstoxFnoLiveOrderTransportTest {
    private final RestClient.Builder http = RestClient.builder();
    private final MockRestServiceServer server =
        MockRestServiceServer.bindTo(http).build();
    private final UpstoxFnoLiveOrderTransport transport =
        new UpstoxFnoLiveOrderTransport(http);

    private ObjectNode verified() {
        ObjectNode p = new ObjectMapper().createObjectNode();
        return p.put("instrument_token", "NSE_FO|43919")
            .put("order_type", "LIMIT").put("validity", "DAY")
            .put("product", "I").put("transaction_type", "BUY")
            .put("quantity", 65).put("price", 100.05)
            .put("is_amo", false).put("slice", false)
            .put("tag", "MintTick_181");
    }

    @Test
    void postsExactlyOnceToOfficialFixedHostAndReturnsOnlyValidatedOrderIds() {
        server.expect(requestTo("https://api-hft.upstox.com/v3/order/place"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization", "Bearer synthetic-token"))
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(content().string(
                org.hamcrest.Matchers.containsString("\"instrument_token\":\"NSE_FO|43919\"")))
            .andRespond(withSuccess("""
                {"status":"success","data":{"order_ids":["1644490272000"]},
                 "metadata":{"latency":30}}
                """, MediaType.APPLICATION_JSON));
        var result = transport.submitOnce("synthetic-token", verified());
        assertThat(result.outcome()).isEqualTo(UpstoxFnoLiveOrderTransport.Outcome.ACKNOWLEDGED);
        assertThat(result.orderIds()).containsExactly("1644490272000");
        assertThat(result.code()).isEqualTo("UPSTOX_FNO_ORDER_ACK");
        server.verify();
    }

    @Test
    void timeoutMeansUnknownNotRejectedAndNeverAutoRetries() {
        server.expect(requestTo("https://api-hft.upstox.com/v3/order/place"))
            .andRespond(request -> { throw new IOException("broker accepted but socket died"); });
        var result = transport.submitOnce("synthetic-token", verified());
        assertThat(result.outcome()).isEqualTo(UpstoxFnoLiveOrderTransport.Outcome.UNKNOWN);
        assertThat(result.orderIds()).isEmpty();
        assertThat(result.code()).isEqualTo("UPSTOX_FNO_BROKER_STATUS_UNKNOWN");
        server.verify();
    }

    @Test
    void providerRateLimitAlsoRequiresReconciliationAndNeverRetries() {
        server.expect(requestTo("https://api-hft.upstox.com/v3/order/place"))
            .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        assertThat(transport.submitOnce("synthetic-token", verified()).outcome())
            .isEqualTo(UpstoxFnoLiveOrderTransport.Outcome.UNKNOWN);
        server.verify();
    }

    @Test
    void rejectedAuthorizationNeverLeaksBrokerBody() {
        server.expect(requestTo("https://api-hft.upstox.com/v3/order/place"))
            .andRespond(withStatus(HttpStatus.FORBIDDEN)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"message\":\"sensitive account body\"}"));
        var result = transport.submitOnce("synthetic-token", verified());
        assertThat(result.outcome()).isEqualTo(UpstoxFnoLiveOrderTransport.Outcome.REJECTED);
        assertThat(result.code()).isEqualTo("UPSTOX_FNO_ORDER_PERMISSION_DENIED");
        assertThat(result.toString()).doesNotContain("sensitive account body");
        server.verify();
    }

    @Test
    void missingAckAndMalformedJsonBecomeUnknownWithoutSecondOrder() {
        server.expect(requestTo("https://api-hft.upstox.com/v3/order/place"))
            .andRespond(withSuccess("{\"status\":\"success\",\"data\":{}}",
                MediaType.APPLICATION_JSON));
        assertThat(transport.submitOnce("synthetic-token", verified()).outcome())
            .isEqualTo(UpstoxFnoLiveOrderTransport.Outcome.UNKNOWN);
        server.verify();
    }

    @Test
    void invalidMarketOrderPayloadIsRejectedBeforeAnyNetworkCall() {
        ObjectNode p = verified().put("order_type", "MARKET");
        var result = transport.submitOnce("synthetic-token", p);
        assertThat(result.outcome()).isEqualTo(UpstoxFnoLiveOrderTransport.Outcome.REJECTED);
        assertThat(result.code()).isEqualTo("UPSTOX_FNO_INVALID_ORDER");
        assertThat(transport.submitOnce(null, verified()).code())
            .isEqualTo("UPSTOX_SESSION_MISSING");
        server.verify();
    }
}
