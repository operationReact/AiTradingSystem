package in.aitradingsystem.tradingrooms;

import in.aitradingsystem.tradingrooms.BrokerOrderRecord.BrokerReportedStatus;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Pure, provider-agnostic reconciliation authority for a single LIVE intraday
 * cash-equity order. Given what the platform durably reserved and what the
 * customer's own selected-broker order book reports, it decides the one state
 * the order may advance to — or leaves it reserved and fails closed.
 *
 * <p>Design invariants:
 * <ul>
 *   <li>An empty or tag-unmatched broker result is NOT proof of rejection; it
 *       leaves the order reserved.</li>
 *   <li>Any missing, malformed, duplicated or contradictory evidence leaves the
 *       order reserved.</li>
 *   <li>A partial fill never resolves to a clean cancellation and never releases
 *       exposure: a cancel-after-partial stays {@code PARTIALLY_FILLED}.</li>
 *   <li>It returns only fixed, sanitized outcome codes; raw broker text never
 *       crosses this boundary.</li>
 * </ul>
 */
public final class LiveEquityReconciler {

    /** What the platform durably reserved; the authority to match against. */
    public record Expected(String broker, String tag, String exchange,
            String tradingSymbol, String side, int quantity, BigDecimal limitPrice,
            String knownBrokerOrderId) {}

    public enum Kind { RESOLVED, RESERVED }

    public record Resolution(Kind kind, LiveEquityOrderState observedState,
            String brokerOrderId, int filledQuantity, BigDecimal averageFillPrice,
            String outcomeCode) {

        static Resolution reserved(String code) {
            return new Resolution(Kind.RESERVED, null, null, 0, null, code);
        }

        static Resolution resolved(LiveEquityOrderState state, String brokerOrderId,
                int filledQuantity, BigDecimal averageFillPrice, String code) {
            return new Resolution(Kind.RESOLVED, state, brokerOrderId, filledQuantity,
                averageFillPrice, code);
        }

        public boolean reserved() { return kind == Kind.RESERVED; }
    }

    private LiveEquityReconciler() {}

    public static Resolution reconcile(Expected expected, List<BrokerOrderRecord> records) {
        if (expected == null || !LiveEquityCorrelationTag.isValid(expected.tag())
                || expected.quantity() < 1 || expected.limitPrice() == null) {
            return Resolution.reserved("RECONCILE_EXPECTED_INVALID");
        }
        if (records == null) {
            // A failed/unreadable broker history read is never a clean outcome.
            return Resolution.reserved("BROKER_HISTORY_UNAVAILABLE");
        }

        // 1. Keep only entries carrying this order's exact correlation tag.
        List<BrokerOrderRecord> tagged = new ArrayList<>();
        for (BrokerOrderRecord r : records) {
            if (r != null && expected.tag().equals(r.tag())) tagged.add(r);
        }
        if (tagged.isEmpty()) {
            // Empty / not-yet-visible history: reserved, not rejected.
            return Resolution.reserved("NO_TAGGED_BROKER_ORDER_FOUND");
        }

        // 2. Resolve to exactly one broker order identity.
        String brokerOrderId = expected.knownBrokerOrderId();
        if (brokerOrderId != null) {
            // We already observed an acknowledgement id; the tag must still point
            // only at that same order, otherwise the evidence is contradictory.
            for (BrokerOrderRecord r : tagged) {
                if (r.brokerOrderId() == null
                        || !brokerOrderId.equals(r.brokerOrderId())) {
                    return Resolution.reserved("BROKER_ORDER_IDENTITY_MISMATCH");
                }
            }
        } else {
            String only = null;
            for (BrokerOrderRecord r : tagged) {
                if (r.brokerOrderId() == null) {
                    return Resolution.reserved("BROKER_ORDER_IDENTITY_MISSING");
                }
                if (only == null) {
                    only = r.brokerOrderId();
                } else if (!only.equals(r.brokerOrderId())) {
                    return Resolution.reserved("AMBIGUOUS_DUPLICATE_BROKER_ORDERS");
                }
            }
            brokerOrderId = only;
        }

        // 3. Every tagged entry for this order must match the reserved identity.
        for (BrokerOrderRecord r : tagged) {
            if (!identityMatches(expected, r)) {
                return Resolution.reserved("BROKER_ORDER_IDENTITY_CONTRADICTS_CLAIM");
            }
        }

        // 4. Collapse the lifecycle entries to one authoritative status + fills.
        return collapse(expected, brokerOrderId, tagged);
    }

    /** Validate the order-identity fields that a provider entry carries. */
    private static boolean identityMatches(Expected expected, BrokerOrderRecord r) {
        if (r.exchange() == null || r.tradingSymbol() == null || r.side() == null
                || r.quantity() == null || r.price() == null
                || r.product() == null || r.orderType() == null || r.duration() == null) {
            return false;
        }
        return expected.exchange().equals(r.exchange())
            && expected.tradingSymbol().equals(r.tradingSymbol())
            && expected.side().equals(r.side())
            && expected.quantity() == r.quantity()
            && expected.limitPrice().compareTo(r.price()) == 0
            && "INTRADAY".equals(r.product())
            && "LIMIT".equals(r.orderType())
            && "DAY".equals(r.duration());
    }

    private static Resolution collapse(Expected expected, String brokerOrderId,
            List<BrokerOrderRecord> entries) {
        boolean sawFilled = false, sawRejected = false, sawCancelled = false;
        boolean sawPartial = false, sawOpen = false, sawUnmapped = false;
        int maxFilled = 0;
        BigDecimal averageAtMaxFilled = null;
        for (BrokerOrderRecord r : entries) {
            BrokerReportedStatus status = r.status();
            if (status == null) { sawUnmapped = true; continue; }
            Integer filled = r.filledQuantity();
            if (filled != null) {
                if (filled < 0 || filled > expected.quantity()) {
                    return Resolution.reserved("BROKER_FILL_QUANTITY_MALFORMED");
                }
                if (filled > maxFilled) {
                    maxFilled = filled;
                    averageAtMaxFilled = r.averagePrice();
                }
            }
            switch (status) {
                case FILLED -> sawFilled = true;
                case REJECTED -> sawRejected = true;
                case CANCELLED -> sawCancelled = true;
                case PARTIALLY_FILLED -> sawPartial = true;
                case OPEN -> sawOpen = true;
                case UNMAPPED -> sawUnmapped = true;
            }
        }

        // Contradictory terminal evidence (e.g. both filled and rejected) is unsafe.
        int terminals = (sawFilled ? 1 : 0) + (sawRejected ? 1 : 0) + (sawCancelled ? 1 : 0);
        if (terminals > 1) {
            return Resolution.reserved("CONTRADICTORY_TERMINAL_BROKER_STATUS");
        }

        if (sawRejected) {
            if (maxFilled != 0) {
                return Resolution.reserved("REJECTED_WITH_FILLS_CONTRADICTION");
            }
            return Resolution.resolved(LiveEquityOrderState.REJECTED, brokerOrderId,
                0, null, "BROKER_REPORTS_REJECTED");
        }
        if (sawFilled) {
            if (maxFilled != expected.quantity()) {
                return Resolution.reserved("FILLED_QUANTITY_MISMATCH");
            }
            return Resolution.resolved(LiveEquityOrderState.FILLED, brokerOrderId,
                maxFilled, averageAtMaxFilled, "BROKER_REPORTS_FILLED");
        }
        if (sawCancelled) {
            if (maxFilled > 0) {
                // Cancel-after-partial-fill: exposure must NOT be released. Keep
                // the order reserved as partially filled for operator resolution.
                return Resolution.resolved(LiveEquityOrderState.PARTIALLY_FILLED,
                    brokerOrderId, maxFilled, averageAtMaxFilled,
                    "CANCELLED_AFTER_PARTIAL_FILL_RESERVED");
            }
            return Resolution.resolved(LiveEquityOrderState.CANCELLED, brokerOrderId,
                0, null, "BROKER_REPORTS_CANCELLED");
        }
        if (sawPartial || maxFilled > 0) {
            if (maxFilled <= 0 || maxFilled >= expected.quantity()) {
                return Resolution.reserved("PARTIAL_FILL_QUANTITY_INCONSISTENT");
            }
            return Resolution.resolved(LiveEquityOrderState.PARTIALLY_FILLED,
                brokerOrderId, maxFilled, averageAtMaxFilled, "BROKER_REPORTS_PARTIAL_FILL");
        }
        if (sawOpen) {
            return Resolution.resolved(LiveEquityOrderState.ACKNOWLEDGED, brokerOrderId,
                0, null, "BROKER_REPORTS_OPEN");
        }
        // Only unmapped/unknown statuses remain: do not guess.
        if (sawUnmapped) {
            return Resolution.reserved("BROKER_STATUS_UNMAPPED");
        }
        return Resolution.reserved("BROKER_STATUS_INDETERMINATE");
    }

    /** Null-safe equality retained for clarity where provider ids may be absent. */
    static boolean sameId(String a, String b) {
        return Objects.equals(a, b);
    }
}
