-- Separate synthetic options practice: zero NSE, Upstox, broker or market-data dependency.
-- Its starting cash is copied once from the owner-funded PAPER room, but every
-- subsequent position, fill and cash change is isolated from the real-data PAPER wallet.
CREATE TABLE customer_fno_sandbox_account (
    room_id UUID PRIMARY KEY REFERENCES customer_paper_account(room_id) ON DELETE RESTRICT,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    initial_cash_paise BIGINT NOT NULL CHECK (initial_cash_paise > 0),
    available_cash_paise BIGINT NOT NULL CHECK (available_cash_paise >= 0),
    realized_pnl_paise BIGINT NOT NULL DEFAULT 0,
    ledger_version BIGINT NOT NULL DEFAULT 0 CHECK (ledger_version >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX ix_fno_sandbox_account_owner ON customer_fno_sandbox_account(user_id, room_id);

CREATE TABLE customer_fno_sandbox_position (
    room_id UUID NOT NULL REFERENCES customer_fno_sandbox_account(room_id) ON DELETE RESTRICT,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    contract_code VARCHAR(20) NOT NULL CHECK (contract_code IN ('SIM_FNO_CE', 'SIM_FNO_PE')),
    quantity INTEGER NOT NULL CHECK (quantity >= 0 AND quantity <= 10000 AND quantity % 10 = 0),
    cost_basis_paise BIGINT NOT NULL CHECK (cost_basis_paise >= 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (room_id, contract_code),
    CONSTRAINT ck_fno_sandbox_position_basis
        CHECK ((quantity = 0 AND cost_basis_paise = 0)
            OR (quantity > 0 AND cost_basis_paise > 0))
);
CREATE INDEX ix_fno_sandbox_position_owner ON customer_fno_sandbox_position(user_id, room_id);

CREATE TABLE customer_fno_sandbox_order (
    id UUID PRIMARY KEY,
    room_id UUID NOT NULL REFERENCES customer_fno_sandbox_account(room_id) ON DELETE RESTRICT,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    contract_code VARCHAR(20) NOT NULL CHECK (contract_code IN ('SIM_FNO_CE', 'SIM_FNO_PE')),
    side VARCHAR(4) NOT NULL CHECK (side IN ('BUY', 'SELL')),
    lots INTEGER NOT NULL CHECK (lots BETWEEN 1 AND 20),
    quantity INTEGER NOT NULL CHECK (quantity BETWEEN 10 AND 200 AND quantity % 10 = 0),
    fill_price NUMERIC(18,2) NOT NULL CHECK (fill_price > 0),
    notional_paise BIGINT NOT NULL CHECK (notional_paise > 0),
    fees_paise BIGINT NOT NULL CHECK (fees_paise >= 0),
    cash_delta_paise BIGINT NOT NULL,
    cash_after_paise BIGINT NOT NULL CHECK (cash_after_paise >= 0),
    realized_pnl_delta_paise BIGINT NOT NULL DEFAULT 0,
    idempotency_key VARCHAR(64) NOT NULL CHECK (length(idempotency_key) >= 8),
    payload_hash VARCHAR(64) NOT NULL CHECK (length(payload_hash) = 64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (user_id, room_id, idempotency_key),
    CONSTRAINT ck_fno_sandbox_order_cash_delta
      CHECK ((side = 'BUY' AND cash_delta_paise < 0)
          OR (side = 'SELL' AND cash_delta_paise > 0))
);
CREATE INDEX ix_fno_sandbox_order_owner
    ON customer_fno_sandbox_order(user_id, room_id, created_at DESC, id DESC);
