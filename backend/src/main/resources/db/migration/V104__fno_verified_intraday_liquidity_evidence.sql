-- STR-179: EMPTY original-time NSE_FO L1/OI/volume liquidity source custody.
-- Futures only for initial adapter; option identity requires independent
-- complete strike/expiry/CE-PE master and is NOT synthesized from symbols.
-- Every quote must have independently verified provider-specific research
-- receipts, verified session, original source observation, and normalized units.
CREATE TABLE fno_verified_intraday_liquidity_evidence (
    evidence_id BIGSERIAL PRIMARY KEY,
    provider_code VARCHAR(32) NOT NULL CHECK (provider_code = 'UPSTOX_V3'),
    instrument_key VARCHAR(80) NOT NULL CHECK (
        instrument_key ~ '^NSE_FO\|[A-Za-z0-9._-]{1,64}$'),
    underlying_key VARCHAR(96) NOT NULL CHECK (
        underlying_key ~ '^(NSE_EQ\|[A-Za-z0-9._-]{1,64}|NSE_INDEX\|[A-Za-z0-9 ._-]{1,64})$'),
    expiry DATE NOT NULL,
    lot_size INTEGER NOT NULL CHECK (lot_size BETWEEN 1 AND 100000),
    instrument_kind VARCHAR(8) NOT NULL CHECK (instrument_kind = 'FUTURE'),
    bid NUMERIC(20,6) NOT NULL CHECK (bid > 0),
    ask NUMERIC(20,6) NOT NULL CHECK (ask > 0),
    bid_depth_units BIGINT NOT NULL CHECK (bid_depth_units >= 0),
    ask_depth_units BIGINT NOT NULL CHECK (ask_depth_units >= 0),
    oi_units BIGINT NOT NULL CHECK (oi_units > 0),
    cumulative_session_volume_units BIGINT NOT NULL CHECK (
        cumulative_session_volume_units >= 0),
    exchange_at TIMESTAMPTZ NOT NULL,
    first_observed_at TIMESTAMPTZ NOT NULL,
    oi_first_observed_at TIMESTAMPTZ NOT NULL,
    volume_first_observed_at TIMESTAMPTZ NOT NULL,
    exchange_session_verified BOOLEAN NOT NULL CHECK (exchange_session_verified),
    session_source_digest CHAR(64) NOT NULL CHECK (
        session_source_digest ~ '^[a-f0-9]{64}$'),
    contract_source_digest CHAR(64) NOT NULL CHECK (
        contract_source_digest ~ '^[a-f0-9]{64}$'),
    quote_source_digest CHAR(64) NOT NULL CHECK (
        quote_source_digest ~ '^[a-f0-9]{64}$'),
    oi_source_digest CHAR(64) NOT NULL CHECK (
        oi_source_digest ~ '^[a-f0-9]{64}$'),
    volume_source_digest CHAR(64) NOT NULL CHECK (
        volume_source_digest ~ '^[a-f0-9]{64}$'),
    quote_rights_receipt_id UUID NOT NULL
        REFERENCES golden_source_rights_receipt(receipt_id) ON DELETE RESTRICT,
    oi_rights_receipt_id UUID NOT NULL
        REFERENCES golden_source_rights_receipt(receipt_id) ON DELETE RESTRICT,
    volume_rights_receipt_id UUID NOT NULL
        REFERENCES golden_source_rights_receipt(receipt_id) ON DELETE RESTRICT,
    retain_until DATE NOT NULL,
    captured_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_fno_liquidity_provider_event UNIQUE (
        provider_code, instrument_key, exchange_at),
    CONSTRAINT ck_fno_liquidity_quote CHECK (ask >= bid),
    CONSTRAINT ck_fno_liquidity_original_time CHECK (
        exchange_at <= first_observed_at
        AND oi_first_observed_at <= first_observed_at
        AND volume_first_observed_at <= first_observed_at
        AND first_observed_at <= captured_at),
    CONSTRAINT ck_fno_liquidity_expiry CHECK (
        expiry >= (exchange_at AT TIME ZONE 'Asia/Kolkata')::DATE),
    CONSTRAINT ck_fno_liquidity_retention CHECK (
        retain_until >= (exchange_at AT TIME ZONE 'Asia/Kolkata')::DATE
        AND retain_until <= (exchange_at AT TIME ZONE 'Asia/Kolkata')::DATE + 1)
);
CREATE INDEX ix_fno_liquidity_contract_event
    ON fno_verified_intraday_liquidity_evidence (instrument_key, exchange_at DESC);
CREATE INDEX ix_fno_liquidity_retention
    ON fno_verified_intraday_liquidity_evidence (retain_until);

CREATE FUNCTION fno_reject_intraday_liquidity_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE'
        AND OLD.retain_until < (now() AT TIME ZONE 'Asia/Kolkata')::DATE THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'Intraday liquidity source receipts immutable until expiry'
        USING ERRCODE = '23514';
END
$$;
CREATE TRIGGER tr_fno_intraday_liquidity_immutable
BEFORE UPDATE OR DELETE ON fno_verified_intraday_liquidity_evidence
FOR EACH ROW EXECUTE FUNCTION fno_reject_intraday_liquidity_mutation();
-- Zero seeded rows; no automatic broker polling or privileges.
