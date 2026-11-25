-- Phase 7 / P07-W02: durable approval evidence tied to the exact execution policy and risk decision.

CREATE TABLE order_approval (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id UUID NOT NULL UNIQUE REFERENCES paper_order(id),
    policy_id UUID NOT NULL REFERENCES execution_policy(id),
    policy_version INTEGER NOT NULL,
    state VARCHAR(20) NOT NULL,
    decision_reason VARCHAR(300),
    requested_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    decided_at TIMESTAMPTZ,
    decided_by VARCHAR(120),
    row_version INTEGER NOT NULL DEFAULT 0,
    risk_decision_id UUID NOT NULL REFERENCES risk_decision(id),
    request_snapshot JSONB NOT NULL,

    CONSTRAINT ck_order_approval_state CHECK (
        state IN ('PENDING','APPROVED','REJECTED','EXPIRED','CANCELLED','AUTO_APPROVED')
    ),
    CONSTRAINT ck_order_approval_expiry CHECK (expires_at > requested_at),
    CONSTRAINT ck_order_approval_row_version CHECK (row_version >= 0),
    CONSTRAINT ck_order_approval_snapshot_object CHECK (jsonb_typeof(request_snapshot) = 'object'),
    CONSTRAINT ck_order_approval_decision_time CHECK (decided_at IS NULL OR decided_at >= requested_at)
);

CREATE INDEX idx_order_approval_state_requested
    ON order_approval (state, requested_at DESC);

CREATE INDEX idx_order_approval_policy
    ON order_approval (policy_id, policy_version);

-- P06-W01 deliberately deferred this reference until the Phase 7 approval table existed.
ALTER TABLE paper_order
    ADD COLUMN approval_id UUID NULL REFERENCES order_approval(id);

CREATE INDEX idx_paper_order_approval
    ON paper_order (approval_id)
    WHERE approval_id IS NOT NULL;
