-- Phase 4: persisted, explainable deterministic strategy candidates (never broker orders).
CREATE TABLE strategy_signal (
    id UUID PRIMARY KEY,
    instrument_id BIGINT NOT NULL,
    exchange VARCHAR(8) NOT NULL,
    trading_symbol VARCHAR(100) NOT NULL,
    symbol_token VARCHAR(32) NOT NULL,
    interval VARCHAR(20) NOT NULL CHECK (interval IN (
        'ONE_MINUTE','THREE_MINUTE','FIVE_MINUTE','TEN_MINUTE',
        'FIFTEEN_MINUTE','THIRTY_MINUTE','ONE_HOUR','ONE_DAY'
    )),
    action VARCHAR(10) NOT NULL CHECK (action IN ('BUY','SELL','WATCH','NO_TRADE')),
    strategy VARCHAR(80) NOT NULL,
    reference_price NUMERIC(20,6) NOT NULL CHECK (reference_price > 0),
    stop_loss NUMERIC(20,6) NOT NULL CHECK (stop_loss >= 0),
    target_price NUMERIC(20,6) NOT NULL CHECK (target_price >= 0),
    fast_average NUMERIC(20,6) NOT NULL,
    slow_average NUMERIC(20,6) NOT NULL,
    average_volume NUMERIC(24,2) NOT NULL CHECK (average_volume >= 0),
    latest_volume NUMERIC(24,2) NOT NULL CHECK (latest_volume >= 0),
    confidence NUMERIC(5,4) NOT NULL CHECK (confidence >= 0 AND confidence <= 1),
    reason_codes TEXT NOT NULL,
    source VARCHAR(40) NOT NULL,
    latest_candle_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(40) NOT NULL DEFAULT 'CANDIDATE_NOT_EXECUTABLE',
    CONSTRAINT uq_strategy_signal_version UNIQUE (instrument_id, interval, strategy, latest_candle_at)
);
CREATE INDEX ix_strategy_signal_recent ON strategy_signal (created_at DESC);
CREATE INDEX ix_strategy_signal_instrument ON strategy_signal (instrument_id, interval, created_at DESC);
