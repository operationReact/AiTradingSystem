package in.aitradingsystem.execution;

public enum OrderApprovalState {
    PENDING,
    APPROVED,
    REJECTED,
    EXPIRED,
    CANCELLED,
    AUTO_APPROVED;

    public boolean operatorDecision() {
        return this == APPROVED || this == REJECTED || this == CANCELLED;
    }
}
