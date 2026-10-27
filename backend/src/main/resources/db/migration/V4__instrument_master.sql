-- Phase 2: versioned Angel One instrument catalogue and import audit.
-- V1-V3 are immutable; use new migrations for all schema changes.
CREATE TABLE instrument_import_run (
    id UUID PRIMARY KEY,
    status VARCHAR(20) NOT NULL CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED')),
    source_url VARCHAR(500) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    downloaded_bytes BIGINT NOT NULL DEFAULT 0 CHECK (downloaded_bytes >= 0),
    parsed_count INTEGER NOT NULL DEFAULT 0 CHECK (parsed_count >= 0),
    accepted_count INTEGER NOT NULL DEFAULT 0 CHECK (accepted_count >= 0),
    rejected_count INTEGER NOT NULL DEFAULT 0 CHECK (rejected_count >= 0),
    duplicate_count INTEGER NOT NULL DEFAULT 0 CHECK (duplicate_count >= 0),
    active_count INTEGER NOT NULL DEFAULT 0 CHECK (active_count >= 0),
    error_code VARCHAR(80),
    error_message VARCHAR(500),
    catalogue_version UUID
);

CREATE TABLE instrument_master (
    instrument_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    exchange_segment VARCHAR(20) NOT NULL,
    exchange VARCHAR(8) NOT NULL,
    symbol_token VARCHAR(32) NOT NULL,
    trading_symbol VARCHAR(100) NOT NULL,
    instrument_name VARCHAR(200),
    instrument_type VARCHAR(30),
    expiry DATE,
    strike NUMERIC(20,6),
    lot_size INTEGER NOT NULL CHECK (lot_size > 0),
    tick_size NUMERIC(20,8) NOT NULL CHECK (tick_size > 0),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    catalogue_version UUID NOT NULL REFERENCES instrument_import_run(id),
    source_updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- PostgreSQL unique constraints treat NULL values as distinct. A generated identity is used here instead of a
-- fragile expression-based primary key; catalogue rebuild enforces identity uniqueness before inserting rows.
CREATE INDEX ix_instrument_identity ON instrument_master
    (exchange_segment, symbol_token, trading_symbol, expiry, strike);
CREATE INDEX ix_instrument_search_symbol
    ON instrument_master (exchange, lower(trading_symbol), active);
CREATE INDEX ix_instrument_search_name
    ON instrument_master (exchange, lower(instrument_name), active);
CREATE INDEX ix_instrument_search_type
    ON instrument_master (exchange, instrument_type, active);
CREATE INDEX ix_instrument_active_version
    ON instrument_master (active, catalogue_version);

CREATE INDEX ix_instrument_import_started ON instrument_import_run (started_at DESC);

CREATE TABLE instrument_catalogue_state (
    singleton_id SMALLINT PRIMARY KEY DEFAULT 1 CHECK (singleton_id = 1),
    active_version UUID REFERENCES instrument_import_run(id),
    activated_at TIMESTAMPTZ
);
INSERT INTO instrument_catalogue_state (singleton_id) VALUES (1) ON CONFLICT DO NOTHING;
