package in.aitradingsystem.provider.angelone;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import in.aitradingsystem.instrument.Instrument;
import java.math.BigDecimal;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Angel One *internal* LIMIT/DAY cash-equity order adapter.
 *
 * <p>Does not hold broker credentials and is not exposed to customer HTTP
 * endpoints. A separate consent, registered-IP/broker-compliance, account-
 * wide risk reservation, idempotency, and reconciliation service must approve
 * an order before a later execution coordinator may use this payload.
 *
 * <p>See official SmartAPI order docs: only LIMIT DAY normal equity requests
 * are modeled here. Market/IOC and derivatives are deliberately unsupported.
 */
@Component
public final class AngelOneLimitOrderPayload {
    private final ObjectMapper mapper;

    public AngelOneLimitOrderPayload(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public record ApprovedParameters(
        Instrument instrument, String side, int quantity, BigDecimal limitPrice,
        String productType, String orderTag, boolean surveillanceConsent) {
        @Override
        public String toString() {
            return "ApprovedParameters[redacted]";
        }
    }

    public ObjectNode build(ApprovedParameters input) {
        if (input == null || input.instrument() == null) {
            throw new IllegalArgumentException("Canonical instrument required");
        }
        Instrument instrument = input.instrument();
        if (!Set.of("NSE", "BSE").contains(instrument.exchange())
                || !Set.of("EQ", "EQUITY", "STOCK").contains(
                    instrument.instrumentType() == null ? "" : instrument.instrumentType().toUpperCase())
                || instrument.tradingSymbol() == null || instrument.tradingSymbol().isBlank()
                || instrument.symbolToken() == null || instrument.symbolToken().isBlank()) {
            throw new IllegalArgumentException("Only canonical NSE/BSE equity instruments are supported");
        }
        if (!Set.of("BUY", "SELL").contains(input.side())
                || input.quantity() <= 0 || input.quantity() > 10000
                || !Set.of("DELIVERY", "INTRADAY").contains(input.productType())) {
            throw new IllegalArgumentException("Invalid cash order side, quantity or product");
        }
        BigDecimal price = input.limitPrice();
        if (price == null || price.signum() <= 0
                || price.scale() > 2
                || price.compareTo(new BigDecimal("10000000")) > 0) {
            throw new IllegalArgumentException("Valid limit price required");
        }
        BigDecimal tick = instrument.tickSize();
        if (tick == null || tick.signum() <= 0 || price.remainder(tick).signum() != 0) {
            throw new IllegalArgumentException("Limit price must match the canonical exchange tick size");
        }
        if (input.orderTag() == null || !input.orderTag().matches("[A-Za-z0-9_-]{1,19}")) {
            throw new IllegalArgumentException("Short idempotent broker order tag required");
        }
        ObjectNode order = mapper.createObjectNode()
            .put("variety", "NORMAL")
            .put("tradingsymbol", instrument.tradingSymbol())
            .put("symboltoken", instrument.symbolToken())
            .put("transactiontype", input.side())
            .put("exchange", instrument.exchange())
            .put("ordertype", "LIMIT")
            .put("producttype", input.productType())
            .put("duration", "DAY")
            .put("price", price.toPlainString())
            .put("squareoff", "0")
            .put("stoploss", "0")
            .put("quantity", Integer.toString(input.quantity()))
            .put("ordertag", input.orderTag());
        if (input.surveillanceConsent()) order.put("scripconsent", "yes");
        return order;
    }
}
