package in.aitradingsystem.provider.upstox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Dormant, package-private one-shot Upstox transport. It is intentionally not
 * registered or routed. Any timeout/ambiguous response is UNKNOWN and must be
 * reconciled by order tag before another attempt; this class never retries.
 */
final class UpstoxEquityIntradayOrderTransport {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final RestClient http;

    UpstoxEquityIntradayOrderTransport(RestClient.Builder builder) {
        http = builder.baseUrl("https://api-hft.upstox.com").build();
    }

    enum Outcome { ACKNOWLEDGED, REJECTED, UNKNOWN }
    record Result(Outcome outcome, List<String> orderIds, String code) {
        Result { orderIds = List.copyOf(orderIds); }
        @Override public String toString() { return "Result[" + outcome + ", " + code + "]"; }
    }

    Result submitOnce(String accessToken, ObjectNode payload) {
        if (accessToken == null || accessToken.isBlank())
            return new Result(Outcome.REJECTED, List.of(), "UPSTOX_SESSION_MISSING");
        if (!valid(payload))
            return new Result(Outcome.REJECTED, List.of(), "UPSTOX_EQUITY_INVALID_ORDER");
        try {
            String ack = http.post().uri("/v3/order/place")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                    .body(payload.toString()).retrieve().body(String.class);
            if (ack == null || ack.isBlank() || ack.length() > 16384)
                return unknown("UPSTOX_EQUITY_ACK_UNCERTAIN");
            JsonNode response = JSON.readTree(ack);
            JsonNode ids = response == null ? null : response.path("data").path("order_ids");
            if (!"success".equals(response == null ? "" : response.path("status").asText())
                    || ids == null || !ids.isArray() || ids.isEmpty() || ids.size() > 10)
                return unknown("UPSTOX_EQUITY_ACK_UNCERTAIN");
            List<String> parsed = new ArrayList<>();
            for (JsonNode id : ids) {
                if (!id.isTextual() || !id.asText().matches("[A-Za-z0-9_-]{1,64}"))
                    return unknown("UPSTOX_EQUITY_ACK_UNCERTAIN");
                parsed.add(id.asText());
            }
            if (Set.copyOf(parsed).size() != parsed.size())
                return unknown("UPSTOX_EQUITY_ACK_UNCERTAIN");
            return new Result(Outcome.ACKNOWLEDGED, parsed, "UPSTOX_EQUITY_ORDER_ACK");
        } catch (RestClientResponseException ex) {
            int status = ex.getStatusCode().value();
            if (status == 401) return rejected("UPSTOX_SESSION_EXPIRED");
            if (status == 403) return rejected("UPSTOX_EQUITY_ORDER_PERMISSION_DENIED");
            if (status >= 400 && status < 500 && status != 408 && status != 409 && status != 429)
                return rejected("UPSTOX_EQUITY_BROKER_REJECTED");
            return unknown("UPSTOX_EQUITY_BROKER_STATUS_UNKNOWN");
        } catch (ResourceAccessException | JsonProcessingException ex) {
            return unknown("UPSTOX_EQUITY_BROKER_STATUS_UNKNOWN");
        } catch (RuntimeException ex) {
            return unknown("UPSTOX_EQUITY_BROKER_STATUS_UNKNOWN");
        }
    }

    private static boolean valid(ObjectNode x) {
        return x != null
                && x.path("instrument_token").asText("").matches("NSE_EQ\\|[A-Za-z0-9._-]{1,64}")
                && "LIMIT".equals(x.path("order_type").asText())
                && "DAY".equals(x.path("validity").asText())
                && "I".equals(x.path("product").asText())
                && Set.of("BUY", "SELL").contains(x.path("transaction_type").asText())
                && x.path("quantity").isIntegralNumber() && x.path("quantity").asInt() > 0
                && x.path("quantity").asInt() <= 10000
                && x.path("price").isNumber() && x.path("price").decimalValue().signum() > 0
                && x.path("disclosed_quantity").asInt(-1) == 0
                && x.path("trigger_price").asInt(-1) == 0
                && !x.path("is_amo").asBoolean(true) && !x.path("slice").asBoolean(true)
                && x.path("tag").asText("").matches("[A-Za-z0-9_-]{8,20}");
    }

    private static Result rejected(String code) {
        return new Result(Outcome.REJECTED, List.of(), code);
    }
    private static Result unknown(String code) {
        return new Result(Outcome.UNKNOWN, List.of(), code);
    }
}
