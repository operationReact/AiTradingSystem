-- Last verified Markets overview per universe, so the page keeps showing real stored data when the app feed is unavailable.
CREATE TABLE IF NOT EXISTS market_overview_snapshot (
    universe_code VARCHAR(32) PRIMARY KEY,
    generated_at TIMESTAMPTZ NOT NULL,
    payload JSONB NOT NULL
);
