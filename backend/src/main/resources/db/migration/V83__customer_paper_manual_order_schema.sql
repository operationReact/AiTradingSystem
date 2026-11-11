-- Idempotent, owner-scoped MANUAL Paper order fill ledger.
-- Versioned migration runs only after V82; do not apply before existing room tables.
-- No broker token, customer real-money balance, or live order is ever touched.
CREATE TABLE IF NOT EXISTS customer_paper_manual_order (
    id UUID PRIMARY KEY,
    room_id UUID NOT NULL REFERENCES customer_trading_room(id) ON DELETE RESTRICT,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    instrument_id BIGINT NOT NULL,
    exchange VARCHAR(8) NOT NULL CHECK (exchange IN ('NSE','BSE')),
    trading_symbol VARCHAR(128) NOT NULL,
    side VARCHAR(4) NOT NULL CHECK (side IN ('BUY','SELL')),
    quantity INTEGER NOT NULL CHECK (quantity BETWEEN 1 AND 10000),
    limit_price NUMERIC(18,4) NOT NULL CHECK (limit_price > 0),
    fill_price NUMERIC(18,4) NOT NULL CHECK (fill_price > 0),
    notional_paise BIGINT NOT NULL CHECK (notional_paise > 0),
    fees_paise BIGINT NOT NULL CHECK (fees_paise > 0),
    realized_pnl_delta_paise BIGINT NOT NULL DEFAULT 0,
    cash_after_paise BIGINT NOT NULL CHECK (cash_after_paise >= 0),
    exchange_at TIMESTAMPTZ NOT NULL,
    idempotency_key VARCHAR(64) NOT NULL,
    payload_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (user_id,room_id,idempotency_key)
);
CREATE INDEX IF NOT EXISTS ix_customer_paper_manual_order_owner
    ON customer_paper_manual_order(user_id,room_id,created_at DESC,id);

CREATE TABLE IF NOT EXISTS customer_paper_manual_position (
    room_id UUID NOT NULL REFERENCES customer_trading_room(id) ON DELETE RESTRICT,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    instrument_id BIGINT NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity >= 0),
    avg_cost_paise BIGINT NOT NULL CHECK (avg_cost_paise >= 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (room_id, instrument_id)
);
CREATE INDEX IF NOT EXISTS ix_customer_paper_manual_position_owner
    ON customer_paper_manual_position(user_id,room_id);
