-- Owner-only Angel One intraday research, separate from customer market_candle.
-- Never publish, join or copy these rows into the shared Golden Source store.
CREATE TABLE owner_private_intraday_candle (
    owner_subject VARCHAR(200) NOT NULL,
    exchange VARCHAR(8) NOT NULL CHECK (exchange = 'NSE'),
    symbol_token VARCHAR(32) NOT NULL,
    trading_symbol VARCHAR(100) NOT NULL,
    interval VARCHAR(20) NOT NULL CHECK (interval IN ('FIVE_MINUTE', 'ONE_HOUR')),
    candle_time TIMESTAMPTZ NOT NULL,
    open_price NUMERIC(20,6) NOT NULL CHECK (open_price > 0),
    high_price NUMERIC(20,6) NOT NULL CHECK (high_price > 0),
    low_price NUMERIC(20,6) NOT NULL CHECK (low_price > 0),
    close_price NUMERIC(20,6) NOT NULL CHECK (close_price > 0),
    volume BIGINT NOT NULL CHECK (volume >= 0),
    source VARCHAR(48) NOT NULL CHECK (source = 'ANGEL_ONE_OWNER_PRIVATE'),
    fetched_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY(owner_subject, exchange, symbol_token, interval, candle_time),
    CONSTRAINT owner_private_intraday_ohlc CHECK (
       high_price >= low_price AND high_price >= open_price AND high_price >= close_price
       AND low_price <= open_price AND low_price <= close_price
    )
);
CREATE INDEX owner_private_intraday_lookup
  ON owner_private_intraday_candle(owner_subject, exchange, symbol_token, interval, candle_time DESC);

CREATE TABLE owner_private_intraday_cursor (
    owner_subject VARCHAR(200) NOT NULL,
    exchange VARCHAR(8) NOT NULL CHECK (exchange = 'NSE'),
    symbol_token VARCHAR(32) NOT NULL,
    interval VARCHAR(20) NOT NULL CHECK (interval IN ('FIVE_MINUTE', 'ONE_HOUR')),
    attempted_at TIMESTAMPTZ NOT NULL,
    completed_through DATE,
    status VARCHAR(16) NOT NULL CHECK (status IN ('RUNNING','SUCCEEDED','EMPTY','FAILED')),
    received_rows INT NOT NULL DEFAULT 0 CHECK (received_rows >= 0),
    error_code VARCHAR(80),
    PRIMARY KEY(owner_subject,exchange,symbol_token,interval)
);
