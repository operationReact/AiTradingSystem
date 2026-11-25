-- Secondary BROKER BOD reference, never mislabelled as official BSE exchange proof.
-- A new Angel One catalogue may use this as a cross-provider identity check.
CREATE TABLE upstox_bse_reference_import (
    id UUID PRIMARY KEY,
    source_url VARCHAR(300) NOT NULL,
    content_sha256 CHAR(64) NOT NULL CHECK (content_sha256 ~ '^[0-9a-f]{64}$'),
    source_fetched_at TIMESTAMPTZ NOT NULL,
    source_total_rows INTEGER NOT NULL CHECK (source_total_rows > 0),
    eligible_rows INTEGER NOT NULL CHECK (eligible_rows > 0),
    excluded_rows INTEGER NOT NULL CHECK (excluded_rows >= 0),
    imported_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE upstox_bse_reference_equity (
    import_id UUID NOT NULL REFERENCES upstox_bse_reference_import(id),
    exchange_token VARCHAR(15) NOT NULL CHECK (exchange_token ~ '^[0-9]{1,15}$'),
    trading_symbol VARCHAR(100) NOT NULL,
    isin CHAR(12) NOT NULL,
    instrument_key VARCHAR(50) NOT NULL,
    PRIMARY KEY (import_id, exchange_token)
);
CREATE TABLE upstox_bse_reference_active (
    singleton_id SMALLINT PRIMARY KEY CHECK (singleton_id = 1),
    active_import UUID REFERENCES upstox_bse_reference_import(id),
    activated_at TIMESTAMPTZ
);
INSERT INTO upstox_bse_reference_active (singleton_id) VALUES (1);
CREATE INDEX ix_upstox_bse_reference_equity_code
    ON upstox_bse_reference_equity (exchange_token);
