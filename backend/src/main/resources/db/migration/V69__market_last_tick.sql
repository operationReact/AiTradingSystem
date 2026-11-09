-- Last verified LIVE tick per cash equity from the shared owner feed.
-- prev_close is the previous session's last stored tick; NULL until a second session has been observed.
CREATE TABLE IF NOT EXISTS market_last_tick (
    instrument_id BIGINT PRIMARY KEY,
    session_date DATE NOT NULL,
    ltp NUMERIC(18, 4) NOT NULL CHECK (ltp > 0),
    prev_close NUMERIC(18, 4) CHECK (prev_close IS NULL OR prev_close > 0),
    exchange_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
