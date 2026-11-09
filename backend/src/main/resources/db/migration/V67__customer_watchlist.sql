-- Owner-scoped customer watchlist. The instrument ID is a catalogue-version identity, so the
-- exchange and trading symbol are stored as a snapshot taken when the item is added.
CREATE TABLE customer_watchlist_item (
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    instrument_id BIGINT NOT NULL CHECK (instrument_id > 0),
    exchange VARCHAR(16) NOT NULL,
    trading_symbol VARCHAR(64) NOT NULL,
    added_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, instrument_id)
);
