-- Last verified full day-detail quote per instrument, so stock detail keeps real stored values when the app feed is down.
CREATE TABLE IF NOT EXISTS market_detail_snapshot (
    instrument_id BIGINT PRIMARY KEY,
    fetched_at TIMESTAMPTZ NOT NULL,
    payload JSONB NOT NULL
);
