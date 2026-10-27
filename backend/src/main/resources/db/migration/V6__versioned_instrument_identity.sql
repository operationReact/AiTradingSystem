-- Correct versioned identity uniqueness: the same instrument must be present in multiple catalogue versions.
DROP INDEX IF EXISTS uq_instrument_identity;
CREATE UNIQUE INDEX uq_instrument_identity
    ON instrument_master (catalogue_version, exchange_segment, symbol_token, trading_symbol,
        COALESCE(expiry, DATE '9999-12-31'), COALESCE(strike, -1));
