package in.aitradingsystem.domain;

import java.util.Set;

/** Explicit PAPER order lifecycle values. Transition policy lives in {@link OrderStateMachine}. */
public enum OrderStatus {
    NEW,
    RISK_APPROVED,
    APPROVAL_PENDING,
    READY,
    PARTIALLY_FILLED,
    FILLED,
    REJECTED,
    CANCELLED,
    EXPIRED,
    FAILED;

    public Set<OrderStatus> allowedNext() {
        return OrderStateMachine.allowedNext(this);
    }

    public boolean canTransitionTo(OrderStatus next) {
        return OrderStateMachine.canTransition(this, next);
    }

    public boolean terminal() {
        return switch (this) {
            case FILLED, REJECTED, CANCELLED, EXPIRED, FAILED -> true;
            default -> false;
        };
    }

    public OrderStatus transitionTo(OrderStatus next) {
        return OrderStateMachine.requireTransition(this, next);
    }
}
