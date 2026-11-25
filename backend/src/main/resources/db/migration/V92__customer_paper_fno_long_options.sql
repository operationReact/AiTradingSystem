-- Monday F&O PAPER pilot: long NSE index options only; NO broker/live orders.
-- Reuses the existing owner-scoped PAPER wallet; separate idempotent option lots.
-- Capture only executed-paper audit fields; source rights still gated at runtime.
CREATE TABLE customer_paper_fno_position (
    room_id UUID NOT NULL REFERENCES customer_paper_account(room_id) ON DELETE RESTRICT,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    instrument_key VARCHAR(72) NOT NULL CHECK (instrument_key ~ '^NSE_FO\\|[A-Za-z0-9._-]{1,64}$'),
    expiry DATE NOT NULL,
    strike_price NUMERIC(18,4) NOT NULL CHECK (strike_price > 0),
    option_type VARCHAR(2) NOT NULL CHECK (option_type IN ('CE','PE')),
    lot_size INTEGER NOT NULL CHECK (lot_size BETWEEN 1 AND 10000),
    quantity INTEGER NOT NULL DEFAULT 0 CHECK (quantity BETWEEN 0 AND 100000),
    cost_basis_paise BIGINT NOT NULL DEFAULT 0 CHECK (cost_basis_paise >= 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (room_id, instrument_key),
    CONSTRAINT ck_customer_paper_fno_position_lots
        CHECK (quantity % lot_size = 0),
    CONSTRAINT ck_customer_paper_fno_position_zero
        CHECK ((quantity = 0 AND cost_basis_paise = 0)
               OR (quantity > 0 AND cost_basis_paise > 0))
);
CREATE INDEX ix_customer_paper_fno_position_owner
    ON customer_paper_fno_position (user_id, room_id, instrument_key);

CREATE TABLE customer_paper_fno_order (
    id UUID PRIMARY KEY,
    room_id UUID NOT NULL REFERENCES customer_paper_account(room_id) ON DELETE RESTRICT,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    instrument_key VARCHAR(72) NOT NULL CHECK (instrument_key ~ '^NSE_FO\\|[A-Za-z0-9._-]{1,64}$'),
    expiry DATE NOT NULL,
    strike_price NUMERIC(18,4) NOT NULL CHECK (strike_price > 0),
    option_type VARCHAR(2) NOT NULL CHECK (option_type IN ('CE','PE')),
    side VARCHAR(4) NOT NULL CHECK (side IN ('BUY','SELL')),
    lot_size INTEGER NOT NULL CHECK (lot_size BETWEEN 1 AND 10000),
    quantity INTEGER NOT NULL CHECK (quantity BETWEEN 1 AND 100000),
    limit_price NUMERIC(18,4) NOT NULL CHECK (limit_price > 0),
    fill_price NUMERIC(18,4) NOT NULL CHECK (fill_price > 0),
    notional_paise BIGINT NOT NULL CHECK (notional_paise > 0),
    estimated_fees_paise BIGINT NOT NULL CHECK (estimated_fees_paise >= 0),
    realized_pnl_delta_paise BIGINT NOT NULL DEFAULT 0,
    cash_after_paise BIGINT NOT NULL CHECK (cash_after_paise >= 0),
    exchange_at TIMESTAMPTZ NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    idempotency_key VARCHAR(64) NOT NULL,
    payload_hash VARCHAR(64) NOT NULL CHECK (length(payload_hash)=64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (user_id,room_id,idempotency_key),
    CONSTRAINT ck_customer_paper_fno_order_lots CHECK (quantity % lot_size = 0)
);
CREATE INDEX ix_customer_paper_fno_order_owner
    ON customer_paper_fno_order (user_id,room_id,created_at DESC,id);
