package in.aitradingsystem.provider.angelone;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Offline, broker-specific SmartAPI NFO LIMIT/DAY payload for NIFTY long
 * options. This bean cannot submit orders: it contains no network code.
 *
 * <p>Only the owner-scoped future broker coordinator may provide server-
 * verified contract identity, current account permissions, portfolio,
 * consent and cash/margin reservations. SELL closes a long option only;
 * uncovered writes and futures are intentionally unsupported.
 */
@Component
public final class AngelOneFnoLimitOrderPayload {
    private final ObjectMapper mapper;

    public AngelOneFnoLimitOrderPayload(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public record VerifiedParameters(
        String underlyingKey, String tradingSymbol, String symbolToken,
        String optionType, LocalDate tradingDate, LocalDate expiry,
        int lotSize, BigDecimal tickSize, int lots, String side, String focus,
        BigDecimal limitPrice, int ownedLongQuantity, String orderTag
    ) {
        @Override public String toString() { return "VerifiedParameters[redacted]"; }
    }

    public ObjectNode build(VerifiedParameters x) {
        if (x == null || !"NSE_INDEX|Nifty 50".equals(x.underlyingKey())
            || x.optionType() == null || !Set.of("CE","PE").contains(x.optionType())
            || x.tradingSymbol() == null
            || !x.tradingSymbol().matches("NIFTY[A-Za-z0-9._-]{2,55}(CE|PE)")
            || !x.tradingSymbol().endsWith(x.optionType())
            || x.symbolToken() == null || !x.symbolToken().matches("[0-9]{1,12}")
            || x.tradingDate() == null || x.expiry() == null
            || x.expiry().isBefore(x.tradingDate())
            || ChronoUnit.DAYS.between(x.tradingDate(), x.expiry()) > 90) {
            throw new IllegalArgumentException("Verified listed NIFTY option required");
        }
        if (x.lotSize() < 1 || x.lotSize() > 10000
            || x.lots() < 1 || x.lots() > 20) {
            throw new IllegalArgumentException("Invalid F&O option lot quantity");
        }
        final int quantity;
        try {
            quantity = Math.multiplyExact(x.lots(), x.lotSize());
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException("Invalid F&O option quantity");
        }
        if (quantity > 100000 || x.side() == null
            || !Set.of("BUY","SELL").contains(x.side())) {
            throw new IllegalArgumentException("Invalid F&O option side or quantity");
        }
        if ("SELL".equals(x.side())
            && (x.ownedLongQuantity() < quantity || x.ownedLongQuantity() < 0)) {
            throw new IllegalArgumentException("Naked short options are not allowed");
        }
        final String product = switch (x.focus() == null ? "" : x.focus()) {
            case "INTRADAY" -> "INTRADAY";
            case "SWING" -> "CARRYFORWARD";
            default -> throw new IllegalArgumentException("Unsupported F&O holding horizon");
        };
        BigDecimal price = x.limitPrice(), tick = x.tickSize();
        if (price == null || price.signum() <= 0 || price.scale() > 4
            || price.compareTo(new BigDecimal("1000000")) > 0
            || tick == null || tick.signum() <= 0 || tick.scale() > 4
            || price.remainder(tick).signum() != 0) {
            throw new IllegalArgumentException("Option price violates verified tick");
        }
        if (x.orderTag() == null || !x.orderTag().matches("[A-Za-z0-9_-]{8,19}")) {
            throw new IllegalArgumentException("Unique broker order tag required");
        }
        return mapper.createObjectNode()
            .put("variety", "NORMAL")
            .put("tradingsymbol", x.tradingSymbol())
            .put("symboltoken", x.symbolToken())
            .put("transactiontype", x.side())
            .put("exchange", "NFO")
            .put("ordertype", "LIMIT")
            .put("producttype", product)
            .put("duration", "DAY")
            .put("price", price.toPlainString())
            .put("squareoff", "0")
            .put("stoploss", "0")
            .put("quantity", Integer.toString(quantity))
            .put("ordertag", x.orderTag());
    }
}
