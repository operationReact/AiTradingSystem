package in.aitradingsystem.provider.upstox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Inactive one-shot transport for Upstox V3 cash-equity intraday LIMIT orders.
 * It has no Spring bean and no API route; a durable safety coordinator must
 * be completed before any production caller can use it.
 */
final class UpstoxIntradayEquityOrderTransport {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final RestClient http;

    UpstoxIntradayEquityOrderTransport(RestClient.Builder builder) {
        this.http = builder.baseUrl("https://api-hft.upstox.com").build();
    }

    enum Outcome { ACKNOWLEDGED, REJECTED, UNKNOWN }

    record Result(Outcome outcome, String orderId, String code) {}

    /** Exactly one POST. Timeouts, throttles and server errors are UNKNOWN. */
    Result submitOnce(String accessToken, ObjectNode payload) {
        if (accessToken == null || accessToken.isBlank()) {
            return new Result(Outcome.REJECTED, null, "UPSTOX_SESSION_MISSING");
        }
        if (!validPayload(payload)) {
            return new Result(Outcome.REJECTED, null, "UPSTOX_EQUITY_INVALID_ORDER");
        }
        try {
            String body = http.post()
                .uri("/v3/order/place")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(payload.toString())
                .retrieve().body(String.class);
            if (body == null || body.isBlank() || body.length() > 16384) {
                return unknown();
            }
            JsonNode response = JSON.readTree(body);
            JsonNode ids = response.path("data").path("order_ids");
            if (!"success".equals(response.path("status").asText())
                    || !ids.isArray() || ids.size() != 1
                    || !ids.get(0).isTextual()
                    || !ids.get(0).asText().matches("[A-Za-z0-9_-]{1,64}")) {
                return unknown();
            }
            return new Result(Outcome.ACKNOWLEDGED, ids.get(0).asText(),
                "UPSTOX_EQUITY_ORDER_ACK");
        } catch (RestClientResponseException ex) {
            int status = ex.getStatusCode().value();
            if (status == 401) {
                return new Result(Outcome.REJECTED, null, "UPSTOX_SESSION_EXPIRED");
            }
            if (status == 403) {
                return new Result(Outcome.REJECTED, null,
                    "UPSTOX_EQUITY_ORDER_PERMISSION_DENIED");
            }
            if (status >= 400 && status < 500
                    && status != 408 && status != 409 && status != 429) {
                return new Result(Outcome.REJECTED, null, "UPSTOX_EQUITY_BROKER_REJECTED");
            }
            return unknown();
        } catch (ResourceAccessException | JsonProcessingException ex) {
            return unknown();
        } catch (RuntimeException ex) {
            return unknown();
        }
    }

    private static Result unknown() {
        return new Result(Outcome.UNKNOWN, null, "UPSTOX_EQUITY_BROKER_STATUS_UNKNOWN");
    }

    private static boolean validPayload(ObjectNode payload) {
        return payload != null
            && payload.path("instrument_token").asText("")
                .matches("(?:NSE|BSE)_EQ\\|[A-Z]{2}[A-Z0-9]{9}[0-9]")
            && "LIMIT".equals(payload.path("order_type").asText())
            && "DAY".equals(payload.path("validity").asText())
            && "I".equals(payload.path("product").asText())
            && Set.of("BUY", "SELL").contains(payload.path("transaction_type").asText())
            && payload.path("quantity").canConvertToInt()
            && payload.path("quantity").asInt() >= 1
            && payload.path("quantity").asInt() <= 10000
            && payload.path("price").isNumber()
            && payload.path("price").decimalValue().signum() > 0
            && payload.path("disclosed_quantity").asInt(-1) == 0
            && payload.path("trigger_price").asInt(-1) == 0
            && !payload.path("is_amo").asBoolean(true)
            && !payload.path("slice").asBoolean(true)
            && payload.path("tag").asText("").matches("[A-Za-z0-9_-]{8,20}");
    }
}
