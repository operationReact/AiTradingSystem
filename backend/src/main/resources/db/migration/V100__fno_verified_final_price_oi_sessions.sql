-- STR-174: an EMPTY, immutable, source-attested final futures CLOSE + OI boundary.
-- No data is seeded, downloaded or automatically promoted from quotes or candles.
-- A future independently reviewed provider import must prove both first-known
-- timestamps, source receipts and an exchange derivatives session calendar.
-- "Personal use" or successful broker authentication does not prove those facts.

CREATE TABLE fno_verified_final_price_oi_session (
    id BIGSERIAL PRIMARY KEY,
    provider_label VARCHAR(32) NOT NULL
        CHECK (provider_label IN ('UPSTOX_ANALYTICS', 'ANGEL_ONE_SMARTAPI')),
    instrument_key VARCHAR(80) NOT NULL
        CHECK (instrument_key ~ '^NSE_FO\|[A-Za-z0-9._-]{1,64}$'),
    underlying_key VARCHAR(96) NOT NULL
        CHECK (underlying_key ~ '^(NSE_EQ\|[A-Za-z0-9._-]{1,64}|NSE_INDEX\|[A-Za-z0-9 ._-]{1,64})$'),
    expiry_date DATE NOT NULL,
    lot_size INTEGER NOT NULL CHECK (lot_size BETWEEN 1 AND 100000),
    session_date DATE NOT NULL,
    final_close_price NUMERIC(20,6) NOT NULL CHECK (final_close_price > 0),
    final_open_interest_units BIGINT NOT NULL CHECK (final_open_interest_units > 0),
    traded_volume_units BIGINT NOT NULL CHECK (traded_volume_units >= 0),
    final_price_first_available_at TIMESTAMPTZ NOT NULL,
    finalized_oi_first_available_at TIMESTAMPTZ NOT NULL,
    price_evidence_sha256 CHAR(64) NOT NULL CHECK (price_evidence_sha256 ~ '^[a-f0-9]{64}$'),
    oi_evidence_sha256 CHAR(64) NOT NULL CHECK (oi_evidence_sha256 ~ '^[a-f0-9]{64}$'),
    calendar_evidence_id UUID NOT NULL
        REFERENCES golden_historical_exchange_session_evidence(evidence_id) ON DELETE RESTRICT,
    price_rights_receipt_id UUID NOT NULL
        REFERENCES golden_source_rights_receipt(receipt_id) ON DELETE RESTRICT,
    oi_rights_receipt_id UUID NOT NULL
        REFERENCES golden_source_rights_receipt(receipt_id) ON DELETE RESTRICT,
    retain_until DATE NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fno_final_oi_one_session UNIQUE (provider_label, instrument_key, session_date),
    CONSTRAINT fno_final_oi_expiry CHECK (expiry_date > session_date),
    CONSTRAINT fno_final_oi_retention CHECK (
        retain_until >= session_date AND retain_until <= session_date + 365),
    CONSTRAINT fno_final_oi_first_known_recorded CHECK (
        final_price_first_available_at <= recorded_at
        AND finalized_oi_first_available_at <= recorded_at)
);
CREATE INDEX fno_verified_final_price_oi_retention_idx
    ON fno_verified_final_price_oi_session(retain_until);
CREATE INDEX fno_verified_final_price_oi_contract_idx
    ON fno_verified_final_price_oi_session(instrument_key, session_date);

-- Research histories are immutable. Corrections must be independently reviewed
-- (not silently UPDATEd) before enabling a future source adapter.
CREATE FUNCTION fno_reject_verified_final_price_oi_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    -- Permit automatic expiry erasure, but no edits or early deletion.
    IF TG_OP = 'DELETE' AND OLD.retain_until < CURRENT_DATE THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'Verified F&O final session evidence is append-only'
        USING ERRCODE = '23514';
END
$$;
CREATE TRIGGER fno_verified_final_price_oi_immutable
BEFORE UPDATE OR DELETE ON fno_verified_final_price_oi_session
FOR EACH ROW EXECUTE FUNCTION fno_reject_verified_final_price_oi_mutation();

-- Explicitly no INSERTs, credential reads, source polling, rights auto-grants,
-- broker order paths or automatic quote/market_candle conversions.
