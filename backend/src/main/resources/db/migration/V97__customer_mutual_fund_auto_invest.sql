-- Customer-owned mutual-fund investment plans. Deliberately NO payment credentials,
-- bank account numbers, UPI PIN, API key, verified mandate, or seeded live orders.
-- Exchange/provider signed confirmations must precede ACTIVE states.
CREATE TABLE customer_mf_mandate (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    provider VARCHAR(32) NOT NULL CHECK (provider IN ('NSE_MF_INVEST', 'BSE_STAR_MF', 'FINTECH_PRIMITIVES')),
    provider_reference VARCHAR(120),
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'ACTIVE', 'FAILED', 'REVOKED', 'EXPIRED')),
    max_debit_paise BIGINT NOT NULL CHECK (max_debit_paise BETWEEN 10000 AND 1000000000),
    verified_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_customer_mf_mandate_owner UNIQUE (id, user_id),
    CONSTRAINT uq_customer_mf_mandate_provider_ref UNIQUE (provider, provider_reference),
    CONSTRAINT ck_customer_mf_verified_mandate CHECK (status <> 'ACTIVE' OR
        (verified_at IS NOT NULL AND provider_reference IS NOT NULL))
);

CREATE TABLE customer_mf_plan (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    isin CHAR(12) NOT NULL CHECK (isin ~ '^INF[A-Z0-9]{9}$'),
    scheme_name VARCHAR(160) NOT NULL CHECK (length(btrim(scheme_name)) BETWEEN 3 AND 160),
    amount_paise BIGINT NOT NULL CHECK (amount_paise BETWEEN 10000 AND 10000000),
    max_monthly_paise BIGINT NOT NULL CHECK (max_monthly_paise BETWEEN amount_paise AND 10000000),
    sip_day SMALLINT NOT NULL CHECK (sip_day BETWEEN 1 AND 28),
    first_due_on DATE NOT NULL,
    idempotency_key VARCHAR(64) NOT NULL CHECK (idempotency_key ~ '^[A-Za-z0-9_-]{8,64}$'),
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'ACTIVE', 'PAUSED', 'CANCELLED')),
    mandate_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_customer_mf_plan_id_user UNIQUE (id, user_id),
    CONSTRAINT uq_customer_mf_plan_idempotency UNIQUE (user_id, idempotency_key),
    CONSTRAINT fk_customer_mf_plan_mandate FOREIGN KEY (mandate_id, user_id)
        REFERENCES customer_mf_mandate(id, user_id) ON DELETE RESTRICT,
    CONSTRAINT ck_customer_mf_activation CHECK
        (status <> 'ACTIVE' OR mandate_id IS NOT NULL)
);
CREATE INDEX ix_customer_mf_plan_owner ON customer_mf_plan(user_id, created_at DESC);

-- This is an internal DUE INTENT, not an external purchase-order receipt.
-- Insertions cannot debit money. Activation and submission adapters require
-- a separately authorized, signed provider/mandate integration.
CREATE TABLE customer_mf_purchase_intent (
    id UUID PRIMARY KEY,
    plan_id UUID NOT NULL,
    user_id UUID NOT NULL,
    due_on DATE NOT NULL,
    amount_paise BIGINT NOT NULL CHECK (amount_paise BETWEEN 10000 AND 10000000),
    status VARCHAR(24) NOT NULL DEFAULT 'PREPARED'
        CHECK (status IN ('PREPARED', 'PROVIDER_PENDING', 'PAYMENT_PENDING',
             'ORDER_PENDING', 'ALLOTTED', 'FAILED', 'RECONCILE_REQUIRED')),
    provider_order_reference VARCHAR(120),
    provider_payment_reference VARCHAR(120),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_customer_mf_intent_plan FOREIGN KEY (plan_id, user_id)
        REFERENCES customer_mf_plan(id, user_id) ON DELETE RESTRICT,
    CONSTRAINT uq_customer_mf_plan_due UNIQUE (plan_id, due_on)
);
CREATE INDEX ix_customer_mf_intent_owner ON customer_mf_purchase_intent(user_id, created_at DESC);
COMMENT ON TABLE customer_mf_purchase_intent IS
    'Preparation receipt only; not payment success, broker order, NAV fixation, or unit allotment.';
