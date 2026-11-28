-- Durable, owner-scoped handoff from an observed Mind PAPER strategy cycle to the
-- existing guarded LIVE intraday order coordinator. A proposal is short-lived,
-- one-use per source cycle, and does not create an order until per-order consent.
CREATE TABLE customer_live_equity_strategy_proposal (
    proposal_id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    live_room_id UUID NOT NULL,
    live_mode VARCHAR(8) NOT NULL DEFAULT 'LIVE' CHECK (live_mode = 'LIVE'),
    source_paper_room_id UUID NOT NULL,
    source_cycle_id UUID NOT NULL REFERENCES mind_paper_cycle(id) ON DELETE RESTRICT,
    strategy_code VARCHAR(64) NOT NULL CHECK (strategy_code IN (
        'SMA_TREND_VOLUME_V1','OPENING_RANGE_BREAKOUT_V1','VWAP_MOMENTUM_V1',
        'MA_RECLAIM_V1','VOLUME_BREAKOUT_V1','RSI_REVERSAL_V1'
    )),
    instrument_id BIGINT NOT NULL CHECK (instrument_id > 0),
    trading_symbol VARCHAR(100) NOT NULL,
    side VARCHAR(4) NOT NULL CHECK (side IN ('BUY','SELL')),
    quantity INTEGER NOT NULL CHECK (quantity BETWEEN 1 AND 10000),
    limit_price NUMERIC(14,2) NOT NULL CHECK (limit_price > 0 AND limit_price <= 10000000),
    stop_price NUMERIC(14,2) NOT NULL CHECK (stop_price > 0 AND stop_price <= 10000000),
    target_price NUMERIC(14,2) NOT NULL CHECK (target_price > 0 AND target_price <= 10000000),
    signal_id VARCHAR(100) NOT NULL,
    candle_at TIMESTAMPTZ NOT NULL,
    quote_exchange_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ NOT NULL,
    live_order_id UUID REFERENCES customer_live_equity_order(id) ON DELETE RESTRICT,
    CONSTRAINT fk_live_strategy_proposal_room_owner_mode
        FOREIGN KEY (live_room_id,user_id,live_mode)
        REFERENCES customer_trading_room(id,user_id,mode) ON DELETE RESTRICT,
    CONSTRAINT ux_live_strategy_proposal_source UNIQUE (user_id,source_cycle_id)
);

CREATE INDEX ix_live_strategy_proposal_owner_recent
    ON customer_live_equity_strategy_proposal(user_id,live_room_id,created_at DESC);
