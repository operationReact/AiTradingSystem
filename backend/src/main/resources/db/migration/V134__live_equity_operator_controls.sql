-- Operator controls for LIVE intraday cash-equity dispatch.
--
-- 1. Broker hosting attestation: an operator records that this deployment's
--    egress IP is registered with a broker. Attestations expire and can be
--    revoked; readiness requires an active one. It is deployment-level, not
--    per customer, because the broker sees the server's egress address.
CREATE TABLE live_equity_hosting_attestation (
    id UUID PRIMARY KEY,
    broker VARCHAR(12) NOT NULL CHECK (broker IN ('ANGEL_ONE','UPSTOX')),
    egress_ip VARCHAR(45) NOT NULL,
    operator_reference VARCHAR(200) NOT NULL CHECK (length(btrim(operator_reference)) > 0),
    attested_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    revoked_by_reference VARCHAR(200),
    CONSTRAINT ck_hosting_attestation_window CHECK (expires_at > attested_at
        AND expires_at <= attested_at + INTERVAL '90 days')
);
CREATE INDEX ix_hosting_attestation_active
    ON live_equity_hosting_attestation(broker, expires_at)
    WHERE revoked_at IS NULL;

-- 2. Per-customer account risk policies are attributed to the operator who set them.
ALTER TABLE customer_live_equity_risk_policy
    ADD COLUMN operator_reference VARCHAR(200) NOT NULL DEFAULT 'UNATTRIBUTED'
        CHECK (length(btrim(operator_reference)) > 0);

-- 3. Operator resolution of an order whose broker outcome is unknown. The only
--    supported release is NOT_PLACED, which requires an evidence reference. The
--    previous state is kept so the audit trail shows what was reserved.
CREATE TABLE live_equity_order_operator_resolution (
    order_id UUID PRIMARY KEY REFERENCES customer_live_equity_order(id) ON DELETE RESTRICT,
    resolution VARCHAR(40) NOT NULL CHECK (resolution IN ('CONFIRMED_NOT_PLACED')),
    previous_state VARCHAR(20) NOT NULL CHECK (previous_state IN ('SUBMITTING','UNKNOWN')),
    operator_reference VARCHAR(200) NOT NULL CHECK (length(btrim(operator_reference)) > 0),
    evidence_reference VARCHAR(200) NOT NULL CHECK (length(btrim(evidence_reference)) > 0),
    resolved_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE live_equity_hosting_attestation IS
    'Operator attestation of broker egress registration; readiness requires an active row.';
COMMENT ON TABLE live_equity_order_operator_resolution IS
    'Operator-attested release of a reserved LIVE equity order; every row is an audit record.';
