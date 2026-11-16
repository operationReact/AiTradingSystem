-- Durable, owner-scoped UPSTOX LIVE F&O submission receipt. This is real-money
-- custody and MUST NOT be confused with the independent PAPER option ledger.
-- A committed SUBMITTING/UNKNOWN row is never blindly resubmitted.
CREATE TABLE customer_upstox_fno_live_order (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    room_id UUID NOT NULL,
    mode VARCHAR(8) NOT NULL DEFAULT 'LIVE' CHECK (mode = 'LIVE'),
    instrument_key VARCHAR(80) NOT NULL CHECK (instrument_key ~ '^NSE_FO\|[A-Za-z0-9._-]{1,64}$'),
    option_type VARCHAR(2) NOT NULL CHECK (option_type IN ('CE','PE')),
    expiry DATE NOT NULL,
    side VARCHAR(4) NOT NULL CHECK (side IN ('BUY','SELL')),
    quantity INTEGER NOT NULL CHECK (quantity BETWEEN 1 AND 100000),
    lot_size INTEGER NOT NULL CHECK (lot_size BETWEEN 1 AND 10000),
    product VARCHAR(1) NOT NULL CHECK (product IN ('D','I')),
    limit_price NUMERIC(14,4) NOT NULL CHECK (limit_price > 0),
    estimated_premium_paise BIGINT NOT NULL CHECK (estimated_premium_paise > 0),
    idempotency_key VARCHAR(64) NOT NULL CHECK (idempotency_key ~ '^[A-Za-z0-9_-]{8,64}$'),
    payload_hash CHAR(64) NOT NULL,
    order_tag VARCHAR(20) NOT NULL CHECK (order_tag ~ '^[A-Za-z0-9_-]{8,20}$'),
    broker_order_id VARCHAR(64),
    state VARCHAR(20) NOT NULL DEFAULT 'SUBMITTING'
        CHECK (state IN ('SUBMITTING','ACKNOWLEDGED','UNKNOWN','REJECTED','COMPLETE','CANCELLED')),
    outcome_code VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_upstox_fno_live_owner FOREIGN KEY (room_id,user_id,mode)
        REFERENCES customer_trading_room(id,user_id,mode) ON DELETE RESTRICT,
    CONSTRAINT ux_upstox_fno_live_idempotency UNIQUE (user_id,room_id,idempotency_key),
    CONSTRAINT ux_upstox_fno_live_tag UNIQUE (user_id,order_tag)
);
CREATE INDEX ix_upstox_fno_live_active
    ON customer_upstox_fno_live_order (user_id,state,created_at DESC);
CREATE INDEX ix_upstox_fno_live_room_recent
    ON customer_upstox_fno_live_order (user_id,room_id,created_at DESC);

COMMENT ON TABLE customer_upstox_fno_live_order IS
    'Real Upstox order intention and broker acknowledgement; never a fill claim. Unknown submissions require broker tag reconciliation; no blind retry.';
