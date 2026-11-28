package in.aitradingsystem.provider.upstox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.Set;

/**
 * Offline Upstox V3 payload builder for a cash-equity intraday LIMIT order.
 * This class is deliberately not a Spring bean and does not authorize or
 * submit orders. A future coordinator must establish fresh quote, market
 * session, owner consent, static egress IP, risk reservation, idempotency and
 * reconciliation before using the payload.
 */
final class UpstoxEquityIntradayLimitOrderPayload {
    private final ObjectMapper mapper;

    UpstoxEquityIntradayLimitOrderPayload(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    record VerifiedOrder(String instrumentKey, String side, int quantity,
                         BigDecimal limitPrice, BigDecimal tickSize, String tag) {
        @Override public String toString() { return "VerifiedOrder[redacted]"; }
    }

    ObjectNode build(VerifiedOrder order) {
        if (order == null
                || order.instrumentKey() == null
                || !order.instrumentKey().matches("NSE_EQ\\|[A-Za-z0-9._-]{1,64}")
                || order.side() == null
                || !Set.of("BUY", "SELL").contains(order.side())
                || order.quantity() < 1 || order.quantity() > 10000) {
            throw new IllegalArgumentException("Verified NSE cash-equity order required");
        }
        BigDecimal price = order.limitPrice();
        BigDecimal tick = order.tickSize();
        if (price == null || price.signum() <= 0 || price.scale() > 2
                || price.compareTo(new BigDecimal("10000000")) > 0
                || tick == null || tick.signum() <= 0 || tick.scale() > 2
                || price.remainder(tick).signum() != 0) {
            throw new IllegalArgumentException("Verified cash-equity limit tick required");
        }
        if (order.tag() == null || !order.tag().matches("[A-Za-z0-9_-]{8,20}")) {
            throw new IllegalArgumentException("Unique validated broker order tag required");
        }
        return mapper.createObjectNode()
                .put("quantity", order.quantity())
                .put("product", "I")
                .put("validity", "DAY")
                .put("price", price)
                .put("tag", order.tag())
                .put("instrument_token", order.instrumentKey())
                .put("order_type", "LIMIT")
                .put("transaction_type", order.side())
                .put("disclosed_quantity", 0)
                .put("trigger_price", 0)
                .put("is_amo", false)
                .put("slice", false);
    }
}
