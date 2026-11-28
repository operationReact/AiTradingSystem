package in.aitradingsystem.tradingrooms;

import java.util.EnumSet;
import java.util.Set;

/** Durable state machine for customer LIVE intraday cash-equity orders. */
public enum LiveEquityOrderState {
    SUBMITTING,
    UNKNOWN,
    ACKNOWLEDGED,
    PARTIALLY_FILLED,
    FILLED,
    REJECTED,
    CANCELLED;

    private static final Set<LiveEquityOrderState> TERMINAL =
        EnumSet.of(FILLED, REJECTED, CANCELLED);

    /**
     * Unknown submission outcomes must be reconciled; this state machine never
     * permits a transition back to SUBMITTING, which would risk a duplicate order.
     */
    public boolean mayTransitionTo(LiveEquityOrderState next) {
        if (next == null || this == next || TERMINAL.contains(this)) return false;
        return switch (this) {
            case SUBMITTING -> EnumSet.of(UNKNOWN, ACKNOWLEDGED, REJECTED).contains(next);
            case UNKNOWN -> EnumSet.of(ACKNOWLEDGED, PARTIALLY_FILLED, FILLED,
                REJECTED, CANCELLED).contains(next);
            case ACKNOWLEDGED -> EnumSet.of(UNKNOWN, PARTIALLY_FILLED, FILLED,
                REJECTED, CANCELLED).contains(next);
            case PARTIALLY_FILLED -> EnumSet.of(UNKNOWN, PARTIALLY_FILLED, FILLED,
                CANCELLED).contains(next);
            case FILLED, REJECTED, CANCELLED -> false;
        };
    }

    public boolean requiresReconciliation() {
        return this == SUBMITTING || this == UNKNOWN || this == ACKNOWLEDGED
            || this == PARTIALLY_FILLED;
    }
}
