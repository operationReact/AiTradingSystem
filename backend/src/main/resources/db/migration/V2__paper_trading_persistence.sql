-- Durable paper-trading ledger. V1 is intentionally left unchanged (it may already be applied).

CREATE TABLE paper_account (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    starting_capital NUMERIC(18,2) NOT NULL CHECK (starting_capital > 0),
    cash_balance NUMERIC(18,2) NOT NULL CHECK (cash_balance >= 0),
    currency CHAR(3) NOT NULL DEFAULT 'INR',
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE trading_control (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    new_orders_halted BOOLEAN NOT NULL DEFAULT FALSE,
    reason VARCHAR(200),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
INSERT INTO trading_control (id, new_orders_halted) VALUES (1, FALSE);

CREATE TABLE paper_order (
    id UUID PRIMARY KEY,
    idempotency_key VARCHAR(80) NOT NULL UNIQUE,
    request_hash CHAR(64) NOT NULL,
    symbol VARCHAR(20) NOT NULL,
    side VARCHAR(4) NOT NULL CHECK (side IN ('BUY','SELL')),
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    reference_price NUMERIC(18,4) NOT NULL CHECK (reference_price > 0),
    stop_loss NUMERIC(18,4) CHECK (stop_loss IS NULL OR stop_loss > 0),
    target_price NUMERIC(18,4) CHECK (target_price IS NULL OR target_price > 0),
    signal_generated_at TIMESTAMPTZ,
    status VARCHAR(20) NOT NULL CHECK (status IN ('NEW','RISK_APPROVED','REJECTED','FILLED')),
    rejection_reason VARCHAR(300),
    correlation_id VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT paper_order_buy_levels_chk CHECK (
        side = 'SELL' OR (stop_loss IS NOT NULL AND target_price IS NOT NULL
            AND stop_loss < reference_price AND target_price > reference_price)
    )
);
CREATE INDEX idx_paper_order_created_at ON paper_order (created_at DESC);
CREATE INDEX idx_paper_order_symbol_created_at ON paper_order (symbol, created_at DESC);
CREATE INDEX idx_paper_order_status ON paper_order (status);

CREATE TABLE risk_decision (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL UNIQUE REFERENCES paper_order (id),
    decision VARCHAR(12) NOT NULL CHECK (decision IN ('APPROVED','REJECTED')),
    reason_codes JSONB NOT NULL DEFAULT '[]'::jsonb,
    risk_budget NUMERIC(18,2) NOT NULL,
    estimated_risk NUMERIC(18,2) NOT NULL,
    estimated_notional NUMERIC(18,2) NOT NULL,
    available_cash NUMERIC(18,2) NOT NULL,
    open_positions INTEGER NOT NULL,
    realized_pnl_today NUMERIC(18,2) NOT NULL,
    daily_loss_limit NUMERIC(18,2) NOT NULL,
    policy_version VARCHAR(40) NOT NULL,
    evaluated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_risk_decision_evaluated_at ON risk_decision (evaluated_at DESC);

CREATE TABLE paper_position (
    symbol VARCHAR(20) PRIMARY KEY,
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    cost_basis NUMERIC(18,2) NOT NULL CHECK (cost_basis > 0),
    stop_loss NUMERIC(18,4),
    target_price NUMERIC(18,4),
    opened_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE paper_fill (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL UNIQUE REFERENCES paper_order (id),
    symbol VARCHAR(20) NOT NULL,
    side VARCHAR(4) NOT NULL CHECK (side IN ('BUY','SELL')),
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    reference_price NUMERIC(18,4) NOT NULL,
    fill_price NUMERIC(18,4) NOT NULL CHECK (fill_price > 0),
    gross_amount NUMERIC(18,2) NOT NULL,
    fee_amount NUMERIC(18,2) NOT NULL,
    slippage_amount NUMERIC(18,2) NOT NULL,
    net_amount NUMERIC(18,2) NOT NULL,
    cost_basis NUMERIC(18,2) NOT NULL DEFAULT 0,
    realized_pnl NUMERIC(18,2) NOT NULL DEFAULT 0,
    filled_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_paper_fill_filled_at ON paper_fill (filled_at DESC);
CREATE INDEX idx_paper_fill_symbol_filled_at ON paper_fill (symbol, filled_at DESC);

CREATE TABLE paper_cash_entry (
    id BIGSERIAL PRIMARY KEY,
    order_id UUID REFERENCES paper_order (id),
    entry_type VARCHAR(24) NOT NULL CHECK (entry_type IN ('CAPITAL_SEED','BUY_SETTLEMENT','SELL_SETTLEMENT')),
    amount NUMERIC(18,2) NOT NULL,
    balance_after NUMERIC(18,2) NOT NULL CHECK (balance_after >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_paper_cash_entry_order_id ON paper_cash_entry (order_id);

CREATE TABLE audit_event (
    id BIGSERIAL PRIMARY KEY,
    event_type VARCHAR(60) NOT NULL,
    entity_type VARCHAR(40) NOT NULL,
    entity_id VARCHAR(80) NOT NULL,
    correlation_id VARCHAR(64),
    details JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_audit_event_created_at ON audit_event (created_at DESC);
CREATE INDEX idx_audit_event_entity ON audit_event (entity_type, entity_id);
