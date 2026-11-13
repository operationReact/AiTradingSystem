-- Mintick Mind PAPER vertical slice (issue #998 PR C).
-- One row per owner-requested strategy cycle. Orders themselves remain in the
-- existing customer_paper_manual_order ledger; this table records the
-- deterministic decision trail that led to (or blocked) each virtual order.
CREATE TABLE mind_paper_cycle (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id),
    room_id UUID NOT NULL REFERENCES customer_trading_room(id),
    request_key VARCHAR(64) NOT NULL,
    instrument_id BIGINT NOT NULL,
    trading_symbol VARCHAR(100),
    candle_interval VARCHAR(20) NOT NULL,
    strategy_code VARCHAR(64) NOT NULL,
    signal_id VARCHAR(64),
    signal_action VARCHAR(16),
    candle_time TIMESTAMPTZ,
    candle_source VARCHAR(40),
    outcome VARCHAR(32) NOT NULL CHECK (outcome IN (
        'FILLED','NO_ENTRY','DATA_BLOCKED','QUOTE_UNAVAILABLE',
        'REJECTED_BY_GATE','FAILED_UNKNOWN')),
    side VARCHAR(4) CHECK (side IN ('BUY','SELL')),
    quantity INTEGER CHECK (quantity IS NULL OR quantity > 0),
    limit_price NUMERIC(20,4),
    stop_price NUMERIC(20,4),
    target_price NUMERIC(20,4),
    quote_ltp NUMERIC(20,4),
    quote_exchange_at TIMESTAMPTZ,
    order_id UUID,
    reasons VARCHAR(2000) NOT NULL,
    stages VARCHAR(2000) NOT NULL,
    backend_revision VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_mind_paper_cycle_request UNIQUE (user_id, room_id, request_key),
    CONSTRAINT ck_mind_paper_cycle_fill CHECK (
        outcome <> 'FILLED' OR (order_id IS NOT NULL AND side IS NOT NULL AND quantity IS NOT NULL))
);

CREATE INDEX idx_mind_paper_cycle_room ON mind_paper_cycle (room_id, created_at DESC);
