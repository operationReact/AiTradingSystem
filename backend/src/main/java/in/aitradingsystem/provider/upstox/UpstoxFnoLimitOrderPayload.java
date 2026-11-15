package in.aitradingsystem.provider.upstox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Offline, broker-specific payload construction for an eventual user-approved
 * Upstox LIVE NIFTY long-option order. This class NEVER calls the broker.
 *
 * <p>Callers must independently prove current customer session, NSE_FO broker
 * permission, verified listed contract/lot/tick, market and quote freshness,
 * registered outbound IP, owner-scoped consent, margin/cash reservation,
 * order-idempotency and reconciliation. A caller-supplied long balance is not
 * sufficient proof of a SELL; authoritative positions must be locked by the
 * future execution coordinator before using this builder.
 *
 * <p>Upstox V3 expects quantity in option units, not number of lots.
 * Endpoint: https://api-hft.upstox.com/v3/order/place
 */
@Component
public final class UpstoxFnoLimitOrderPayload {
    private static final String NIFTY = "NSE_INDEX|Nifty 50";
    private final ObjectMapper mapper;

    public UpstoxFnoLimitOrderPayload(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** The types/metadata below MUST be server-verified, never client-trusted. */
    public record VerifiedParameters(
        String underlyingKey, String instrumentKey, String optionType,
        LocalDate tradingDate, LocalDate expiry, int lotSize, BigDecimal tickSize,
        int lots, String side, String focus, BigDecimal limitPrice,
        int ownedLongQuantity, String orderTag
    ) {
        @Override public String toString() { return "VerifiedParameters[redacted]"; }
    }

    public ObjectNode build(VerifiedParameters x) {
        if (x == null || !NIFTY.equals(x.underlyingKey())
            || x.instrumentKey() == null
            || !x.instrumentKey().matches("NSE_FO\\|[A-Za-z0-9._-]{1,64}")
            || x.optionType() == null || !Set.of("CE", "PE").contains(x.optionType())
            || x.tradingDate() == null || x.expiry() == null
            || x.expiry().isBefore(x.tradingDate())
            || ChronoUnit.DAYS.between(x.tradingDate(), x.expiry()) > 90) {
            throw new IllegalArgumentException("Current listed NIFTY option contract required");
        }
        if (x.lotSize() < 1 || x.lotSize() > 10000
            || x.lots() < 1 || x.lots() > 20) {
            throw new IllegalArgumentException("Invalid NIFTY option lot quantity");
        }
        final int units;
        try {
            units = Math.multiplyExact(x.lots(), x.lotSize());
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException("Option quantity overflow");
        }
        if (units > 100000 || x.side() == null || !Set.of("BUY", "SELL").contains(x.side())) {
            throw new IllegalArgumentException("Unsupported option quantity or side");
        }
        // No uncovered option selling. A separate transactional coordinator
        // must verify the account's actual long position before broker send.
        if ("SELL".equals(x.side()) && (x.ownedLongQuantity() < units
            || x.ownedLongQuantity() < 0)) {
            throw new IllegalArgumentException("Uncovered F&O selling prohibited");
        }
        if (x.focus() == null) {
            throw new IllegalArgumentException("Room holding focus required");
        }
        final String product = switch (x.focus()) {
            case "INTRADAY" -> "I";
            case "SWING" -> "D";
            default -> throw new IllegalArgumentException("Unsupported option holding horizon");
        };
        final BigDecimal price = x.limitPrice();
        final BigDecimal tick = x.tickSize();
        if (price == null || price.signum() <= 0 || price.scale() > 4
            || price.compareTo(new BigDecimal("1000000")) > 0
            || tick == null || tick.signum() <= 0
            || tick.scale() > 4 || price.remainder(tick).signum() != 0) {
            throw new IllegalArgumentException("Verified option limit tick required");
        }
        if (x.orderTag() == null
            || !x.orderTag().matches("[A-Za-z0-9_-]{8,20}")) {
            throw new IllegalArgumentException("Validated unique broker order tag required");
        }
        return mapper.createObjectNode()
            .put("quantity", units)
            .put("product", product)
            .put("validity", "DAY")
            .put("price", price)
            .put("tag", x.orderTag())
            .put("instrument_token", x.instrumentKey())
            .put("order_type", "LIMIT")
            .put("transaction_type", x.side())
            .put("disclosed_quantity", 0)
            .put("trigger_price", 0)
            .put("is_amo", false)
            .put("slice", false);
    }
}
