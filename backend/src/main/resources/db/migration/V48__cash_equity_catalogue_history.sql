-- V3-02: durable point-in-time research history for India cash equities only.
-- Operational instrument_master retention remains intentionally short; this table avoids retaining
-- the much larger derivatives catalogue indefinitely while preserving NSE/BSE EQ research evidence.
CREATE TABLE instrument_cash_catalogue_history (
    history_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    catalogue_version UUID NOT NULL REFERENCES instrument_import_run(id),
    instrument_id BIGINT NOT NULL,
    exchange_segment VARCHAR(20) NOT NULL,
    exchange VARCHAR(8) NOT NULL CHECK (exchange IN ('NSE', 'BSE')),
    symbol_token VARCHAR(32) NOT NULL,
    trading_symbol VARCHAR(100) NOT NULL,
    instrument_name VARCHAR(200),
    instrument_type VARCHAR(30) NOT NULL CHECK (instrument_type = 'EQ'),
    expiry DATE,
    strike NUMERIC(20,6),
    lot_size INTEGER NOT NULL CHECK (lot_size > 0),
    tick_size NUMERIC(20,8) NOT NULL CHECK (tick_size > 0),
    source_updated_at TIMESTAMPTZ NOT NULL,
    retained_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_instrument_cash_history_identity
    ON instrument_cash_catalogue_history
       (catalogue_version, exchange_segment, symbol_token, trading_symbol,
        COALESCE(expiry, DATE '9999-12-31'), COALESCE(strike, -1));

CREATE INDEX ix_instrument_cash_history_version
    ON instrument_cash_catalogue_history (catalogue_version, exchange, trading_symbol, symbol_token);

-- Backfill every cash-equity catalogue version that is still present when the migration is applied.
-- Already-purged pre-V48 catalogues cannot be reconstructed and continue to fail closed.
INSERT INTO instrument_cash_catalogue_history
    (catalogue_version, instrument_id, exchange_segment, exchange, symbol_token, trading_symbol,
     instrument_name, instrument_type, expiry, strike, lot_size, tick_size, source_updated_at)
SELECT catalogue_version, instrument_id, exchange_segment, exchange, symbol_token, trading_symbol,
       instrument_name, instrument_type, expiry, strike, lot_size, tick_size, source_updated_at
  FROM instrument_master
 WHERE active = TRUE
   AND exchange IN ('NSE', 'BSE')
   AND instrument_type = 'EQ'
ON CONFLICT DO NOTHING;
