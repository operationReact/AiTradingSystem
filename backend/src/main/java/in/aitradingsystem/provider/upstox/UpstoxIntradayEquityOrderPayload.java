package in.aitradingsystem.provider.upstox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;

/** Builds only a single-unit-source, DAY/LIMIT Upstox cash-equity MIS payload. */
public final class UpstoxIntradayEquityOrderPayload {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final java.util.regex.Pattern EQUITY_KEY =
        java.util.regex.Pattern.compile("(?:NSE|BSE)_EQ\\|[A-Z]{2}[A-Z0-9]{9}[0-9]");

    public record VerifiedOrder(String instrumentKey, String side, int quantity,
            BigDecimal limitPrice, String tag) {}

    public ObjectNode build(VerifiedOrder order) {
        if (order == null || order.instrumentKey() == null
                || !EQUITY_KEY.matcher(order.instrumentKey()).matches()
                || !"BUY".equals(order.side()) && !"SELL".equals(order.side())
                || order.quantity() < 1 || order.quantity() > 10000
                || order.limitPrice() == null || order.limitPrice().signum() <= 0
                || order.limitPrice().scale() > 2
                || order.limitPrice().compareTo(new BigDecimal("10000000")) > 0
                || order.tag() == null || !order.tag().matches("[A-Za-z0-9_-]{8,20}")) {
            throw new IllegalArgumentException("Verified NSE/BSE intraday LIMIT order required");
        }
        return JSON.createObjectNode()
            .put("instrument_token", order.instrumentKey())
            .put("transaction_type", order.side())
            .put("quantity", order.quantity())
            .put("product", "I")
            .put("order_type", "LIMIT")
            .put("validity", "DAY")
            .put("price", order.limitPrice())
            .put("tag", order.tag())
            .put("disclosed_quantity", 0)
            .put("trigger_price", 0)
            .put("is_amo", false)
            .put("slice", false);
    }
}
