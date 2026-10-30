package in.aitradingsystem.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Canonical Phase 6 PAPER order transition policy.
 *
 * <p>All PAPER callers must cross the approval and READY boundary before settlement.
 * Canonical execution uses RISK_APPROVED -> APPROVAL_PENDING/READY -> PARTIALLY_FILLED/FILLED.
 * All persistence transitions remain optimistic
 * updates constrained by both order id and expected current status.</p>
 */
public final class OrderStateMachine {
    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED = buildAllowedTransitions();

    private OrderStateMachine() {}

    public static Set<OrderStatus> allowedNext(OrderStatus current) {
        return EnumSet.copyOf(ALLOWED.get(current));
    }

    public static boolean canTransition(OrderStatus from, OrderStatus to) {
        return ALLOWED.get(from).contains(to);
    }

    public static OrderStatus requireTransition(OrderStatus from, OrderStatus to) {
        if (!canTransition(from, to)) {
            throw new IllegalStateException("Illegal order transition " + from + " -> " + to);
        }
        return to;
    }

    private static Map<OrderStatus, Set<OrderStatus>> buildAllowedTransitions() {
        EnumMap<OrderStatus, Set<OrderStatus>> transitions = new EnumMap<>(OrderStatus.class);
        transitions.put(OrderStatus.NEW,
                EnumSet.of(OrderStatus.RISK_APPROVED, OrderStatus.REJECTED, OrderStatus.CANCELLED,
                        OrderStatus.EXPIRED, OrderStatus.FAILED));
        transitions.put(OrderStatus.RISK_APPROVED,
                EnumSet.of(OrderStatus.APPROVAL_PENDING, OrderStatus.READY,
                        OrderStatus.CANCELLED, OrderStatus.EXPIRED, OrderStatus.FAILED));
        transitions.put(OrderStatus.APPROVAL_PENDING,
                EnumSet.of(OrderStatus.READY, OrderStatus.REJECTED, OrderStatus.CANCELLED,
                        OrderStatus.EXPIRED, OrderStatus.FAILED));
        transitions.put(OrderStatus.READY,
                EnumSet.of(OrderStatus.PARTIALLY_FILLED, OrderStatus.FILLED, OrderStatus.CANCELLED,
                        OrderStatus.EXPIRED, OrderStatus.FAILED));
        transitions.put(OrderStatus.PARTIALLY_FILLED,
                EnumSet.of(OrderStatus.FILLED, OrderStatus.CANCELLED, OrderStatus.EXPIRED, OrderStatus.FAILED));
        transitions.put(OrderStatus.FILLED, EnumSet.noneOf(OrderStatus.class));
        transitions.put(OrderStatus.REJECTED, EnumSet.noneOf(OrderStatus.class));
        transitions.put(OrderStatus.CANCELLED, EnumSet.noneOf(OrderStatus.class));
        transitions.put(OrderStatus.EXPIRED, EnumSet.noneOf(OrderStatus.class));
        transitions.put(OrderStatus.FAILED, EnumSet.noneOf(OrderStatus.class));
        return Map.copyOf(transitions);
    }
}
