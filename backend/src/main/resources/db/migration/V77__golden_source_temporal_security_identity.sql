-- D20-02: research-only stable identities for 20+ years of licensed NSE/BSE equity evidence.
-- Intentionally no automatic mapping from Angel One symbol tokens to issuers/listings:
-- provider tokens, ISINs and tickers can change or be reused across corporate events.
-- Existing operational instrument_master/market_candle APIs and orders remain unchanged.
-- Attach historical rows only after an independently verified identity reconciliation.

CREATE TABLE golden_security_identity (
    security_id UUID PRIMARY KEY,
    issuer_name VARCHAR(240) NOT NULL,
    isin_when_verified VARCHAR(12),
    identity_source VARCHAR(120) NOT NULL,
    identity_evidence_digest CHAR(64) NOT NULL
        CHECK (identity_evidence_digest ~ '^[a-f0-9]{64}$'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (length(trim(issuer_name)) > 0),
    CHECK (length(trim(identity_source)) > 0),
    CHECK (isin_when_verified IS NULL OR isin_when_verified ~ '^[A-Z]{2}[A-Z0-9]{10}$')
);

CREATE TABLE golden_listing_identity (
    listing_id UUID PRIMARY KEY,
    security_id UUID NOT NULL REFERENCES golden_security_identity(security_id) ON DELETE RESTRICT,
    exchange VARCHAR(8) NOT NULL CHECK (exchange IN ('NSE', 'BSE')),
    listing_series VARCHAR(24) NOT NULL,
    listed_from DATE NOT NULL,
    delisted_on DATE,
    identity_evidence_digest CHAR(64) NOT NULL
        CHECK (identity_evidence_digest ~ '^[a-f0-9]{64}$'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_golden_listing_period CHECK (delisted_on IS NULL OR delisted_on > listed_from),
    CHECK (length(trim(listing_series)) > 0)
);
CREATE INDEX ix_golden_listing_security ON golden_listing_identity
    (security_id, exchange, listed_from);

-- A broker token/symbol is a time-bounded *alias*, never the listing primary key.
-- Keep revisions for an alias rather than overwriting a former market identity.
-- valid_to is exclusive. first_available_at is the original externally observable
-- publication time, not when the historical file was imported into MintTick.
CREATE TABLE golden_provider_symbol_alias (
    alias_id UUID PRIMARY KEY,
    listing_id UUID NOT NULL REFERENCES golden_listing_identity(listing_id) ON DELETE RESTRICT,
    provider VARCHAR(80) NOT NULL,
    exchange VARCHAR(8) NOT NULL CHECK (exchange IN ('NSE', 'BSE')),
    provider_token VARCHAR(80) NOT NULL,
    trading_symbol VARCHAR(120) NOT NULL,
    valid_from TIMESTAMPTZ NOT NULL,
    valid_to TIMESTAMPTZ,
    source_published_at TIMESTAMPTZ NOT NULL,
    first_available_at TIMESTAMPTZ NOT NULL,
    ingested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    source_event_id VARCHAR(200) NOT NULL,
    source_evidence_digest CHAR(64) NOT NULL
        CHECK (source_evidence_digest ~ '^[a-f0-9]{64}$'),
    source_revision INTEGER NOT NULL DEFAULT 1 CHECK (source_revision > 0),
    CONSTRAINT ck_golden_alias_validity CHECK (valid_to IS NULL OR valid_to > valid_from),
    CONSTRAINT ck_golden_alias_availability CHECK (
        source_published_at <= first_available_at
        AND first_available_at <= ingested_at
    ),
    CONSTRAINT uq_golden_provider_alias_revision UNIQUE (
        provider, exchange, provider_token, source_event_id, source_revision
    ),
    CHECK (length(trim(provider)) > 0),
    CHECK (length(trim(provider_token)) > 0),
    CHECK (length(trim(trading_symbol)) > 0)
);
CREATE INDEX ix_golden_alias_asof ON golden_provider_symbol_alias
    (provider, exchange, provider_token, valid_from DESC, first_available_at);
CREATE INDEX ix_golden_alias_listing_history ON golden_provider_symbol_alias
    (listing_id, valid_from, ingested_at);

-- History of splits, bonuses, dividends, rights, mergers/demergers, renames,
-- delistings and cancellation/correction notices. Do not derive adjusted prices
-- here: publication-time filters and licensed adjustment policy come later.
CREATE TABLE golden_corporate_action_evidence (
    action_id UUID PRIMARY KEY,
    listing_id UUID NOT NULL REFERENCES golden_listing_identity(listing_id) ON DELETE RESTRICT,
    event_type VARCHAR(24) NOT NULL CHECK (event_type IN (
        'SPLIT', 'BONUS', 'DIVIDEND', 'RIGHTS', 'MERGER', 'DEMERGER',
        'RENAME', 'DELISTING', 'OTHER'
    )),
    event_status VARCHAR(16) NOT NULL CHECK (
        event_status IN ('ANNOUNCED', 'REVISED', 'CANCELLED', 'EFFECTIVE')
    ),
    announcement_at TIMESTAMPTZ NOT NULL,
    first_available_at TIMESTAMPTZ NOT NULL,
    ex_date DATE,
    effective_date DATE,
    adjustment_factor NUMERIC(30,16) CHECK (adjustment_factor > 0),
    dividend_cash NUMERIC(20,6) CHECK (dividend_cash >= 0),
    source_name VARCHAR(120) NOT NULL,
    source_event_id VARCHAR(200) NOT NULL,
    source_revision INTEGER NOT NULL CHECK (source_revision > 0),
    source_evidence_digest CHAR(64) NOT NULL
        CHECK (source_evidence_digest ~ '^[a-f0-9]{64}$'),
    ingested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_golden_action_available CHECK (
        announcement_at <= first_available_at
        AND first_available_at <= ingested_at
    ),
    CONSTRAINT uq_golden_action_revision UNIQUE (
        listing_id, source_name, source_event_id, source_revision
    ),
    CHECK (length(trim(source_name)) > 0),
    CHECK (length(trim(source_event_id)) > 0)
);
CREATE INDEX ix_golden_actions_asof ON golden_corporate_action_evidence
    (listing_id, first_available_at, effective_date, source_revision);

-- Intentionally NO inserts from instrument_master / instrument_cash_catalogue_history
-- and NO change to market_candle identities. Unverified automatic token-to-issuer
-- joins would introduce lookahead bias and conflate distinct listing episodes.
