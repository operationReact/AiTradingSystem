-- Separate LIVE stock intraday order custody. The immutable PRECHECK_BLOCKED
-- customer_trade_intent table is intentionally not widened into an order ledger.
-- Submission must claim one idempotency key and one unique broker correlation
-- tag before a single broker POST; UNKNOWN outcomes remain reserved until broker
-- history reconciliation proves a state. This migration is for an undeployed
-- draft LIVE feature; LIVE dispatch stays disabled and separately gated.
CREATE TABLE customer_live_equity_order (
    id UUID PRIMARY KEY,
    room_id UUID NOT NULL,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    mode VARCHAR(8) NOT NULL DEFAULT 'LIVE' CHECK (mode = 'LIVE'),
    broker VARCHAR(12) NOT NULL CHECK (broker IN ('ANGEL_ONE','UPSTOX')),
    instrument_id BIGINT NOT NULL CHECK (instrument_id > 0),
    trading_symbol VARCHAR(100) NOT NULL,
    exchange VARCHAR(4) NOT NULL CHECK (exchange IN ('NSE','BSE')),
    side VARCHAR(4) NOT NULL CHECK (side IN ('BUY','SELL')),
    quantity INTEGER NOT NULL CHECK (quantity BETWEEN 1 AND 10000),
    product_type VARCHAR(12) NOT NULL DEFAULT 'INTRADAY' CHECK (product_type = 'INTRADAY'),
    order_type VARCHAR(8) NOT NULL DEFAULT 'LIMIT' CHECK (order_type = 'LIMIT'),
    duration VARCHAR(3) NOT NULL DEFAULT 'DAY' CHECK (duration = 'DAY'),
    limit_price NUMERIC(14,2) NOT NULL CHECK (limit_price > 0 AND limit_price <= 10000000),
    idempotency_key VARCHAR(64) NOT NULL CHECK (idempotency_key ~ '^[A-Za-z0-9_-]{8,64}$'),
    payload_hash CHAR(64) NOT NULL,
    -- Unique per-broker correlation tag, persisted before any broker POST so an
    -- UNKNOWN outcome can be reconciled from the broker's own order book/history.
    broker_tag VARCHAR(20) NOT NULL CHECK (broker_tag ~ '^[A-Za-z0-9_-]{8,20}$'),
    state VARCHAR(20) NOT NULL CHECK (state IN
        ('SUBMITTING','UNKNOWN','ACKNOWLEDGED','PARTIALLY_FILLED','FILLED','REJECTED','CANCELLED')),
    broker_order_id VARCHAR(100),
    -- Reconciled broker-reported fills; exposure is never released on a partial
    -- fill or a cancel-after-partial-fill. 0 until a fill is proven.
    filled_quantity INTEGER NOT NULL DEFAULT 0 CHECK (filled_quantity BETWEEN 0 AND 10000),
    average_fill_price NUMERIC(14,2) CHECK (average_fill_price IS NULL OR average_fill_price >= 0),
    outcome_code VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_live_equity_order_fill_le_quantity CHECK (filled_quantity <= quantity),
    CONSTRAINT fk_live_equity_order_room_owner_mode FOREIGN KEY (room_id,user_id,mode)
        REFERENCES customer_trading_room(id,user_id,mode) ON DELETE RESTRICT,
    CONSTRAINT ux_live_equity_order_idempotency UNIQUE (user_id,room_id,idempotency_key)
);

-- One broker correlation tag maps to at most one order per broker, so a broker
-- order-book read filtered by the tag cannot be aliased to another order.
CREATE UNIQUE INDEX ux_live_equity_order_broker_tag
    ON customer_live_equity_order(broker,broker_tag);
CREATE UNIQUE INDEX ux_live_equity_order_broker_order
    ON customer_live_equity_order(broker,broker_order_id)
    WHERE broker_order_id IS NOT NULL;
CREATE INDEX ix_live_equity_order_owner_recent
    ON customer_live_equity_order(user_id,room_id,created_at DESC,id);
CREATE INDEX ix_live_equity_order_unreconciled
    ON customer_live_equity_order(user_id,state,created_at)
    WHERE state IN ('SUBMITTING','UNKNOWN','ACKNOWLEDGED','PARTIALLY_FILLED');

COMMENT ON TABLE customer_live_equity_order IS
    'LIVE intraday cash-equity order custody; never PAPER fills. UNKNOWN orders are not auto-retried.';

-- Server-owned, account-wide (all rooms) intraday risk policy. Absence or an
-- unreadable row fails LIVE claims closed; it is never defaulted to permissive.
-- Values are paise-free whole INR to match planned_budget_inr accounting.
CREATE TABLE customer_live_equity_risk_policy (
    user_id UUID PRIMARY KEY REFERENCES app_user(id) ON DELETE CASCADE,
    max_account_intraday_buy_inr BIGINT NOT NULL
        CHECK (max_account_intraday_buy_inr >= 0),
    max_order_value_inr BIGINT NOT NULL CHECK (max_order_value_inr > 0),
    max_order_quantity INTEGER NOT NULL CHECK (max_order_quantity BETWEEN 1 AND 10000),
    daily_realized_loss_limit_inr BIGINT NOT NULL CHECK (daily_realized_loss_limit_inr >= 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE customer_live_equity_risk_policy IS
    'Account-wide LIVE intraday risk caps across all rooms. Missing/unreadable fails closed.';
