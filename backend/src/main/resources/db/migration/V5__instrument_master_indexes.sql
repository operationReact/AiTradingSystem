-- Phase 2 follow-up: atomic identity de-duplication and version-first instrument search.
-- V4 remains immutable.
DROP INDEX IF EXISTS ix_instrument_identity;
CREATE UNIQUE INDEX uq_instrument_identity
    ON instrument_master (exchange_segment, symbol_token, trading_symbol,
        COALESCE(expiry, DATE '9999-12-31'), COALESCE(strike, -1));

DROP INDEX IF EXISTS ix_instrument_search_symbol;
DROP INDEX IF EXISTS ix_instrument_search_name;
DROP INDEX IF EXISTS ix_instrument_search_type;
DROP INDEX IF EXISTS ix_instrument_active_version;

CREATE INDEX ix_instrument_search_symbol_v
    ON instrument_master (catalogue_version, exchange, lower(trading_symbol) text_pattern_ops)
    WHERE active;
CREATE INDEX ix_instrument_search_name_v
    ON instrument_master (catalogue_version, exchange, lower(instrument_name) text_pattern_ops)
    WHERE active;
CREATE INDEX ix_instrument_search_type_v
    ON instrument_master (catalogue_version, exchange, instrument_type)
    WHERE active;
