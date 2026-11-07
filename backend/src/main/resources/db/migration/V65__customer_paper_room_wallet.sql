-- A customer's PAPER portfolio is independent of both LIVE broker balances
-- and the legacy operator paper_account(id=1). Funds here are simulated.
-- No INSERT path for LIVE rooms; activation in the service checks room mode.
ALTER TABLE customer_trading_room
    DROP CONSTRAINT ck_customer_room_status;
ALTER TABLE customer_trading_room
    ADD CONSTRAINT ck_customer_room_status CHECK (
        (mode = 'PAPER' AND status IN ('DRAFT', 'PAPER_FUNDED'))
        OR (mode = 'LIVE' AND status = 'DRAFT')
    );

CREATE TABLE customer_paper_account (
    room_id UUID PRIMARY KEY REFERENCES customer_trading_room(id) ON DELETE RESTRICT,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    initial_cash_paise BIGINT NOT NULL CHECK (initial_cash_paise BETWEEN 100000 AND 1000000000),
    available_cash_paise BIGINT NOT NULL CHECK (available_cash_paise >= 0),
    reserved_cash_paise BIGINT NOT NULL DEFAULT 0 CHECK (reserved_cash_paise >= 0),
    realized_pnl_paise BIGINT NOT NULL DEFAULT 0,
    halted BOOLEAN NOT NULL DEFAULT TRUE,
    ledger_version BIGINT NOT NULL DEFAULT 1 CHECK (ledger_version > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_customer_paper_cash_partition CHECK (
        available_cash_paise + reserved_cash_paise <= 100000000000
    )
);
CREATE INDEX ix_customer_paper_account_owner ON customer_paper_account(user_id, room_id);

CREATE TABLE customer_paper_cash_event (
    id UUID PRIMARY KEY,
    room_id UUID NOT NULL REFERENCES customer_paper_account(room_id) ON DELETE RESTRICT,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    event_kind VARCHAR(30) NOT NULL CHECK (event_kind = 'INITIAL_PAPER_ALLOCATION'),
    cash_delta_paise BIGINT NOT NULL CHECK (cash_delta_paise > 0),
    cash_after_paise BIGINT NOT NULL CHECK (cash_after_paise >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (room_id, event_kind)
);
CREATE INDEX ix_customer_paper_event_owner
    ON customer_paper_cash_event(user_id, room_id, created_at DESC, id);

-- The starting allocation is credited exactly once on user-triggered activation.
-- No balance mutation or executable orders are exposed in this release.
