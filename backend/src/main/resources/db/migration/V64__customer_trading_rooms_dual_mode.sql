-- Customer-owned Trading Rooms are planning workspaces, never independently
-- funded brokerage accounts or automatically executable strategies.
-- Mode is explicit: PAPER and LIVE are distinct plans from day one.
CREATE TABLE customer_trading_room (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    name VARCHAR(80) NOT NULL,
    focus VARCHAR(24) NOT NULL,
    planned_budget_inr BIGINT NOT NULL,
    risk_preference VARCHAR(16) NOT NULL,
    mode VARCHAR(8) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_customer_room_name CHECK (length(btrim(name)) BETWEEN 1 AND 80),
    CONSTRAINT ck_customer_room_focus CHECK (focus IN ('INTRADAY', 'SWING', 'LONG_TERM', 'HELP_ME_CHOOSE')),
    CONSTRAINT ck_customer_room_budget CHECK (planned_budget_inr BETWEEN 1000 AND 10000000),
    CONSTRAINT ck_customer_room_risk CHECK (risk_preference IN ('CAUTIOUS', 'BALANCED', 'AGGRESSIVE')),
    CONSTRAINT ck_customer_room_mode CHECK (mode IN ('PAPER', 'LIVE')),
    CONSTRAINT ck_customer_room_status CHECK (status IN ('DRAFT'))
);
CREATE INDEX ix_customer_room_owner_created
    ON customer_trading_room(user_id, created_at DESC, id);
CREATE UNIQUE INDEX ux_customer_room_owner_name
    ON customer_trading_room(user_id, lower(name));

-- Safety: no execution orders, funded balances, broker sessions, approval
-- tokens, or customer credential material are stored in this table.
