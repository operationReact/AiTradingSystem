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
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.ResourceAccessException;

/**
 * Inactive, package-private Upstox V3 order transport (no Spring bean and no
 * customer/admin route). It must not be wired until a durable, owner-bound
 * execution coordinator enforces broker authorization, fixed IP, NSE_FO
 * entitlement, consent, cash/margin risk reservations and reconciliation.
 *
 * <p>The broker can accept a request even when its response times out.
 * UNKNOWN never means reject; the future coordinator MUST first reconcile
 * against the broker order book and unique order tag before any retry.
 */
final class UpstoxFnoLiveOrderTransport {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_ACK_BYTES = 16384;
    private final RestClient http;

    UpstoxFnoLiveOrderTransport(RestClient.Builder builder) {
        // Caller supplies its bounded production HTTP transport. A separate
        // mock RestClient.Builder is permitted for never-live contract tests.
        this.http = builder.baseUrl("https://api-hft.upstox.com").build();
    }

    enum Outcome { ACKNOWLEDGED, REJECTED, UNKNOWN }

    record Result(Outcome outcome, List<String> orderIds, String code) {
        Result {
            orderIds = List.copyOf(orderIds);
        }
    }

    /**
     * Not a trading authorization. The payload must come from the validated
     * UpstoxFnoLimitOrderPayload builder under a separate approved coordinator.
     * This method does exactly ONE HTTP POST and does not retry.
     */
    Result submitOnce(String accessToken, ObjectNode verifiedPayload) {
        if (accessToken == null || accessToken.isBlank()) {
            return new Result(Outcome.REJECTED, List.of(), "UPSTOX_SESSION_MISSING");
        }
        if (!validPayload(verifiedPayload)) {
            return new Result(Outcome.REJECTED, List.of(), "UPSTOX_FNO_INVALID_ORDER");
        }
        try {
            String ack = http.post()
                .uri("/v3/order/place")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                // Spring Boot's converters may use Jackson 3; send already
                // validated Jackson 2 JSON rather than silently re-mapping.
                .body(verifiedPayload.toString())
                .retrieve().body(String.class);
            if (ack == null || ack.isBlank() || ack.length() > MAX_ACK_BYTES) {
                return new Result(Outcome.UNKNOWN, List.of(), "UPSTOX_FNO_ACK_UNCERTAIN");
            }
            JsonNode response = JSON.readTree(ack);
            if (response == null || !"success".equals(response.path("status").asText())) {
                return new Result(Outcome.UNKNOWN, List.of(), "UPSTOX_FNO_ACK_UNCERTAIN");
            }
            JsonNode list = response.path("data").path("order_ids");
            if (!list.isArray() || list.isEmpty() || list.size() > 10) {
                return new Result(Outcome.UNKNOWN, List.of(), "UPSTOX_FNO_ACK_UNCERTAIN");
            }
            List<String> ids = new ArrayList<>();
            for (JsonNode id : list) {
                if (!id.isTextual() || !id.asText().matches("[A-Za-z0-9_-]{1,64}")) {
                    return new Result(Outcome.UNKNOWN, List.of(), "UPSTOX_FNO_ACK_UNCERTAIN");
                }
                ids.add(id.asText());
            }
            if (Set.copyOf(ids).size() != ids.size()) {
                return new Result(Outcome.UNKNOWN, List.of(), "UPSTOX_FNO_ACK_UNCERTAIN");
            }
            return new Result(Outcome.ACKNOWLEDGED, ids, "UPSTOX_FNO_ORDER_ACK");
        } catch (RestClientResponseException ex) {
            final int status = ex.getStatusCode().value();
            // Only unequivocal client-side rejections are treated as final.
            // A timeout, 408, 409, 429 or 5xx is UNKNOWN: no blind retry.
            if (status == 401) {
                return new Result(Outcome.REJECTED, List.of(), "UPSTOX_SESSION_EXPIRED");
            }
            if (status == 403) {
                return new Result(Outcome.REJECTED, List.of(), "UPSTOX_FNO_ORDER_PERMISSION_DENIED");
            }
            if (status >= 400 && status < 500
                && status != 408 && status != 409 && status != 429) {
                return new Result(Outcome.REJECTED, List.of(), "UPSTOX_FNO_BROKER_REJECTED");
            }
            return new Result(Outcome.UNKNOWN, List.of(), "UPSTOX_FNO_BROKER_STATUS_UNKNOWN");
        } catch (ResourceAccessException | JsonProcessingException ex) {
            return new Result(Outcome.UNKNOWN, List.of(), "UPSTOX_FNO_BROKER_STATUS_UNKNOWN");
        } catch (RuntimeException ex) {
            // Never reflect raw upstream messages, signed URLs, access tokens
            // or response bodies into logs or caller-visible error codes.
            return new Result(Outcome.UNKNOWN, List.of(), "UPSTOX_FNO_BROKER_STATUS_UNKNOWN");
        }
    }

    private static boolean validPayload(ObjectNode x) {
        return x != null
            && x.path("instrument_token").asText("").matches("NSE_FO\\|[A-Za-z0-9._-]{1,64}")
            && "LIMIT".equals(x.path("order_type").asText())
            && "DAY".equals(x.path("validity").asText())
            && Set.of("I","D").contains(x.path("product").asText())
            && Set.of("BUY","SELL").contains(x.path("transaction_type").asText())
            && x.path("quantity").isIntegralNumber() && x.path("quantity").asInt() > 0
            && x.path("quantity").asInt() <= 100000
            && x.path("price").isNumber() && x.path("price").decimalValue().signum() > 0
            && !x.path("is_amo").asBoolean(true)
            && !x.path("slice").asBoolean(true)
            && x.path("tag").asText("").matches("[A-Za-z0-9_-]{8,20}");
    }
}
