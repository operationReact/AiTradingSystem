-- PAPER-only, opt-in F&O Mind worker. Disabled by default.
-- Never stores customer JWTs, broker secrets, or LIVE execution authority.
CREATE TABLE mind_fno_paper_automation (
    room_id UUID PRIMARY KEY REFERENCES customer_trading_room(id) ON DELETE RESTRICT,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    next_run_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    lease_until TIMESTAMPTZ,
    last_status VARCHAR(40),
    last_reason VARCHAR(100),
    last_run_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX ix_mind_fno_paper_automation_due
    ON mind_fno_paper_automation (next_run_at, lease_until) WHERE enabled = TRUE;

CREATE TABLE mind_fno_paper_cycle_event (
    id UUID PRIMARY KEY,
    room_id UUID NOT NULL REFERENCES mind_fno_paper_automation(room_id) ON DELETE RESTRICT,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    evaluated_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(40) NOT NULL,
    reason_code VARCHAR(100) NOT NULL,
    action VARCHAR(12) NOT NULL,
    strategy_id VARCHAR(30),
    instrument_key VARCHAR(72),
    fill_id UUID,
    realized_pnl_delta_paise BIGINT,
    cash_after_paise BIGINT,
    order_reconciliation VARCHAR(40),
    wallet_reconciliation VARCHAR(50),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_mind_fno_paper_cycle_event_virtual CHECK (
        action IN ('NONE', 'ENTER', 'EXIT')
    )
);
CREATE INDEX ix_mind_fno_paper_cycle_event_latest
    ON mind_fno_paper_cycle_event (user_id, room_id, created_at DESC, id DESC);
