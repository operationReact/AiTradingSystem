package in.aitradingsystem.tradingrooms;

import java.math.BigDecimal;

/**
 * One normalized entry from a broker's own order book / order history, parsed
 * from a single provider record. Parsers are deliberately dumb: they normalize
 * shape and map the broker status vocabulary, but make no safety decision. All
 * matching, disambiguation and field validation is the {@link LiveEquityReconciler}'s
 * job, so there is exactly one place that can release a reserved order.
 *
 * <p>Any field a provider omits or returns malformed is left {@code null}; the
 * reconciler then fails closed rather than guessing.
 */
public record BrokerOrderRecord(
        String brokerOrderId,
        String tag,
        String exchange,
        String tradingSymbol,
        String side,
        Integer quantity,
        BigDecimal price,
        String product,
        String orderType,
        String duration,
        BrokerReportedStatus status,
        Integer filledQuantity,
        BigDecimal averagePrice) {

    /** Broker-agnostic lifecycle status after the provider vocabulary is mapped. */
    public enum BrokerReportedStatus {
        OPEN, PARTIALLY_FILLED, FILLED, REJECTED, CANCELLED,
        /** The provider status string was absent or not recognised. Fail closed. */
        UNMAPPED
    }
}
