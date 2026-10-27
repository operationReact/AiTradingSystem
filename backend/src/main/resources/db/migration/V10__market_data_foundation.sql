CREATE TABLE IF NOT EXISTS market_instrument (
    id BIGSERIAL PRIMARY KEY,
    exchange VARCHAR(16) NOT NULL,
    symbol_token VARCHAR(32) NOT NULL,
    trading_symbol VARCHAR(100) NOT NULL,
    name VARCHAR(200),
    instrument_type VARCHAR(40),
    expiry DATE,
    strike_price NUMERIC(18,4),
    lot_size INTEGER,
    tick_size NUMERIC(12,6),
    source VARCHAR(40) NOT NULL DEFAULT 'ANGEL_ONE_MASTER',
    source_updated_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (exchange, symbol_token)
);
CREATE INDEX IF NOT EXISTS idx_market_instrument_symbol ON market_instrument (upper(trading_symbol));
CREATE INDEX IF NOT EXISTS idx_market_instrument_exchange_type ON market_instrument (exchange, instrument_type);

CREATE TABLE IF NOT EXISTS market_candle (
    instrument_id BIGINT NOT NULL REFERENCES market_instrument(id) ON DELETE CASCADE,
    interval VARCHAR(8) NOT NULL,
    candle_at TIMESTAMPTZ NOT NULL,
    open_price NUMERIC(18,4) NOT NULL CHECK (open_price > 0),
    high_price NUMERIC(18,4) NOT NULL CHECK (high_price > 0),
    low_price NUMERIC(18,4) NOT NULL CHECK (low_price > 0),
    close_price NUMERIC(18,4) NOT NULL CHECK (close_price > 0),
    volume BIGINT NOT NULL CHECK (volume >= 0),
    source VARCHAR(40) NOT NULL,
    fetched_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (instrument_id, interval, candle_at),
    CHECK (high_price >= low_price),
    CHECK (high_price >= open_price AND high_price >= close_price),
    CHECK (low_price <= open_price AND low_price <= close_price)
);
CREATE INDEX IF NOT EXISTS idx_market_candle_lookup ON market_candle (instrument_id, interval, candle_at DESC);