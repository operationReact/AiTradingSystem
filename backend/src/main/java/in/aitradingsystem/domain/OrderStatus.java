package in.aitradingsystem.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Explicit PAPER order lifecycle. The direct RISK_APPROVED -> FILLED edge remains temporarily valid for the
 * existing /paper-trades/simulate compatibility path; the canonical Phase 6 order path will use READY/fill states.
 */
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
        return switch (this) {
            case NEW -> EnumSet.of(RISK_APPROVED, REJECTED, CANCELLED, EXPIRED, FAILED);
            case RISK_APPROVED -> EnumSet.of(APPROVAL_PENDING, READY, FILLED, CANCELLED, EXPIRED, FAILED);
            case APPROVAL_PENDING -> EnumSet.of(READY, REJECTED, CANCELLED, EXPIRED, FAILED);
            case READY -> EnumSet.of(PARTIALLY_FILLED, FILLED, CANCELLED, EXPIRED, FAILED);
            case PARTIALLY_FILLED -> EnumSet.of(FILLED, CANCELLED, EXPIRED, FAILED);
            case FILLED, REJECTED, CANCELLED, EXPIRED, FAILED -> EnumSet.noneOf(OrderStatus.class);
        };
    }

    public boolean canTransitionTo(OrderStatus next) {
        return allowedNext().contains(next);
    }

    public boolean terminal() {
        return switch (this) {
            case FILLED, REJECTED, CANCELLED, EXPIRED, FAILED -> true;
            default -> false;
        };
    }

    public OrderStatus transitionTo(OrderStatus next) {
        if (!canTransitionTo(next)) {
            throw new IllegalStateException("Illegal order transition " + this + " -> " + next);
        }
        return next;
    }
}
