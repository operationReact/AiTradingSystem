-- Research-only completed NSE_FO OHLCV + final OI source custody for STR-176
-- (usable by STR-177 once independently integrated). EMPTY at deployment.
-- Distinct first-known release times and distinct source/use receipts are required.
-- Never backdate an imported candle to its exchange session timestamp.
CREATE TABLE fno_verified_completed_ohlcv_oi_session (
    evidence_id BIGSERIAL PRIMARY KEY,
    provider_code VARCHAR(32) NOT NULL CHECK (
        provider_code IN ('UPSTOX_ANALYTICS', 'ANGEL_ONE_SMARTAPI')),
    instrument_key VARCHAR(80) NOT NULL CHECK (
        instrument_key ~ '^NSE_FO\|[A-Za-z0-9._-]{1,64}$'),
    underlying_key VARCHAR(96) NOT NULL CHECK (
        underlying_key ~ '^(NSE_EQ\|[A-Za-z0-9._-]{1,64}|NSE_INDEX\|[A-Za-z0-9 ._-]{1,64})$'),
    listed_from DATE NOT NULL,
    expiry DATE NOT NULL,
    lot_size INTEGER NOT NULL CHECK (lot_size BETWEEN 1 AND 100000),
    session_date DATE NOT NULL,
    open_price NUMERIC(20,6) NOT NULL CHECK (open_price > 0),
    high_price NUMERIC(20,6) NOT NULL CHECK (high_price > 0),
    low_price NUMERIC(20,6) NOT NULL CHECK (low_price > 0),
    close_price NUMERIC(20,6) NOT NULL CHECK (close_price > 0),
    traded_volume_units BIGINT NOT NULL CHECK (traded_volume_units >= 0),
    final_open_interest_units BIGINT NOT NULL CHECK (final_open_interest_units > 0),
    ohlcv_first_available_at TIMESTAMPTZ NOT NULL,
    oi_first_available_at TIMESTAMPTZ NOT NULL,
    ohlcv_source_digest CHAR(64) NOT NULL CHECK (
        ohlcv_source_digest ~ '^[a-f0-9]{64}$'),
    oi_source_digest CHAR(64) NOT NULL CHECK (
        oi_source_digest ~ '^[a-f0-9]{64}$'),
    listing_source_digest CHAR(64) NOT NULL CHECK (
        listing_source_digest ~ '^[a-f0-9]{64}$'),
    calendar_evidence_id UUID NOT NULL
        REFERENCES golden_historical_exchange_session_evidence(evidence_id) ON DELETE RESTRICT,
    ohlcv_rights_receipt_id UUID NOT NULL
        REFERENCES golden_source_rights_receipt(receipt_id) ON DELETE RESTRICT,
    oi_rights_receipt_id UUID NOT NULL
        REFERENCES golden_source_rights_receipt(receipt_id) ON DELETE RESTRICT,
    retain_until DATE NOT NULL,
    captured_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_fno_completed_ohlcv_source_session UNIQUE (
        provider_code, instrument_key, session_date),
    CONSTRAINT ck_fno_completed_ohlcv_identity CHECK (
        listed_from <= session_date AND session_date < expiry),
    CONSTRAINT ck_fno_completed_ohlcv_range CHECK (
        high_price >= open_price AND high_price >= close_price
        AND low_price <= open_price AND low_price <= close_price
        AND high_price >= low_price),
    CONSTRAINT ck_fno_completed_ohlcv_availability CHECK (
        ohlcv_first_available_at <= captured_at
        AND oi_first_available_at <= captured_at),
    CONSTRAINT ck_fno_completed_ohlcv_retention CHECK (
        retain_until >= session_date AND retain_until <= session_date + 365)
);
CREATE INDEX ix_fno_completed_ohlcv_contract_date ON
    fno_verified_completed_ohlcv_oi_session (instrument_key, session_date);
CREATE INDEX ix_fno_completed_ohlcv_retention ON
    fno_verified_completed_ohlcv_oi_session (retain_until);

CREATE FUNCTION fno_reject_completed_ohlcv_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' AND OLD.retain_until < CURRENT_DATE THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'Completed futures OHLCV/OI evidence is append-only'
        USING ERRCODE = '23514';
END
$$;
CREATE TRIGGER tr_fno_completed_ohlcv_immutable
BEFORE UPDATE OR DELETE ON fno_verified_completed_ohlcv_oi_session
FOR EACH ROW EXECUTE FUNCTION fno_reject_completed_ohlcv_mutation();
-- No INSERTs, synthetic bars, rights auto-approval or market source consumers.
