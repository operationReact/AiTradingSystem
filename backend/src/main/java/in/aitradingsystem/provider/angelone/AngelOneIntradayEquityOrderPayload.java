package in.aitradingsystem.provider.angelone;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.Set;

/**
 * Builds the single Angel One SmartAPI payload for an NSE/BSE cash-equity
 * intraday LIMIT DAY order. It is a pure builder: it contacts no broker and
 * authorizes nothing. Its output is only ever passed to
 * {@link CustomerAngelOneSessionService#submitVerifiedIntradayLimitOnce}.
 */
public final class AngelOneIntradayEquityOrderPayload {
    private static final ObjectMapper JSON = new ObjectMapper();

    private AngelOneIntradayEquityOrderPayload() {}

    /**
     * @param exchange     NSE or BSE
     * @param symbolToken  numeric Angel One instrument token
     * @param tradingSymbol e.g. SBIN-EQ
     * @param side         BUY or SELL
     * @param quantity     1..10000
     * @param price        positive, at most 2 decimals
     * @param tag          correlation tag, 8..19 chars of [A-Za-z0-9_-]
     */
    public record VerifiedOrder(String exchange, String symbolToken, String tradingSymbol,
            String side, int quantity, BigDecimal price, String tag) {
        @Override public String toString() { return "VerifiedOrder[redacted]"; }
    }

    public static ObjectNode build(VerifiedOrder order) {
        if (order == null
                || !Set.of("NSE", "BSE").contains(order.exchange())
                || order.symbolToken() == null || !order.symbolToken().matches("[0-9]{1,32}")
                || order.tradingSymbol() == null
                || !order.tradingSymbol().matches("[A-Za-z0-9&._/+\\-]{1,100}")
                || !Set.of("BUY", "SELL").contains(order.side())
                || order.quantity() < 1 || order.quantity() > 10000
                || order.price() == null || order.price().signum() <= 0
                || order.price().scale() > 2
                || order.price().compareTo(new BigDecimal("10000000")) > 0
                || order.tag() == null || !order.tag().matches("[A-Za-z0-9_-]{8,19}")) {
            throw new IllegalArgumentException("Verified NSE/BSE intraday LIMIT order required");
        }
        return JSON.createObjectNode()
            .put("variety", "NORMAL")
            .put("ordertype", "LIMIT")
            .put("producttype", "INTRADAY")
            .put("duration", "DAY")
            .put("exchange", order.exchange())
            .put("symboltoken", order.symbolToken())
            .put("tradingsymbol", order.tradingSymbol())
            .put("transactiontype", order.side())
            .put("quantity", Integer.toString(order.quantity()))
            .put("price", order.price().toPlainString())
            .put("squareoff", "0")
            .put("stoploss", "0")
            .put("ordertag", order.tag());
    }
}
