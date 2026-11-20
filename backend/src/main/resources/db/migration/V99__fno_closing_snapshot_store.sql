-- Source-labelled F&O closing snapshots for research evaluation (STR-170/171/172).
-- Rows are written only when a server-side rights gate has verified internal
-- research use for the provider and instrument (rights_basis is mandatory).
-- Retention is bounded: retain_until may not exceed session_date + 365 days,
-- and rows past retain_until are purged by the retention job.

CREATE TABLE fno_closing_snapshot (
    id                    BIGSERIAL PRIMARY KEY,
    provider_label        VARCHAR(32)   NOT NULL,
    instrument_key        VARCHAR(80)   NOT NULL,
    underlying_key        VARCHAR(96)   NOT NULL,
    session_date          DATE          NOT NULL,
    exchange_at           TIMESTAMPTZ   NOT NULL,
    observed_at           TIMESTAMPTZ   NOT NULL,
    bid                   NUMERIC(18,4) NOT NULL,
    ask                   NUMERIC(18,4) NOT NULL,
    open_interest_units   BIGINT,
    session_volume_units  BIGINT,
    rights_basis          VARCHAR(64)   NOT NULL,
    retain_until          DATE          NOT NULL,
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT fno_closing_snapshot_provider_chk CHECK (
        provider_label IN ('UPSTOX_V3', 'UPSTOX_ANALYTICS', 'ANGEL_ONE_SMARTAPI')),
    CONSTRAINT fno_closing_snapshot_instrument_chk CHECK (
        instrument_key ~ '^NSE_FO\|[A-Za-z0-9._-]{1,64}$'),
    CONSTRAINT fno_closing_snapshot_underlying_chk CHECK (
        underlying_key ~ '^(NSE_EQ\|[A-Za-z0-9._-]{1,64}|NSE_INDEX\|[A-Za-z0-9 ._-]{1,64})$'),
    CONSTRAINT fno_closing_snapshot_price_chk CHECK (bid > 0 AND ask >= bid),
    CONSTRAINT fno_closing_snapshot_time_chk CHECK (observed_at >= exchange_at),
    CONSTRAINT fno_closing_snapshot_units_chk CHECK (
        (open_interest_units IS NULL OR open_interest_units >= 0)
        AND (session_volume_units IS NULL OR session_volume_units >= 0)),
    CONSTRAINT fno_closing_snapshot_rights_chk CHECK (
        rights_basis = 'INTERNAL_RESEARCH_VERIFIED'),
    CONSTRAINT fno_closing_snapshot_retention_chk CHECK (
        retain_until >= session_date AND retain_until <= session_date + 365),
    CONSTRAINT fno_closing_snapshot_natural_key UNIQUE (provider_label, instrument_key, session_date)
);

CREATE INDEX fno_closing_snapshot_retention_idx ON fno_closing_snapshot (retain_until);
CREATE INDEX fno_closing_snapshot_underlying_session_idx
    ON fno_closing_snapshot (underlying_key, session_date);
