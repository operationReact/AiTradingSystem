-- Broker-neutral, customer-owned trade intent custody for both PAPER/LIVE.
-- These are durable BLOCKED preflight records, NOT executable orders or fills.
-- No broker provider API or PAPER balance mutation is triggered by inserts.
ALTER TABLE customer_trading_room
    ADD CONSTRAINT ux_customer_room_owner_mode UNIQUE (id, user_id, mode);

CREATE TABLE customer_trade_intent (
    id UUID PRIMARY KEY,
    room_id UUID NOT NULL,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    mode VARCHAR(8) NOT NULL CHECK (mode IN ('PAPER','LIVE')),
    instrument_id BIGINT NOT NULL CHECK (instrument_id > 0),
    trading_symbol VARCHAR(100) NOT NULL,
    exchange VARCHAR(4) NOT NULL CHECK (exchange IN ('NSE','BSE')),
    side VARCHAR(4) NOT NULL CHECK (side IN ('BUY','SELL')),
    quantity INTEGER NOT NULL CHECK (quantity BETWEEN 1 AND 10000),
    product_type VARCHAR(12) NOT NULL CHECK (product_type IN ('DELIVERY','INTRADAY')),
    order_type VARCHAR(8) NOT NULL DEFAULT 'LIMIT' CHECK (order_type = 'LIMIT'),
    duration VARCHAR(3) NOT NULL DEFAULT 'DAY' CHECK (duration = 'DAY'),
    limit_price NUMERIC(14,2) NOT NULL CHECK (limit_price > 0 AND limit_price <= 10000000),
    idempotency_key VARCHAR(64) NOT NULL CHECK (idempotency_key ~ '^[A-Za-z0-9_-]{8,64}$'),
    payload_hash CHAR(64) NOT NULL,
    state VARCHAR(20) NOT NULL DEFAULT 'PRECHECK_BLOCKED'
        CHECK (state = 'PRECHECK_BLOCKED'),
    blockers JSONB NOT NULL DEFAULT '[]'::jsonb
        CHECK (jsonb_typeof(blockers) = 'array'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_intent_customer_room_mode FOREIGN KEY (room_id,user_id,mode)
        REFERENCES customer_trading_room(id,user_id,mode) ON DELETE RESTRICT,
    CONSTRAINT ux_customer_trade_intent_idempotency UNIQUE (user_id,room_id,idempotency_key)
);
CREATE INDEX ix_customer_trade_intent_owner_recent
    ON customer_trade_intent(user_id,room_id,created_at DESC,id);

COMMENT ON TABLE customer_trade_intent IS
    'Durable failed-closed order preflight custody; never submission or fills';
