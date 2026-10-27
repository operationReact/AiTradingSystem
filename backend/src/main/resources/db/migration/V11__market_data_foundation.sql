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