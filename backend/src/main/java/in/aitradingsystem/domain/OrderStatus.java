package in.aitradingsystem.domain;

import java.util.EnumSet;
import java.util.Set;

/** Order lifecycle. Transitions are enforced in code and mirrored by a CHECK constraint on paper_order.status. */
public enum OrderStatus {
    NEW, RISK_APPROVED, REJECTED, FILLED;

    public Set<OrderStatus> allowedNext() {
        return switch (this) {
            case NEW -> EnumSet.of(RISK_APPROVED, REJECTED);
            case RISK_APPROVED -> EnumSet.of(FILLED);
            case REJECTED, FILLED -> EnumSet.noneOf(OrderStatus.class);
        };
    }

    public boolean canTransitionTo(OrderStatus next) {
        return allowedNext().contains(next);
    }

    public OrderStatus transitionTo(OrderStatus next) {
        if (!canTransitionTo(next)) {
            throw new IllegalStateException("Illegal order transition " + this + " -> " + next);
        }
        return next;
    }
}